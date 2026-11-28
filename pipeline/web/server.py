"""Local web interface for T2x2 montage."""
import asyncio
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import uuid

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field, model_validator
from access import AccessGate

ROOT = Path(__file__).resolve().parent
PIPE = ROOT.parent
DATA = Path(os.environ.get("T2X2_WEB_DATA", "/workspace/t2x2/web-jobs")).resolve()
PYTHON = os.environ.get("T2X2_WORKER_PYTHON", "/workspace/t2x2/.venv/bin/python")
CHANNEL = "https://www.twitch.tv/t2x2"
YTDLP = [PYTHON, "-m", "yt_dlp", "--no-check-certificates", "--socket-timeout", "8",
         "--no-warnings"]
queue = threading.Condition(threading.RLock())
TECHNICAL_JOBS = frozenset({
    "2697fd41c56b42879f9970427c5f7faa",
    "1ccac7c80dce4045a5e67ed10f3389cc",
    "ccce2debe84845b79d690cb17d64ab23",
})


class YieldAnalysis(Exception):
    pass


def bounded_analysis(operation):
    recipe = operation.get("recipe", {})
    end = recipe.get("end")
    return operation.get("action") == "analysis" and end is not None and end-recipe.get("start", 0) <= 900
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
gate = AccessGate()


class Analysis(BaseModel):
    vod: str = Field(pattern=r"^(live|[0-9]{1,20})$")
    start: float = Field(default=0, ge=0, le=1000000)
    end: float | None = Field(default=None, gt=0, le=1000000)

    @model_validator(mode="after")
    def range_valid(self):
        if self.end is not None and self.end <= self.start:
            raise ValueError("Конец должен быть после начала.")
        if self.vod == "live" and self.start:
            raise ValueError("Живой захват начинается с текущего момента.")
        return self


class Render(BaseModel):
    start: float = Field(ge=0, le=1000000)
    end: float = Field(gt=0, le=1000000)
    kind: str = Field(pattern=r"^(game|reaction|chat)$")
    title: str = Field(min_length=1, max_length=70)
    confirmed: bool = False
    preview: bool = False
    original: str | None = Field(default=None, pattern=r"^https://[^\s]+$")
    next_game: str | None = Field(default=None, max_length=80)


class Short(BaseModel):
    start: float = Field(ge=0, le=100000)
    end: float = Field(gt=0, le=100000)


def save(path, value):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False))
    tmp.replace(path)


def read(path):
    return json.loads(path.read_text())


def job_path(job):
    folder = DATA / job
    if not re.fullmatch(r"[a-f0-9]{32}", job) or not (folder / "job.json").is_file():
        raise HTTPException(404, "Задача не найдена.")
    return folder


def execute(command, timeout, folder=None):
    if folder is None:
        return subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=True).stdout
    with (folder / "worker.log").open("a") as log:
        subprocess.run(command, stdout=log, stderr=log, timeout=timeout, check=True)


def audio_matches(first, first_at, second, second_at):
    """Check the two Twitch renditions share the same audio clock before reuse."""
    from array import array
    from math import sqrt
    def pcm(path, at):
        data=subprocess.check_output(["ffmpeg","-nostdin","-v","error","-ss",str(at),
            "-i",str(path),"-t","4","-vn","-ac","1","-ar","8000","-f","s16le","-"],
            timeout=20)
        return array("h",data)
    try:
        a,b=pcm(first,first_at),pcm(second,second_at)
        if len(a)<24000 or len(a)!=len(b):
            return False
        power=sum(x*x for x in a)*sum(y*y for y in b)
        return bool(power) and sum(x*y for x,y in zip(a,b))/sqrt(power)>=.90
    except (OSError,subprocess.SubprocessError):
        return False


def now():
    return datetime.now(timezone.utc).isoformat()


def stage(folder, label, **extra):
    with queue:
        state = read(folder / "job.json")
        if state.get("stage") != label or not state.get("stage_since"):
            state["stage_since"] = now()
        state.update(stage=label, **extra)
        save(folder / "job.json", state)
        with (folder / "events.jsonl").open("a") as out:
            out.write(json.dumps({"utc": now(), "stage": label}, ensure_ascii=False) + "\n")


def command_id(value):
    if value is None:
        return uuid.uuid4().hex
    try:
        return uuid.UUID(value).hex
    except ValueError:
        raise HTTPException(400, "Некорректный идентификатор команды.")


def enqueue(folder, state, action, recipe, key):
    if key in state.get("accepted", {}):
        if state["accepted"][key] != {"action": action, "recipe": recipe}:
            raise HTTPException(409, "Этот идентификатор уже использован.")
        return {"id": folder.name}
    if state.get("operation") and state["operation"]["status"] in {"queued", "running"}:
        raise HTTPException(409, "Эта задача уже выполняется или стоит в очереди.")
    state.setdefault("accepted", {})[key] = {"action": action, "recipe": recipe}
    state.update(stage="В очереди", stage_since=now(), error=None,
                 operation=dict(id=key, action=action, recipe=recipe, status="queued", queued_at=now()))
    save(folder / "job.json", state)
    queue.notify()
    return {"id": folder.name}


def run_queue():
    actions = {"analysis": (Analysis, analyze), "render": (Render, render), "short": (Short, render_short)}
    while True:
        with queue:
            waiting = []
            for path in DATA.glob("*/job.json"):
                state = read(path)
                operation = state.get("operation") or {}
                if operation.get("status") == "queued" and not state.get("duplicate_of"):
                    priority = operation["action"] == "analysis" and not bounded_analysis(operation)
                    waiting.append((priority, operation["queued_at"], path, state))
            if not waiting:
                queue.wait()
                continue
            _, _, path, state = min(waiting)
            operation = state["operation"]
            operation["status"] = "running"
            save(path, state)
        try:
            model, handler = actions[operation["action"]]
            arguments = [model.model_validate(operation["recipe"])]
            if operation["action"] == "short":
                arguments.append(operation["id"])
            handler(path.parent, *arguments)
            status = "completed"
        except YieldAnalysis:
            stage(path.parent, "В очереди")
            status = "queued"
        except Exception as exc:
            with (path.parent / "worker.log").open("a") as log:
                log.write(f"\n{type(exc).__name__}: {exc}\n")
            stage(path.parent, "Ошибка", error="Этап остановлен. Подробности в журнале задачи.")
            status = "failed"
        with queue:
            state = read(path)
            state["operation"]["status"] = status
            save(path, state)


def captured_live_duration(folder):
    for name in ("source.mkv", "source.partial.mkv"):
        path = folder / name
        if path.is_file():
            try:
                return float(execute(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                      "-of", "default=nk=1:nw=1", str(path)], 5).strip())
            except (OSError, subprocess.SubprocessError, ValueError):
                continue
    return None


def recover_jobs():
    for path in DATA.glob("*/job.json"):
        state = read(path)
        operation = state.get("operation")
        if operation and operation["status"] == "running":
            live = operation["action"] == "analysis" and state["vod"] == "live"
            captured = all((path.parent / name).is_file() for name in ("source.mkv", "source.manifest.json"))
            already_done = {
                "analysis": {"Карта готова"}, "render": {"Готово", "На проверке"},
                "short": {"Готово", "На проверке"},
            }
            if state["stage"] in already_done[operation["action"]]:
                operation["status"] = "completed"
                save(path, state)
                continue
            operation["status"] = "interrupted" if live and not captured else "queued"
            if live and not captured:
                state["captured_live_seconds"] = captured_live_duration(path.parent)
            state.update(stage="Прямой эфир прерван" if live and not captured else "Возобновление",
                         stage_since=now(), error=None)
            save(path, state)
        elif not operation and state["stage"] not in (
            "Карта готова", "Готово", "На проверке", "Ошибка", "Ожидает перезапуска"
        ):
            plan = path.parent / "render-request.json"
            live = state["vod"] == "live"
            capture = (path.parent / "source.manifest.json").exists()
            if state["stage"] == "Рендер Shorts":
                stage(path.parent, "Ожидает перезапуска", error="Выбери диапазон Shorts повторно.")
                continue
            action = "render" if plan.exists() else "analysis"
            recipe = read(plan) if plan.exists() else Analysis(
                vod=state["vod"], start=state["requested_start"], end=state["requested_end"]
            ).model_dump()
            interrupted = live and not capture
            state.update(stage="Прямой эфир прерван" if interrupted else "Возобновление", stage_since=now(),
                         operation=dict(id=uuid.uuid4().hex, action=action, recipe=recipe,
                                        status="interrupted" if interrupted else "queued", queued_at=now()))
            if interrupted:
                state["captured_live_seconds"] = captured_live_duration(path.parent)
            if action == "render":
                state["render_selection"] = recipe
            save(path, state)


@app.on_event("startup")
def recover_interrupted():
    with queue:
        recover_jobs()
        current = DATA / "e8ef4c8472fa479f8012d0c3a49a0583" / "job.json"
        duplicate = DATA / "fdb1eec3e39645788828cb6a596f8fe9" / "job.json"
        if current.exists() and duplicate.exists():
            original, copy = read(current), read(duplicate)
            keys = ("vod", "requested_start", "requested_end")
            if all(original.get(k) == copy.get(k) for k in keys) and copy.get("operation", {}).get("status") == "queued":
                copy["operation"]["status"] = "duplicate"
                copy["duplicate_of"] = original["id"]
                save(duplicate, copy)
    threading.Thread(target=run_queue, daemon=True, name="montage-queue").start()


def proposals(rows, offset, category=None):
    """Transcript-based suggestions. A human confirms semantic boundaries."""
    result = []
    groups = []
    for row in rows:
        if not groups or row["start"] - groups[-1][-1]["end"] > 20 or row["end"] - groups[-1][0]["start"] > 240:
            groups.append([])
        groups[-1].append(row)
    for group in groups:
        a, b = group[0]["start"] + offset, group[-1]["end"] + offset
        if b-a < 12:
            continue
        excerpt = " ".join(row["text"].strip() for row in group)
        names = (
            (r"тренировк\w*.{0,100}(?:люс[иея]|нейт)|(?:люс[иея]|нейт).{0,100}тренировк", "game", "Тренировка Люсии"),
            (r"смотрим\s+(?:новый\s+)?ролик", "reaction", None),
            (r"ограблени\w*|грабим\s+магазин", "game", None),
        )
        match = next(((kind, title) for pattern, kind, title in names if re.search(pattern, excerpt, re.I)), None)
        if match:
            kind, title = match
        elif re.search(r"квест|мисси[яию]|геймплей|персонаж|в игре|сейв", excerpt, re.I):
            kind, title = "game", None
        else:
            kind, title = "chat", None
        result.append(dict(start=round(a, 3), end=round(b, 3), kind=kind, suggested_title=title,
                           excerpt=excerpt[:420], category=category,
                           confidence="Проверь тип, название и границы по исходнику"))
    return result


def analyze(folder, request):
    stage(folder, "Получаю данные Twitch", error=None)
    url = CHANNEL if request.vod == "live" else f"https://www.twitch.tv/videos/{request.vod}"
    source = folder / "source.mkv"
    if not source.exists():
        info = json.loads(execute(YTDLP + ["-j", url], 35))
        formats = [f for f in info["formats"] if f.get("height") and f.get("url")]
        video = max(formats, key=lambda f: (f["height"], f.get("fps") or 0))
        stage(folder, "Скачиваю материал", vod_duration=info.get("duration"),
              category=(info.get("categories") or [None])[0], title=info.get("title"),
              date=datetime.fromtimestamp(info["timestamp"], timezone.utc).strftime("%d.%m.%y")
              if info.get("timestamp") else None)
        if request.vod == "live" and not info.get("is_live"):
            raise ValueError("Twitch не подтвердил прямой эфир.")
        if info.get("is_live"):
            length = min(request.end or 180, 900)
            execute(["ffmpeg", "-nostdin", "-y", "-v", "error", "-rw_timeout", "18000000",
                     "-i", video["url"], "-t", str(length), "-c", "copy",
                     str(source.with_suffix(".partial.mkv"))], length+45, folder)
            source.with_suffix(".partial.mkv").replace(source)
            save(folder / "source.manifest.json", {"offset": 0, "duration": length})
        else:
            end = min(request.end or info["duration"], info["duration"])
            audio = next((f for f in info["formats"] if f["format_id"].lower() == "audio_only"), video)
            save(folder / "formats.json", {"formats":[audio, video]})
            execute([PYTHON, str(PIPE/"pipeline_source.py"), str(folder/"formats.json"), audio["format_id"],
                     str(request.start), str(end), str(source)], max(180, (end-request.start)/2), folder)
    manifest = read(folder / "source.manifest.json")
    stage(folder, "Расшифровка")
    transcript = folder / "words.jsonl"
    if not transcript.exists():
        import math
        total=math.ceil(manifest["duration"]/360)
        temporary=transcript.with_suffix(".partial.jsonl")
        with temporary.open("w") as out:
            for part in range(total):
                at=part*360
                pcm=folder/f"audio-{part}.wav"
                words=folder/f"words-{part}.jsonl"
                spec=folder/f"words-{part}.input.json"
                computed = not words.exists()
                if computed:
                    execute(["ffmpeg","-nostdin","-y","-v","error","-ss",str(at),"-i",str(source),
                             "-t",str(min(360,manifest["duration"]-at)),"-vn","-ar","16000","-ac","1",str(pcm)],
                            100,folder)
                    save(spec,{"source":str(pcm)})
                    execute([PYTHON,str(PIPE/"pipeline.py"),"worker","words",str(spec),str(words)],750,folder)
                for line in words.read_text().splitlines():
                    row=json.loads(line)
                    for record in [row,*row["words"]]:
                        record["start"]+=at
                        record["end"]+=at
                    out.write(json.dumps(row,ensure_ascii=False)+"\n")
                if computed or part+1 >= read(folder/"job.json").get("asr", {}).get("done", 0):
                    stage(folder,"Расшифровка",asr=dict(done=part+1,total=total))
                if computed and part+1 < total and (request.end is None or request.end-request.start > 900):
                    with queue:
                        if any(bounded_analysis(op) or op.get("action") in {"render", "short"}
                               for path in DATA.glob("*/job.json")
                               if path.parent != folder
                               and (op := read(path).get("operation") or {}).get("status") == "queued"):
                            raise YieldAnalysis()
        temporary.replace(transcript)
    rows = [json.loads(line) for line in transcript.read_text().splitlines()]
    selections = proposals(rows, manifest["offset"],read(folder/"job.json").get("category"))
    stage(folder, "Карта готова", proposals=selections,
          start=manifest["offset"], end=manifest["offset"]+manifest["duration"])


def render(folder, request):
    state = read(folder / "job.json")
    folder = folder / "montage"
    folder.mkdir(exist_ok=True)
    parent = folder.parent
    stage(parent, "Готовлю видео", error=None)
    source = parent / "source.mkv"
    if state["vod"] == "live":
        offset = 0
    else:
        source = folder / "hd.mkv"
        formats = folder / "video-format.json"
        if not source.exists():
            info=json.loads(execute(YTDLP+["-j",f"https://www.twitch.tv/videos/{state['vod']}"],35))
            video=max((f for f in info["formats"] if f.get("height") and f.get("url")),
                      key=lambda f:(f["height"],f.get("fps") or 0))
            save(formats,{"formats":[video]})
            media_end=state.get("vod_duration") or state["end"]
            execute([PYTHON,str(PIPE/"pipeline_source.py"),str(formats),video["format_id"],
                     str(max(0,request.start-12)),str(min(request.end+12,media_end)),str(source)],
                    max(180, (request.end-request.start)/2), parent)
        offset = read(source.with_suffix(".manifest.json"))["offset"]
    verb = {"game":"ИГРАЕТ", "reaction":"СМОТРИТ", "chat":"ОБСУЖДАЕТ"}[request.kind]
    plan = dict(keep=[[request.start,request.end]], cuts=[], kind=request.kind,
                title=f"T2x2 {verb}: {request.title}", opening="Начало эпизода",
                reviewed=True, deviations=[], tags=["t2x2","нарезка"], hashtags="#t2x2 #нарезка",
                date=state.get("date"), next_game=request.next_game,
                original=request.original, original_verified=bool(request.original))
    review_path = folder / "mask-review.json"
    if review_path.is_file():
        import hashlib
        review = read(review_path)
        with source.open("rb") as handle:
            fingerprint = hashlib.file_digest(handle, "sha256").hexdigest()
        if review.get("source_sha256") != fingerprint or review.get("keep") != plan["keep"]:
            raise ValueError("Проверка масок относится к другому исходнику или диапазону.")
        overrides = {}
        for index, value in review.get("overrides", {}).items():
            override = (folder / value).resolve()
            if not override.is_relative_to(folder) or not override.is_file():
                raise ValueError("Файл исправленных масок недоступен.")
            overrides[index] = str(override)
        plan.update(mask_overrides=overrides, verified_absences=review.get("verified_absences", {}))
    save(folder / "panel-plan.json", plan)
    command=[PYTHON,str(PIPE/"clip"),"run","Монтаж T2x2","--job",str(folder),"--input",str(source),
             "--offset",str(offset),"--plan",str(folder/"panel-plan.json"),"--banners-only"]
    # Reuse the analysis transcript on the shared portion of both downloads.
    # If an operator selected beyond its actual speech context, the render worker
    # still recognizes the final AAC during QA.
    transcript=folder/"analysis-words.jsonl"
    analysis_offset=read(parent/"source.manifest.json")["offset"]
    shift=analysis_offset-offset
    adjusted=[]
    for line in (parent/"words.jsonl").read_text().splitlines():
        row=json.loads(line)
        if row["end"]+analysis_offset<request.start-12 or row["start"]+analysis_offset>request.end+12:
            continue
        row["words"]=[w for w in row["words"] if w["end"]+shift>0]
        for item in [row,*row["words"]]:
            item["start"]=max(0,item["start"]+shift)
            item["end"]=max(item["start"],item["end"]+shift)
        adjusted.append(json.dumps(row,ensure_ascii=False))
    if adjusted and audio_matches(parent/"source.mkv",request.start-analysis_offset,
                                  source,request.start-offset):
        transcript.write_text("\n".join(adjusted)+"\n")
        command.extend(["--review-transcript",str(transcript)])
    if request.preview:
        command.append("--with-preview")
    stage(parent, "Монтаж")
    execute(command, 14400, parent)
    run = read(folder / "run.json")
    stage(parent, "Готово" if run.get("status")=="ready" else "На проверке")


@app.middleware("http")
async def protect_writes(request, call_next):
    if request.method != "GET" and request.url.path != "/unlock" and request.headers.get("x-t2x2-panel") != "1":
        from fastapi.responses import JSONResponse
        return JSONResponse({"detail":"Открой панель для запуска задачи."}, status_code=403)
    return await gate(request,call_next)


@app.get("/")
def home():
    return FileResponse(ROOT / "index.html")


@app.get("/api/catalog")
def catalog(page: int = 1):
    if not 1 <= page <= 500:
        raise HTTPException(400, "Некорректная страница.")
    try:
        reply = execute(YTDLP + ["--flat-playlist", "--playlist-items", f"{(page-1)*20+1}:{page*20}",
                                  "-j", CHANNEL + "/videos"], 35)
    except (subprocess.SubprocessError, OSError):
        raise HTTPException(502, "Twitch не вернул архив. Попробуй обновить список.")
    rows = [json.loads(line) for line in reply.splitlines()]
    entries = [dict(id=str(row["id"]).lstrip("v"), title=row.get("title"),
                    duration=row.get("duration"), date=row.get("upload_date"),
                    category=(row.get("categories") or [None])[0]) for row in rows]
    if entries:
        try:
            dates = json.loads(execute([PYTHON, str(ROOT/"twitch_catalog.py"), *(row["id"] for row in entries)], 14))
            for row in entries:
                for field, value in dates.get(row["id"], {}).items():
                    if value:
                        row[field] = value
        except (subprocess.SubprocessError, OSError, ValueError):
            pass
    live = None
    if page == 1:
        try:
            result = subprocess.run(YTDLP + ["-j", CHANNEL], capture_output=True, text=True, timeout=18)
            if result.returncode == 0:
                row = json.loads(result.stdout)
                live = dict(id="live", title=row.get("description") or row.get("title"), category=(row.get("categories") or [None])[0]) if row.get("is_live") else False
            elif re.search(r"offline|not currently live|not live",result.stderr,re.I):
                live = False
        except (subprocess.SubprocessError, OSError, ValueError):
            pass
    return dict(entries=entries, live=live, next=page+1 if len(entries)==20 else None)


@app.get("/api/jobs")
def jobs():
    return [state_for(path.parent) for path in sorted(DATA.glob("*/job.json"), key=lambda p:p.stat().st_mtime, reverse=True)
            if visible_job(path)]


@app.post("/api/jobs")
def start(request: Analysis, x_t2x2_command: str | None = Header(None)):
    key = command_id(x_t2x2_command)
    folder = DATA / key
    with queue:
        if request.vod != "live" and not (folder/"job.json").exists():
            for candidate in DATA.glob("*/job.json"):
                if not visible_job(candidate):
                    continue
                old = read(candidate)
                if (old.get("vod"), old.get("requested_start"), old.get("requested_end")) == (
                    request.vod, request.start, request.end
                ):
                    return {"id": old["id"]}
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / "job.json"
        state = read(path) if path.exists() else dict(
            id=key, vod=request.vod, requested_start=request.start, requested_end=request.end
        )
        return enqueue(folder, state, "analysis", request.model_dump(), key)


@app.post("/api/jobs/{job}/retry")
def retry(job: str, x_t2x2_command: str | None = Header(None)):
    folder = job_path(job)
    key = command_id(x_t2x2_command)
    with queue:
        state = read(folder / "job.json")
        if key in state.get("accepted", {}):
            return {"id": job}
        previous = state.get("operation")
        plan = folder / "render-request.json"
        if previous:
            action, recipe = previous["action"], previous["recipe"]
        elif plan.exists():
            action, recipe = "render", read(plan)
        else:
            action, recipe = "analysis", Analysis(vod=state["vod"], start=state["requested_start"],
                                                   end=state["requested_end"]).model_dump()
        if previous and previous["status"] in {"queued","running"}:
            raise HTTPException(409, "Эта задача уже выполняется или стоит в очереди.")
        if action == "analysis" and state["vod"] == "live" and not (folder / "source.manifest.json").exists():
            for name in ("source.mkv", "source.partial.mkv"):
                source = folder / name
                if source.exists():
                    source.rename(folder / f"interrupted-{key}-{name}")
        return enqueue(folder, state, action, recipe, key)


@app.post("/api/jobs/{job}/render")
def start_render(job: str, request: Render, x_t2x2_command: str | None = Header(None)):
    folder = job_path(job)
    with queue:
        state = read(folder / "job.json")
        if not request.confirmed or not state.get("proposals") or not state["start"] <= request.start < request.end <= state["end"]:
            raise HTTPException(400, "Подтверди границы и сохранность донатов.")
        previous = state.get("render_selection")
        if previous and previous != request.model_dump():
            raise HTTPException(409, "Для других настроек создай отдельную задачу.")
        state["render_selection"] = request.model_dump()
        return enqueue(folder, state, "render", request.model_dump(), command_id(x_t2x2_command))


def visible_job(path):
    return path.parent.name not in TECHNICAL_JOBS and not read(path).get("duplicate_of")


class MetadataEdit(BaseModel):
    title: str = Field(min_length=1, max_length=100)
    description: str = Field(min_length=1, max_length=5000)
    chapters: list[tuple[int, str]] = Field(min_length=1, max_length=200)


@app.post("/api/jobs/{job}/metadata")
def edit_metadata(job: str, edit: MetadataEdit):
    folder = job_path(job)
    with queue:
        state = state_for(folder)
        if state.get("pipeline", {}).get("status") != "ready":
            raise HTTPException(409, "Метаданные можно править после проверки ролика.")
        times = [t for t, _ in edit.chapters]
        duration = state["qa"]["master"]["duration"]
        if times[0] != 0 or any(b-a < 10 for a,b in zip(times,times[1:])) or duration-times[-1] < 10:
            raise HTTPException(400, "Первая глава начинается с 0:00, каждая длится не менее 10 секунд.")
        if any(not label.strip() for _, label in edit.chapters):
            raise HTTPException(400, "Укажи название каждой главы.")
        save(folder/"metadata-edit.json", edit.model_dump())
        return {"id": job}


def state_for(folder):
    result = read(folder/"job.json")
    result.pop("accepted", None)
    operation = result.get("operation")
    if operation:
        result["operation"] = {k: operation[k] for k in ("action", "status", "queued_at")}
        if operation["action"] == "short":
            result["short_selection"] = operation["recipe"]
            report = folder / "montage" / f"short-{operation['id'][:12]}.result.json"
            if report.exists():
                result["current_short"] = read(report)
    for name,key in (("run.json","pipeline"),("qa.json","qa"),("metadata.json","metadata")):
        path=folder/"montage"/name
        if path.exists():
            data=read(path)
            if key=="pipeline":
                files=data.get("deliverables",{})
                result[key]=dict(stage=data.get("stage"),status=data.get("status"),
                                 files=[k for k in ("master","preview") if k in files],
                                 stage_since=datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat())
            elif key=="qa":
                result[key]={k:data.get(k) for k in ("failures","mask_frames","mask_missing_detections","mask_verified_absences","mask_review_ranges","speech","master")}
                if result[key]["mask_review_ranges"]:
                    result[key]["mask_review_ranges"] = result[key]["mask_review_ranges"][:50]
                sheets = []
                for raw in data.get("contact_sheets", [])[:2]:
                    sheet = Path(raw).resolve()
                    cache = (PIPE/"output"/"pipeline-cache").resolve()
                    if (sheet.is_relative_to(folder.resolve()) or sheet.is_relative_to(cache)) and sheet.suffix == ".jpg" and sheet.is_file() and sheet.stat().st_size <= 80_000:
                        sheets.append(base64.b64encode(sheet.read_bytes()).decode())
                if sheets:
                    result[key]["contact_images"] = sheets
            else:
                result[key]=data
    edit = folder/"metadata-edit.json"
    if edit.exists() and "metadata" in result:
        result["metadata"].update(read(edit))
    log=folder/"worker.log"
    if log.exists() and result.get("pipeline",{}).get("stage")=="PREPARE":
        with log.open("rb") as stream:
            stream.seek(max(0,log.stat().st_size-32768))
            for line in reversed(stream.read().splitlines()):
                if line.startswith(b'{"stage":"CHUNKS"'):
                    result["chunks"]=json.loads(line)
                    break
    result["shorts"]=[p.name for p in sorted((folder/"montage").glob("short-*.mp4"))
                      if re.fullmatch(r"short-[a-f0-9]{12}\.mp4",p.name)]
    result["short_results"] = [read(p) for p in sorted((folder/"montage").glob("short-*.result.json"))]
    edl = folder / "montage" / "edl.json"
    if edl.exists():
        result["censor_windows"] = len(read(edl).get("censorship", []))
    if gate.tunnel:
        result["separate_delivery"]=True
    return result


@app.get("/api/jobs/{job}/events")
async def events(job: str, request: Request):
    folder=job_path(job)
    async def stream():
        previous=None
        import time
        deadline=time.monotonic()+45 if gate.enabled else float("inf")
        while time.monotonic()<deadline and not await request.is_disconnected():
            value=json.dumps(state_for(folder),ensure_ascii=False)
            if value!=previous:
                yield f"data: {value}\n\n"
                previous=value
            else:
                yield ": heartbeat\n\n"
            await asyncio.sleep(2)
    return StreamingResponse(stream(),media_type="text/event-stream",headers={"Cache-Control":"no-cache"})


@app.get("/api/jobs/{job}/file/{kind}")
def file(job: str, kind: str, download: bool = False):
    folder=job_path(job)/"montage"
    if kind not in ("master","preview") or not (folder/"run.json").exists():
        raise HTTPException(404)
    run=read(folder/"run.json")
    if run.get("status")!="ready" or kind not in run.get("deliverables",{}):
        raise HTTPException(409,"Файл ещё не прошёл проверку.")
    target=Path(run["deliverables"][kind]).resolve()
    if not target.is_relative_to(folder) or not target.is_file():
        raise HTTPException(404)
    return FileResponse(target,media_type="video/mp4",filename=target.name if download else None)


@app.post("/api/jobs/{job}/short")
def make_short(job: str, selection: Short, x_t2x2_command: str | None = Header(None)):
    folder = job_path(job)
    with queue:
        file(job, "master")
        plan = read(folder / "montage" / "panel-plan.json")
        if not 5 <= selection.end-selection.start <= 59.9:
            raise HTTPException(400, "Выбери от 5 до 59,9 секунды готового видео.")
        if selection.start < 10 or selection.end > 10 + plan["keep"][0][1] - plan["keep"][0][0]:
            raise HTTPException(400, "Диапазон должен быть внутри готового эпизода после интро.")
        return enqueue(folder, read(folder/"job.json"), "short", selection.model_dump(),
                       command_id(x_t2x2_command))


def check_short(folder, path, selection):
    result = path.with_suffix(".qa.json")
    execute([PYTHON, str(ROOT/"short_qa.py"), str(path), str(folder/"montage"),
             str(selection.start), str(selection.end), str(result)], 420, folder)
    return read(result)


def render_short(folder, selection, key):
    master = file(folder.name, "master")
    output = folder / "montage"
    stage(folder, "Рендер Shorts", error=None)
    dest = output / f"short-{key[:12]}.mp4"
    tmp = dest.with_suffix(".partial.mp4")
    report = dest.with_suffix(".result.json")
    if report.exists():
        stage(folder, "Готово" if read(report)["status"] == "ready" else "На проверке")
        return
    execute(["ffmpeg","-nostdin","-y","-v","error","-ss",str(selection.start),"-i",str(master.path),
             "-t",str(selection.end-selection.start),"-vf","scale=1080:-2,pad=1080:1920:0:(oh-ih)/2",
             "-c:v","libx264","-preset","veryfast","-crf","19","-c:a","aac","-b:a","192k",
             "-movflags","+faststart",str(tmp)],240,folder)
    stage(folder, "Проверка Shorts")
    result = check_short(folder, tmp, selection)
    if result["status"] == "ready":
        tmp.replace(dest)
    save(report, dict(result, name=dest.name, start=selection.start, end=selection.end))
    stage(folder, "Готово" if result["status"] == "ready" else "На проверке")


@app.get("/api/jobs/{job}/short/{name}")
def short_file(job: str,name: str):
    if not re.fullmatch(r"short-[a-f0-9]{12}\.mp4",name):
        raise HTTPException(404)
    folder=job_path(job)/"montage"
    target=folder/name
    result = target.with_suffix(".result.json")
    if not result.is_file() or read(result).get("status") != "ready":
        raise HTTPException(409, "Shorts ещё не прошёл проверку.")
    if not target.is_file() or target.resolve().parent!=folder.resolve():
        raise HTTPException(404)
    return FileResponse(target,media_type="video/mp4",filename=name)
