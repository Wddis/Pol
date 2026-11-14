"""Telegram bot «Двойная резка» — the whole pipeline of this branch by commands, without an LLM.
The owner approves every step (events, preview, cover, publish). Scripts do the work (same tools as by hand).

Run:   TG_BOT_TOKEN=... python3 tools/tgbot/bot.py            (long polling, no webhook/ports needed)
Env:   TG_BOT_TOKEN  — token from @BotFather (secret; set it in the host's env, never commit)
       TG_OWNER_ID   — your Telegram numeric id (optional: otherwise pair once with /pair <code from the log>)
       T2X2_WORK     — work dir (default ~/t2x2work); CHANNEL_ID (default UC4R6TINYFRWa_80Vewy068g)
       SHORTS_FONTS  — fonts dir for shorts/intro (montserrat 800/900 + russo-one woff)
Docs:  tools/tgbot/README.md (commands, install). Rules the bot follows: docs/montage-style.md, docs/no-yellow-card.md,
       docs/timecodes.md (the bot never blurs the webcam, no zoom, intro «Ретро-ТВ» on main videos, promo boxes in chat)."""
import os, sys, json, time, re, shutil, secrets, subprocess, threading, traceback, html
import requests

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
T = os.path.join(REPO, "tools")
TOKEN = os.environ.get("TG_BOT_TOKEN") or sys.exit("TG_BOT_TOKEN is not set")
API = f"https://api.telegram.org/bot{TOKEN}"
WORK = os.path.expanduser(os.environ.get("T2X2_WORK", "~/t2x2work")); os.makedirs(WORK, exist_ok=True)
CHANNEL = os.environ.get("CHANNEL_ID", "UC4R6TINYFRWa_80Vewy068g")
STATE_F = os.path.join(WORK, "bot_state.json")
TG_LIMIT = 48 * 1024 * 1024                         # bot API upload limit is 50 MB

def log(*a): print(time.strftime("%H:%M:%S"), *a, flush=True)
LOGD = os.path.join(WORK, "logs"); os.makedirs(LOGD, exist_ok=True)
_loglock = threading.Lock()
def blog(kind, **data):
    """structured log, one JSON per line: WORK/logs/YYYY-MM-DD.jsonl — read by Claude to improve the bot (/logs sends it)"""
    rec = {"t": time.strftime("%Y-%m-%dT%H:%M:%S"), "kind": kind, **data}
    line = json.dumps(rec, ensure_ascii=False, default=str).replace(TOKEN, "<token>")
    with _loglock, open(os.path.join(LOGD, time.strftime("%Y-%m-%d") + ".jsonl"), "a") as f: f.write(line + "\n")
def load_state():
    try: return json.load(open(STATE_F))
    except Exception: return {}
S = load_state()
def save(): json.dump(S, open(STATE_F, "w"), ensure_ascii=False, indent=1)
if os.environ.get("TG_OWNER_ID"): S["owner"] = int(os.environ["TG_OWNER_ID"])
if not S.get("owner"):
    S.setdefault("pair_code", secrets.token_hex(3)); save()
    log(f"NOT PAIRED. Send the bot:  /pair {S['pair_code']}")

# ----------------------------------------------------------------- telegram helpers
def tg(method, **kw):
    files = kw.pop("files", None)
    for k, v in list(kw.items()):
        if isinstance(v, (dict, list)): kw[k] = json.dumps(v, ensure_ascii=False)
    for i in range(4):
        try:
            r = requests.post(f"{API}/{method}", data=kw, files=files, timeout=600 if files else 70).json()
            if not r.get("ok"): log("tg error", method, r.get("description"))
            return r
        except Exception as e:
            log("tg net", e); time.sleep(2 * (i + 1))
    return {}
def say(text, kb=None):
    if not S.get("owner"): return
    blog("out", text=re.sub(r"<[^>]+>", "", text)[:1500], buttons=[b["text"] for row in (kb or []) for b in row])
    for chunk in [text[i:i + 3900] for i in range(0, len(text), 3900)] or [""]:
        tg("sendMessage", chat_id=S["owner"], text=chunk, parse_mode="HTML", disable_web_page_preview=True,
           **({"reply_markup": {"inline_keyboard": kb}} if kb else {}))
def send_file(path, caption="", kind="document"):
    if not os.path.exists(path): return say(f"нет файла {html.escape(path)}")
    if os.path.getsize(path) > TG_LIMIT: return say(f"файл {os.path.basename(path)} больше 50 МБ — лежит на сервере: <code>{html.escape(path)}</code>")
    with open(path, "rb") as f:
        tg({"video": "sendVideo", "photo": "sendPhoto"}.get(kind, "sendDocument"), chat_id=S["owner"], caption=caption[:1000],
           files={kind: (os.path.basename(path), f)})
def esc(s): return html.escape(str(s))

def run(cmd, cwd=None, env=None, timeout=None, tail=15):
    """run a step; returns (ok, last lines)"""
    e = dict(os.environ, **(env or {})); c = cmd if isinstance(cmd, str) else " ".join(cmd); t0 = time.time()
    log("RUN", c)
    try:
        p = subprocess.run(cmd, cwd=cwd, env=e, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        blog("run", cmd=c[:400], rc="timeout", sec=round(time.time() - t0, 1)); return False, f"timeout after {timeout}s"
    out = (p.stdout + p.stderr).strip().splitlines()
    blog("run", cmd=c[:400], rc=p.returncode, sec=round(time.time() - t0, 1), tail=out[-(tail if p.returncode else 4):])
    return p.returncode == 0, "\n".join(out[-tail:])

def bg(name, fn, *a):
    """one heavy job at a time; reports errors to the owner"""
    if S.get("busy") and S["busy"] != name and THREADS.get(S["busy"]) and THREADS[S["busy"]].is_alive():
        return say(f"⏳ Уже идёт: <b>{esc(S['busy'])}</b>. Дождись или /status.")
    def wrap():
        S["busy"] = name; save()
        try: fn(*a)
        except Exception as ex:
            log(traceback.format_exc()); blog("error", job=name, err=str(ex)[:2000], tb=traceback.format_exc()[-3000:])
            say(f"❌ {esc(name)}: <code>{esc(str(ex)[:800])}</code>")
        finally: S["busy"] = ""; save()
    th = threading.Thread(target=wrap, daemon=True); THREADS[name] = th; th.start()
THREADS = {}

# ----------------------------------------------------------------- paths / jobs
def vdir(vod=None):
    vod = vod or S.get("vod"); d = os.path.join(WORK, f"vod{vod}"); os.makedirs(d, exist_ok=True); return d
def jdir(j): d = os.path.join(WORK, "jobs", j); os.makedirs(d, exist_ok=True); return d
def jload(j):
    try: return json.load(open(os.path.join(jdir(j), "job.json")))
    except Exception: return None
def jsave(j, d):
    old = jload(j) if os.path.exists(os.path.join(WORK, "jobs", j, "job.json")) else {}
    if (old or {}).get("stage") != d.get("stage"): blog("stage", job=j, stage=d.get("stage"), frm=d.get("from"), to=d.get("to"))
    json.dump(d, open(os.path.join(jdir(j), "job.json"), "w"), ensure_ascii=False, indent=1)
def parse_t(s):
    """'94' -> 94 min, '94:30' -> 94.5 min, '1:34:30' -> h:m:s ; returns seconds"""
    p = [float(x) for x in s.split(":")]
    return p[0] * 60 if len(p) == 1 else (p[0] * 60 + p[1] if len(p) == 2 else p[0] * 3600 + p[1] * 60 + p[2])
def fmt(t): t = int(t); return f"{t // 3600}:{t % 3600 // 60:02d}:{t % 60:02d}" if t >= 3600 else f"{t // 60}:{t % 60:02d}"
def disk(): u = shutil.disk_usage(WORK); return f"{u.free / 2**30:.1f} ГБ свободно"

# ----------------------------------------------------------------- panel (Studio with the owner's verified cookies)
def panel_up():
    try: return requests.get("http://127.0.0.1:9222/json", timeout=4).ok
    except Exception: return False
def panel_start():
    ck = os.path.join(WORK, "cookies.txt")
    if not os.path.exists(ck): return say("Нет cookies. Пришли файл cookies.txt (подтверждённая сессия) после /cookies.")
    if panel_up(): return True
    tok = S.setdefault("rb_token", secrets.token_urlsafe(18)); save()
    subprocess.Popen(["node", os.path.join(T, "yt", "remote_browser.js"), ck, "8765", "9222",
                      f"https://studio.youtube.com/channel/{CHANNEL}/videos/upload?d=ud"],
                     env=dict(os.environ, RB_TOKEN=tok), stdout=open(os.path.join(WORK, "panel.log"), "a"),
                     stderr=subprocess.STDOUT, start_new_session=True)
    for _ in range(40):
        time.sleep(1.5)
        if panel_up(): break
    time.sleep(8)
    return panel_up()
def studio_url():
    try: return [p["url"] for p in requests.get("http://127.0.0.1:9222/json", timeout=4).json() if p.get("type") == "page"][0]
    except Exception: return ""

# ----------------------------------------------------------------- commands
HELP = """<b>Двойная резка — бот</b> 🧪 бета (основной способ пока — нарезка в сессии Claude)
/start — меню: 🔴 Прямой эфир · 📼 Прошедший стрим · /live · /past · /logs [дней] — логи
<b>Стрим</b>
/vod &lt;id или канал&gt; — выбрать VOD (канал → последний VOD)
/watch — следить за идущим стримом (расшифровка+видео каждые 8 мин)
/transcribe — расшифровать весь VOD (если стрим закончен)
/map [от-до мин] — темы стрима (кандидаты в нарезки) с кнопками
<b>Основное видео</b>
/cut №[-№] или /cut 94-110 [мин] — нарезать: план → проверка моментов (/drop /add) → /go рендер (интро, блюр, тайм-коды, превью)
/jobs — нарезки · /job &lt;id&gt; — выбрать
/title &lt;текст&gt; · /chapters · /chapter &lt;№&gt; &lt;название&gt; · /desc
фото с подписью или без — обложка текущей нарезки
/publish — опубликовать текущую (1080p60)
<b>Шортсы</b>
/short 94:10-94:55 СТРОКА 1|СТРОКА 2 [talk] — собрать шортс (дизайн v2 clean)
/shorts — полка · /pubshorts [10] — опубликовать полку с интервалом N мин
<b>Сервис</b>
/cookies — прислать cookies.txt · /panel — поднять Studio · /limits — ограничения (18+) в Studio
/status · /stop — остановить слежение"""

def cmd_start(m, a): say(HELP)
def cmd_status(m, a):
    j = S.get("job"); jd = jload(j) if j else None
    say(f"VOD: <b>{esc(S.get('vod', '—'))}</b> · слежение: {'да' if S.get('watch_pid') and pid_alive(S['watch_pid']) else 'нет'}\n"
        f"Занят: {esc(S.get('busy') or 'нет')} · нарезка: {esc(j or '—')} ({esc(jd.get('stage') if jd else '')})\n"
        f"Studio: {'работает' if panel_up() else 'выключена'} · диск: {disk()}")
def pid_alive(p):
    try: os.kill(p, 0); return True
    except Exception: return False

def cmd_vod(m, a):
    if not a: return say("Формат: /vod 2926718724  или  /vod t2x2")
    v = a.strip().split()[0]
    if not v.isdigit():
        ok, out = run(["yt-dlp", "--no-warnings", "--flat-playlist", "-I", "1", "--print", "id", f"https://www.twitch.tv/{v}/videos?filter=archives&sort=time"], timeout=120)
        v = out.strip().splitlines()[-1].lstrip("v") if ok and out.strip() else ""
        if not v.isdigit(): return say("Не нашёл VOD канала.")
    S["vod"] = v; S["vod_len"] = 0; save(); vdir(v); say(f"VOD <b>{v}</b> выбран. Дальше: /watch (идёт стрим) или /transcribe (закончен).")

def cmd_watch(m, a):
    if not S.get("vod"): return say("Сначала /vod")
    d = vdir(); tr = os.path.join(d, "transcript_live.txt"); open(tr, "a").close()
    if S.get("watch_pid") and pid_alive(S["watch_pid"]): return say("Слежение уже идёт.")
    p = subprocess.Popen(["bash", os.path.join(T, "live_update.sh"), S["vod"], d], stdout=open(os.path.join(d, "live.log"), "a"),
                         stderr=subprocess.STDOUT, start_new_session=True)
    S["watch_pid"] = p.pid; save(); say("👀 Слежу за стримом: каждые 8 мин дописываю расшифровку и качаю новое видео. /map — темы.")
    threading.Thread(target=watch_reporter, args=(d,), daemon=True).start()
def watch_reporter(d):
    lf = os.path.join(d, "live.log"); pos = os.path.getsize(lf) if os.path.exists(lf) else 0
    while S.get("watch_pid") and pid_alive(S["watch_pid"]):
        time.sleep(60)
        if not os.path.exists(lf): continue
        with open(lf) as f: f.seek(pos); new = f.read(); pos = f.tell()
        for l in new.splitlines():
            if "no new audio" in l: say("📴 Похоже, стрим закончился (VOD не растёт). /map — темы, /stop — выключить слежение.")
            elif l.startswith("VOD"): S["live_last"] = l; save()
def cmd_stop(m, a):
    S["live_on"] = False
    p = S.get("watch_pid")
    if p and pid_alive(p): os.killpg(p, 15)
    S["watch_pid"] = 0; save(); say("Слежение остановлено.")

def cmd_transcribe(m, a):
    if not S.get("vod"): return say("Сначала /vod")
    bg("расшифровка", cmd_transcribe_job)
def cmd_transcribe_job():
    if True:
        d = vdir(); say("🎧 Качаю звук VOD…")
        ok, out = run(["python3", os.path.join(T, "vod.py"), S["vod"], "Audio_Only", "audio.mp4"], cwd=d, timeout=3600)
        if not ok: return say(f"❌ звук: <code>{esc(out)}</code>")
        run("ffmpeg -loglevel error -y -i audio.mp4 -ac 1 -ar 16000 audio.wav", cwd=d)
        dur = float(run("ffprobe -v error -show_entries format=duration -of csv=p=0 audio.wav", cwd=d)[1] or 0)
        say(f"📝 Расшифровка {fmt(dur)} (≈{int(dur / 27 / 60) + 1} мин)…")
        ok, out = run(["python3", os.path.join(T, "stt.py"), "audio.wav", "transcript_live.txt"], cwd=d, timeout=8 * 3600)
        say("✅ Расшифровка готова." if ok else f"❌ <code>{esc(out)}</code>")

def cmd_map(m, a):
    tr = os.path.join(vdir(), "transcript_live.txt")
    if not os.path.exists(tr) or os.path.getsize(tr) < 100: return say("Нет расшифровки: /watch или /transcribe")
    args = ["python3", os.path.join(T, "auto_events.py"), tr, "--json", os.path.join(vdir(), "events.json")]
    if a and re.match(r"^\d+(:\d+)?-\d+(:\d+)?$", a.strip()):
        f, t = a.strip().split("-"); args += ["--from", str(parse_t(f)), "--to", str(parse_t(t))]
    ok, out = run(args, tail=200)
    ev = json.load(open(os.path.join(vdir(), "events.json")))
    lines = [f"<b>#{i}</b> {fmt(e['start'])}–{fmt(e['end'])} ({e['dur_min']} мин) ⭐{e['score']}\n<i>{esc(', '.join(e['keywords'][:5]))}</i>\n«{esc(e['first'][:160])}»"
             for i, e in enumerate(ev, 1)]
    say("🗺 <b>Темы стрима</b> (кандидаты; соседние можно склеить: /cut 5-9)\n\n" + "\n\n".join(lines))
    best = sorted(range(len(ev)), key=lambda i: -ev[i]["score"])[:6]
    say("Быстрые кнопки (лучшие по оценке):", [[{"text": f"✂️ #{i + 1} {fmt(ev[i]['start'])} ({ev[i]['dur_min']}м)", "callback_data": f"cut:{i + 1}"}] for i in best])

def resolve_range(a):
    a = a.strip()
    evf = os.path.join(vdir(), "events.json")
    m1 = re.match(r"^#?(\d+)(?:-#?(\d+))?$", a)
    if m1 and os.path.exists(evf) and int(m1[1]) <= 200:
        ev = json.load(open(evf)); i, k = int(m1[1]), int(m1[2] or m1[1])
        if 1 <= i <= k <= len(ev): return ev[i - 1]["start"], ev[k - 1]["end"]
    m2 = re.match(r"^([\d:]+)-([\d:]+)$", a)
    if m2: return parse_t(m2[1]), parse_t(m2[2])
    return None

def cut_do(m, a):
    if not S.get("vod"): return say("Сначала /vod")
    parts = (a or "").split(); r = resolve_range(parts[0]) if parts else None
    tgt = float(parts[1]) if len(parts) > 1 and parts[1].replace(".", "").isdigit() else 0
    if not r: return say("Формат: /cut 12  ·  /cut 5-9 (склеить темы)  ·  /cut 94-110 (минуты)  ·  /cut 1:34:00-1:50:30\n"
                         "Длинная игра → лучшие моменты: /cut 168-384 25  (25 = длина ролика в минутах)")
    if not tgt and r[1] - r[0] > 45 * 60: tgt = 25
    j = time.strftime("%m%d_%H%M%S"); S["job"] = j; save()
    jsave(j, {"vod": S["vod"], "from": r[0], "to": r[1], "stage": "queued", "kind": "main", "target": tgt})
    bg(f"нарезка {j}", main_job, j)
def main_job(j):
    jd = jload(j); d = jdir(j); vd = vdir(jd["vod"]); a, b = jd["from"], jd["to"]
    say(f"✂️ Нарезка <b>{j}</b>: {fmt(a)}–{fmt(b)} ({(b - a) / 60:.1f} мин)\n1/6 видео 1080p60…")
    segs = os.path.join(vd, "video.mp4.segs")
    ok, out = run(["python3", os.path.join(T, "vod.py"), jd["vod"], "1080p60", os.path.join(vd, "video.mp4"), str(a - 20), str(b + 20)],
                  env={"SEGS_ONLY": "1"}, timeout=7200)
    if not ok: raise RuntimeError("скачивание: " + out)
    ok, out = run(["python3", os.path.join(T, "vodcut.py"), jd["vod"], "1080p60", segs, str(a - 3), str(b + 3), "ev.mp4"], cwd=d, timeout=3600)
    if not ok: raise RuntimeError("vodcut: " + out)
    off = float(re.findall(r"offset ([\d.]+)", out)[-1]); jd["offset"] = off
    say("2/6 план монтажа по правилам (тишина/пустые донаты/гудение — вон; начало и конец события целиком)…")
    ok, out = run(["python3", os.path.join(T, "auto_plan.py"), os.path.join(vd, "transcript_live.txt"), str(a), str(b), str(off), "plan.json"]
                  + (["--target", str(jd["target"])] if jd.get("target") else []), cwd=d)
    if not ok: raise RuntimeError("план: " + out)
    shutil.copy(os.path.join(d, "plan.json"), os.path.join(d, "plan_auto.json"))   # for learning from the owner's edits
    jd["stage"] = "review"; jsave(j, jd); show_moments(j)

def show_moments(j):
    d = jdir(j); plan = json.load(open(os.path.join(d, "plan.json"))); jd = jload(j)
    kept = sum(c["e"] - c["s"] for c in plan["clips"]) / 60
    lines = [f"<b>{i}.</b> {fmt(mo['from'])}–{fmt(mo['to'])} {'⚠️ ' + esc('; '.join(mo['risk'])) if mo['risk'] else ''}\n«{esc(mo['text'][:170])}»"
             for i, mo in enumerate(plan.get("moments", []), 1)]
    say(f"🧾 <b>План {j}</b>: {kept:.1f} мин, моментов {len(lines)} (время — по стриму)\n\n" + "\n\n".join(lines) +
        "\n\n/drop № — убрать момент · /add 1:34:10-1:36:00 — добавить кусок стрима · /go — рендер",
        [[{"text": "▶️ Рендер", "callback_data": f"go:{j}"}]])

def plan_edit(j, drop=None, add=None):
    blog("edit_plan", job=j, drop=drop, add=add)
    d = jdir(j); jd = jload(j); pf = os.path.join(d, "plan.json"); plan = json.load(open(pf)); off = jd["offset"]
    if drop is not None:
        mo = plan["moments"].pop(drop)
        plan["clips"] = [c for c in plan["clips"] if not (mo["from"] - 1 <= c["s"] + off and c["e"] + off <= mo["to"] + 1)]
    if add:
        a, b = add
        if not (jd["from"] - 1 <= a < b <= jd["to"] + 1): return say(f"Кусок вне нарезки ({fmt(jd['from'])}–{fmt(jd['to'])})")
        plan["clips"].append({"s": round(a - off, 2), "e": round(b - off, 2), "zoom": [], "blur": list(plan["blur"])})
        plan["clips"].sort(key=lambda c: c["s"]); merged = []
        for c in plan["clips"]:
            if merged and c["s"] <= merged[-1]["e"] + 0.5: merged[-1]["e"] = max(merged[-1]["e"], c["e"])
            else: merged.append(c)
        plan["clips"] = merged
        plan["moments"].append({"from": a, "to": b, "text": "(добавлено вручную)", "risk": []}); plan["moments"].sort(key=lambda m: m["from"])
    json.dump(plan, open(pf, "w"), ensure_ascii=False, indent=1); show_moments(j)
def cmd_drop(m, a):
    j, jd = cur_job()
    if not jd or jd.get("stage") != "review" or not (a or "").isdigit(): return say("Формат: /drop 3 (в режиме проверки плана)")
    plan_edit(j, drop=int(a) - 1)
def cmd_add(m, a):
    j, jd = cur_job(); mm = re.match(r"^([\d:]+)-([\d:]+)$", a or "")
    if not jd or jd.get("stage") != "review" or not mm: return say("Формат: /add 1:34:10-1:36:00 (время стрима)")
    plan_edit(j, add=(parse_t(mm[1]), parse_t(mm[2])))
def cmd_go(m, a):
    j, jd = cur_job()
    if a and jload(a): j, jd = a, jload(a); S["job"] = j; save()
    if not jd or jd.get("stage") != "review": return say("Нечего рендерить (нужна нарезка в режиме проверки плана)")
    bg(f"рендер {j}", render_job, j)

def render_job(j):
    jd = jload(j); d = jdir(j)
    say("3/6 ищу промокоды FONBET в чате (блюр только окошка сообщения)…")
    ok, out = run(["python3", os.path.join(T, "chat_promo_scan.py"), "ev.mp4", "--out", "promo.json"], cwd=d, timeout=4 * 3600)
    plan = json.load(open(os.path.join(d, "plan.json")))
    promo = json.load(open(os.path.join(d, "promo.json"))) if ok else []
    for c in plan["clips"]: c["blur"] = [x for x in c["blur"] if len(x) == 4] + [p for p in promo if p[5] > c["s"] and p[4] < c["e"]]
    json.dump(plan, open(os.path.join(d, "plan.json"), "w"), ensure_ascii=False)
    say(f"   промо-сообщений в чате: {len(promo)}\n4/6 рендер 1080p60 (без зума, вебка не блюрится)…")
    ok, out = run(["python3", os.path.join(T, "edit2.py"), "ev.mp4", "plan.json", "out.mp4", "2"], cwd=d, timeout=6 * 3600)
    if not ok: raise RuntimeError("рендер: " + out)
    say("5/6 интро «Ретро-ТВ» + тайм-коды…")
    ok, out = run(["bash", os.path.join(T, "add_intro.sh"), "out.mp4", "final.mp4"], cwd=d, timeout=3600)
    if not ok: raise RuntimeError("интро: " + out)
    jd["chapters"] = [c for c in plan["chapters"] if any(cl["s"] - 0.6 <= c["src"] <= cl["e"] for cl in plan["clips"])] or \
                     [{"src": plan["clips"][0]["s"] + 0.5, "title": "Начало"}]
    jd["stage"] = "preview"; jsave(j, jd)
    say("6/6 превью…"); make_preview(d, "final.mp4", "preview.mp4")
    send_file(os.path.join(d, "preview.mp4"), f"Превью {j}", "video")
    refs(d, jd)
    say(f"✅ Готово: <b>{j}</b>. Дальше:\n• /title НАЗВАНИЕ (формат «КАПС-ХУК - суть / РЕАКЦИЯ T2x2»)\n• /chapters — проверить тайм-коды, /chapter № название — поправить\n"
        f"• пришли обложку (фото)\n• /publish — опубликовать\n\n<b>Тайм-коды (черновик):</b>\n<pre>{esc(timecodes(j))}</pre>")
def make_preview(d, src, out):
    dur = float(run(f"ffprobe -v error -show_entries format=duration -of csv=p=0 {src}", cwd=d)[1] or 60)
    vb = max(150, int(TG_LIMIT * 8 / dur / 1000) - 80)                     # fit the Telegram 50 MB limit
    sc = "640:360" if vb >= 400 else "480:270"
    run(f"ffmpeg -loglevel error -y -i {src} -vf scale={sc},fps=30 -c:v libx264 -preset veryfast -b:v {vb}k -maxrate {vb}k -bufsize {2 * vb}k "
        f"-c:a aac -b:a 64k -ac 1 {out}", cwd=d, timeout=3600)
def refs(d, jd):
    """cover references: big webcam faces + screen frames (owner makes the cover himself)"""
    dur = float(run("ffprobe -v error -show_entries format=duration -of csv=p=0 out.mp4", cwd=d)[1] or 60)
    os.makedirs(os.path.join(d, "refs"), exist_ok=True)
    for i in range(6):
        t = dur * (i + 0.5) / 6
        run(f"ffmpeg -loglevel error -y -ss {t:.1f} -i out.mp4 -frames:v 1 -q:v 3 refs/frame_{i}.jpg", cwd=d)
    run("rm -f refs.zip && cd refs && zip -q ../refs.zip *.jpg", cwd=d)
    send_file(os.path.join(d, "refs.zip"), "Референсы для обложки (кадры). Обложку пришли фото в чат.")

def timecodes(j):
    d = jdir(j); jd = jload(j)
    json.dump({"plan": "plan.json", "chapters": jd.get("chapters", [])}, open(os.path.join(d, "chapters_cfg.json"), "w"), ensure_ascii=False)
    ok, out = run(["python3", os.path.join(T, "timecodes.py"), "chapters_cfg.json", "--intro", "10.0"], cwd=d, tail=60)
    return out
def cur_job():
    j = S.get("job"); return (j, jload(j)) if j and jload(j) else (None, None)
def cmd_title(m, a):
    j, jd = cur_job()
    if not jd: return say("Нет текущей нарезки (/jobs)")
    if not a: return say(f"Название: <b>{esc(jd.get('title', '—'))}</b>\nФормат: /title КАПС-ХУК - суть / РЕАКЦИЯ T2x2 (≤100)")
    if len(a) > 100: return say(f"Длина {len(a)} > 100")
    blog("edit_title", job=j, title=a.strip()); jd["title"] = a.strip(); jsave(j, jd); say(f"✅ Название: <b>{esc(a)}</b>")
    if S.get("publish_after_title") == j and jd.get("cover") and jd.get("stage") == "preview":
        S["publish_after_title"] = ""; save(); cmd_publish(m, "")
def cmd_chapters(m, a):
    j, jd = cur_job()
    if not jd: return say("Нет текущей нарезки")
    lst = "\n".join(f"{i}. {esc(c['title'])}" for i, c in enumerate(jd.get("chapters", []), 1))
    say(f"<b>Главы</b> (поправить: /chapter № Новое название · удалить: /chapter № -):\n{lst}\n\n<pre>{esc(timecodes(j))}</pre>")
def cmd_chapter(m, a):
    j, jd = cur_job(); mm = re.match(r"^(\d+)\s+(.+)$", a or "")
    if not jd or not mm: return say("Формат: /chapter 3 Письмо сына из интерната ( 25 копеек )")
    i = int(mm[1]) - 1; ch = jd.get("chapters", [])
    if not 0 <= i < len(ch): return say("Нет такой главы")
    blog("edit_chapter", job=j, n=i + 1, auto=ch[i]["title"], owner=mm[2].strip())
    if mm[2].strip() == "-": ch.pop(i)
    else: ch[i]["title"] = mm[2].strip()
    jd["chapters"] = ch; jsave(j, jd); cmd_chapters(m, "")
def describe(j, jd):
    vod = jd["vod"]; date = time.strftime("%d.%m.%y")
    tc = timecodes(j).split("\n(видео")[0]
    title = jd.get("title", "")
    return (f"{title}\n\n🎬 Оригинал / игра - {jd.get('source', 'стрим T2x2')}\n💜 Твич T2x2 - https://www.twitch.tv/t2x2\n"
            f"✈️ Телеграм - t.me/t2xtwitch\n\n{tc}\n\nЗапись со стрима {date}\n\n"
            f"T2x2 (Т2х2, Антон) — нарезка и лучшие моменты стрима: реакция, общение с чатом.\n\n#t2x2 #т2х2 #нарезка #реакция")
def cmd_desc(m, a):
    j, jd = cur_job()
    if not jd: return say("Нет текущей нарезки")
    if a and a.startswith("source "): jd["source"] = a[7:].strip(); jsave(j, jd)
    say(f"<pre>{esc(describe(j, jd))}</pre>\n(ссылка на оригинал: /desc source https://… )")
def on_photo(m):
    j, jd = cur_job()
    if not jd: return say("Обложку некуда прикрепить: нет текущей нарезки (/jobs)")
    fid = m["photo"][-1]["file_id"]; path = os.path.join(jdir(j), "cover.jpg")
    if download(fid, path):
        jd["cover"] = path; jsave(j, jd); blog("cover", job=j)
        if jd.get("stage") not in ("preview", "published"):
            return say(f"🖼 Обложка для {j} сохранена. Видео ещё не готово (стадия: {esc(jd.get('stage'))}) — предложу публикацию, когда будет превью.")
        if not jd.get("title"):
            S["publish_after_title"] = j; save()
            return say(f"🖼 Обложка сохранена. Осталось название: /title КАПС-ХУК - суть / РЕАКЦИЯ T2x2 — и сразу предложу опубликовать.")
        say("🖼 Обложка сохранена."); cmd_publish(m, "")                     # → confirmation ✅/✖️ with all checks
def download(fid, path):
    r = tg("getFile", file_id=fid)
    if not r.get("ok"): return False
    data = requests.get(f"https://api.telegram.org/file/bot{TOKEN}/{r['result']['file_path']}", timeout=300).content
    open(path, "wb").write(data); return True

def publish(meta_path, label):
    if not panel_start(): return None, "Studio не поднялась (cookies?)"
    ok, out = run(["node", os.path.join(T, "yt", "studio_upload.js"), "cdp:9222", meta_path], cwd=REPO, timeout=3600, tail=30)
    link = re.findall(r"VIDEO_LINK (\S+)", out)
    if link: return link[-1], ""
    if "UPLOAD_LIMIT" in out: return None, "LIMIT"
    return None, out[-600:]
def publish_do(m, a):
    j, jd = cur_job()
    if not jd: return say("Нет текущей нарезки")
    if not jd.get("title"): return say("Сначала /title")
    if not jd.get("cover") and a.strip() != "без обложки": return say("Нет обложки: пришли фото (или /publish без обложки)")
    d = jdir(j)
    meta = {"video": os.path.join(d, "final.mp4"), "thumb": jd.get("cover", ""), "title": jd["title"], "description": describe(j, jd),
            "tags": ["t2x2", "т2х2", "лучшее с т2х2", "лучшее с t2x2", "реакция", "юмор", "нарезка", "антон т2х2"] + jd.get("tags", []),
            "visibility": "PUBLIC", "channel": CHANNEL, "shot_dir": os.path.join(d, "up")}
    json.dump(meta, open(os.path.join(d, "studio.json"), "w"), ensure_ascii=False, indent=1)
    def job():
        say(f"📤 Публикую <b>{esc(jd['title'])}</b>…")
        link, err = publish(os.path.join(d, "studio.json"), j)
        if link: jd["link"] = link; jd["stage"] = "published"; jsave(j, jd); S["last_main_link"] = link; save(); say(f"✅ Опубликовано: {link}\nЧерез ~30 мин проверю ограничения: /limits")
        elif err == "LIMIT": say("⛔ Лимит загрузок YouTube на сутки. Повтори через 24 ч (не долбить — попытки тоже считаются).")
        else: say(f"❌ Публикация: <code>{esc(err)}</code>"); send_file(os.path.join(d, "up", "error.png"), "скрин ошибки", "photo")
    bg("публикация", job)

def cmd_jobs(m, a):
    root = os.path.join(WORK, "jobs"); out = []
    for j in sorted(os.listdir(root))[-15:] if os.path.isdir(root) else []:
        jd = jload(j) or {}
        out.append(f"<b>{j}</b> {jd.get('kind', '')} {fmt(jd.get('from', 0))}–{fmt(jd.get('to', 0))} · {esc(jd.get('stage', ''))} · {esc(jd.get('title', '')[:50])} {jd.get('link', '')}")
    say("\n".join(out) or "Пока нет нарезок")
def cmd_job(m, a):
    if a and jload(a.strip()): S["job"] = a.strip(); save(); say(f"Текущая нарезка: {a.strip()}")
    else: say("Формат: /job 1114_153012 (см. /jobs)")

# ----------------------------------------------------------------- shorts
def cmd_short(m, a):
    mm = re.match(r"^([\d:]+)-([\d:]+)\s+(.+?)(\s+talk)?$", a or "")
    if not mm or "|" not in mm[3]: return say("Формат: /short 94:10-94:55 СТРОКА 1|СТРОКА 2  (добавь talk, если вебка на весь экран)")
    a0, b0 = parse_t(mm[1]), parse_t(mm[2])
    if not 5 <= b0 - a0 <= 60: return say("Шортс: 5–60 секунд")
    j = "s" + time.strftime("%m%d_%H%M%S")
    jsave(j, {"vod": S["vod"], "from": a0, "to": b0, "kind": "short", "lines": mm[3], "talk": bool(mm[4]), "stage": "queued"})
    bg(f"шортс {j}", short_job, j)
def short_job(j):
    jd = jload(j); d = jdir(j); vd = vdir(jd["vod"])
    ok, out = run(["python3", os.path.join(T, "vod.py"), jd["vod"], "1080p60", "src.mp4", str(jd["from"] - 15), str(jd["to"] + 15)], cwd=d, timeout=1800)
    if not ok: raise RuntimeError(out)
    off = float(re.findall(r"offset ([\d.]+)", out)[-1])
    args = ["python3", os.path.join(T, "shorts_template2.py"), "--assets", "clean", jd["lines"], "p"] + (["--talk"] if jd["talk"] else [])
    ok, out = run(args, cwd=d)
    if not ok: raise RuntimeError(out)
    env = {"TALK": os.environ.get("SHORT_TALK", "956:1080:402:0")} if jd["talk"] else \
          {"SCR": os.environ.get("SHORT_SCR", "1099:618:405:100"), "CAM": os.environ.get("SHORT_CAM", "640:364:1280:716")}
    ok, out = run(["bash", os.path.join(T, "shorts_render2.sh"), "src.mp4", "p", "short.mp4", f"{jd['from'] - off:.2f}-{jd['to'] - off:.2f}"], cwd=d, env=env, timeout=1800)
    if not ok: raise RuntimeError(out)
    run("ffmpeg -loglevel error -y -i short.mp4 -vf scale=360:640,fps=30 -c:v libx264 -crf 24 -c:a aac -b:a 96k preview.mp4", cwd=d)
    jd["stage"] = "shelf"; jsave(j, jd); os.remove(os.path.join(d, "src.mp4"))
    send_file(os.path.join(d, "preview.mp4"), f"Шортс {j}: {jd['lines']}", "video")
    say(f"На полке: {j}. Название: /stitle {j} ТЕКСТ #shorts · полка: /shorts · публикация: /pubshorts\n"
        "Кадрирование не то? Укажи SHORT_SCR/SHORT_CAM в настройках (кропы по раскладке стрима).")
def cmd_stitle(m, a):
    mm = re.match(r"^(\S+)\s+(.+)$", a or ""); jd = jload(mm[1]) if mm else None
    if not jd: return say("Формат: /stitle s1114_153012 НАЗВАНИЕ #shorts")
    jd["title"] = mm[2][:100]; jsave(mm[1], jd); say("✅")
def shelf():
    root = os.path.join(WORK, "jobs")
    return [j for j in sorted(os.listdir(root)) if j.startswith("s") and (jload(j) or {}).get("stage") == "shelf"] if os.path.isdir(root) else []
def cmd_shorts(m, a):
    say("\n".join(f"<b>{j}</b> {esc((jload(j) or {}).get('title') or (jload(j) or {}).get('lines'))}" for j in shelf()) or "Полка пуста")
def pubshorts_do(m, a):
    iv = int(a) if a and a.strip().isdigit() else 10; q = shelf()
    if not q: return say("Полка пуста")
    def job():
        for n, j in enumerate(q):
            jd = jload(j); d = jdir(j); t0 = time.time()
            title = jd.get("title") or (jd["lines"].replace("|", " ") + " #shorts")
            main_link = S.get("last_main_link", "")
            meta = {"video": os.path.join(d, "short.mp4"), "title": title[:100],
                    "description": (f"Полное видео: {main_link}\n\n" if main_link else "") + "💜 Твич T2x2 - https://www.twitch.tv/t2x2\n✈️ Телеграм - t.me/t2xtwitch\n\n#t2x2 #т2х2 #shorts",
                    "tags": ["t2x2", "т2х2", "shorts", "нарезка"], "visibility": "PUBLIC", "channel": CHANNEL, "shot_dir": os.path.join(d, "up")}
            json.dump(meta, open(os.path.join(d, "studio.json"), "w"), ensure_ascii=False)
            link, err = publish(os.path.join(d, "studio.json"), j)
            if link: jd["stage"] = "published"; jd["link"] = link; jsave(j, jd); say(f"✅ {n + 1}/{len(q)} {esc(title)}\n{link}")
            else:
                say("⛔ Лимит загрузок — остальное завтра." if err == "LIMIT" else f"❌ {esc(title)}: <code>{esc(err)}</code>"); return
            if n + 1 < len(q): time.sleep(max(0, iv * 60 - (time.time() - t0)))
        say("🏁 Полка опубликована.")
    bg("публикация шортсов", job)

# ----------------------------------------------------------------- service
def cmd_cookies(m, a): S["await_cookies"] = True; save(); say("Пришли файл cookies.txt (Netscape) подтверждённой сессии. Хранится только на сервере.")
def on_document(m):
    if S.get("await_cookies"):
        p = os.path.join(WORK, "cookies.txt")
        if download(m["document"]["file_id"], p):
            os.chmod(p, 0o600); S["await_cookies"] = False; save(); say("🍪 Cookies сохранены. /panel — поднять Studio.")
def cmd_panel(m, a):
    def job():
        ok = panel_start(); u = studio_url()
        if ok and "studio.youtube.com" in u: say("✅ Studio открыта, вход подтверждён.")
        elif ok: say(f"⚠️ Браузер поднят, но не Studio: {esc(u[:120])}\nСессия не подтверждена — нужны свежие cookies (/cookies). Пароли/капчи бот НЕ вводит.")
        else: say("❌ Браузер не поднялся (см. panel.log)")
    bg("studio", job)
def cmd_limits(m, a):
    def job():
        if not panel_start(): return say("Studio не поднялась")
        ok, out = run(["node", os.path.join(T, "tgbot", "studio_restrictions.js"), CHANNEL], cwd=REPO, timeout=300, tail=40)
        say(f"<b>Ограничения в Studio</b>\n<pre>{esc(out)}</pre>")
    bg("проверка ограничений", job)

def cmd_pair(m, a):
    if not S.get("owner") and a.strip() == S.get("pair_code"):
        S["owner"] = m["from"]["id"]; S.pop("pair_code", None); save(); say("🔐 Бот привязан к тебе. " + HELP)

# ----------------------------------------------------------------- confirmations: the bot double-checks every heavy request
PENDING = {}
def ask(text, fn, *args, warn=None):
    key = secrets.token_hex(4); PENDING[key] = (fn, args, time.time())
    w = ("\n\n⚠️ <b>Проверь:</b>\n" + "\n".join("• " + esc(x) for x in warn)) if warn else ""
    blog("confirm_ask", key=key, text=re.sub(r"<[^>]+>", "", text)[:500], warn=warn or [])
    say(f"❓ {text}{w}", [[{"text": "✅ Да", "callback_data": f"ok:{key}"}, {"text": "✖️ Отмена", "callback_data": f"no:{key}"}]])
def confirm_answer(k, key):
    item = PENDING.pop(key, None)
    blog("confirm_answer", key=key, answer=k, known=bool(item))
    if not item: return say("Это подтверждение устарело — повтори команду.")
    if k == "no" or time.time() - item[2] > 3600: return say("Отменено 👍" if k == "no" else "Подтверждение просрочено (больше часа). Повтори команду.")
    fn, args, _ = item; fn(*args)

MAT_RE = re.compile(r"(?i)(бля|хуй|хуе|пизд|ебат|ебан|ёбан|сука|залуп|мудак)")
def cmd_cut(m, a):
    if not S.get("vod"): return say("Сначала выбери стрим: /start → режим")
    parts = (a or "").split(); r = resolve_range(parts[0]) if parts else None
    if not r: return cut_do(m, a)                                       # prints the format help
    if r[1] <= r[0]: return say(f"Конец ({fmt(r[1])}) раньше начала ({fmt(r[0])}) — проверь запрос. Пример: /cut 94-110")
    warn = []; dur = r[1] - r[0]; vlen = S.get("vod_len", 0)
    if dur < 180: warn.append(f"очень короткий кусок: {dur / 60:.1f} мин — точно основное видео, а не шортс?")
    if dur > 3 * 3600: warn.append(f"очень длинный кусок: {dur / 3600:.1f} ч")
    if vlen and r[1] > vlen + 60: warn.append(f"конец {fmt(r[1])} дальше конца VOD ({fmt(vlen)})")
    if dur > 45 * 60 and not (len(parts) > 1): warn.append("длиннее 45 мин → соберу ЛУЧШИЕ МОМЕНТЫ на ~25 мин (или укажи длину: /cut … 30)")
    for j in os.listdir(os.path.join(WORK, "jobs")) if os.path.isdir(os.path.join(WORK, "jobs")) else []:
        jd = jload(j) or {}
        if jd.get("vod") == S["vod"] and jd.get("kind") == "main" and min(jd.get("to", 0), r[1]) - max(jd.get("from", 0), r[0]) > 60:
            warn.append(f"этот кусок уже нарезался: {j} ({jd.get('stage')})"); break
    ask(f"Нарезать VOD {S['vod']}: <b>{fmt(r[0])}–{fmt(r[1])}</b> ({dur / 60:.1f} мин)?", cut_do, m, a, warn=warn)
def cmd_publish(m, a):
    j, jd = cur_job()
    if not jd: return say("Нет текущей нарезки (/jobs)")
    warn = []
    if not jd.get("title"): return say("Сначала /title НАЗВАНИЕ")
    if MAT_RE.search(jd["title"]): warn.append("в названии мат/грубое слово → риск 18+ (docs/no-yellow-card.md)")
    if len(jd["title"]) > 100: warn.append("название длиннее 100 символов")
    if not jd.get("cover"): warn.append("нет обложки (пришли фото), опубликую без неё только если ответишь «Да»")
    if len(jd.get("chapters", [])) < 3: warn.append("меньше 3 глав — YouTube не покажет тайм-коды")
    if jd.get("link"): warn.append(f"уже опубликовано: {jd['link']} — будет ДУБЛЬ")
    ask(f"Опубликовать <b>{esc(jd['title'])}</b> ({j}) на канал?", publish_do, m, "без обложки" if not jd.get("cover") else a, warn=warn)
def cmd_pubshorts(m, a):
    q = shelf()
    if not q: return say("Полка пуста")
    lst = "\n".join(f"• {esc((jload(j) or {}).get('title') or (jload(j) or {}).get('lines'))}" for j in q)
    iv = int(a) if a and a.strip().isdigit() else 10
    warn = [f"шортсов {len(q)} — суточный лимит канала ~3–4 загрузки"] if len(q) > 4 else []
    ask(f"Опубликовать полку ({len(q)} шт., интервал {iv} мин)?\n{lst}", pubshorts_do, m, a, warn=warn)

# ----------------------------------------------------------------- Twitch: live status / last streams (public GQL)
GQL_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
TWITCH = os.environ.get("TWITCH_LOGIN", "t2x2")
def gql(q):
    try:
        return requests.post("https://gql.twitch.tv/gql", headers={"Client-Id": GQL_ID}, json={"query": q}, timeout=20).json().get("data") or {}
    except Exception as e:
        blog("error", where="gql", err=str(e)); return {}
def twitch_info(n=6):
    d = gql('query{user(login:"%s"){stream{id createdAt game{displayName} viewersCount} videos(first:%d,type:ARCHIVE,sort:TIME)'
            '{edges{node{id createdAt lengthSeconds status game{displayName}}}}}}' % (TWITCH, n))
    u = d.get("user") or {}
    vids = [e["node"] for e in (u.get("videos") or {}).get("edges", [])]
    return u.get("stream"), vids
def when(iso):
    """Twitch UTC time -> Moscow time (owner's)"""
    import calendar
    t = calendar.timegm(time.strptime(iso[:19], "%Y-%m-%dT%H:%M:%S")) + 3 * 3600
    return time.strftime("%d.%m %H:%M МСК", time.gmtime(t))

MENU = [[{"text": "🔴 Прямой эфир", "callback_data": "mode:live"}, {"text": "📼 Прошедший стрим", "callback_data": "mode:past"}],
        [{"text": "🎬 Шортсы (полка)", "callback_data": "mode:shorts"}, {"text": "ℹ️ Статус", "callback_data": "mode:status"}]]
BETA = "🧪 <b>Бета-тест.</b> Основной способ нарезки пока — по опыту в сессии Claude; бот учится по логам."
def cmd_start(m, a): say(f"{BETA}\n\nВыбери режим:", MENU)
def cmd_help(m, a): say(HELP)

def mode_live():
    st, vids = twitch_info(3)
    if not st:
        blog("live_check", live=False)
        return say(f"⚫ {TWITCH} сейчас НЕ в эфире. Можно нарезать прошедший стрим.", [[{"text": "📼 Прошедший стрим", "callback_data": "mode:past"}]])
    rec = next((v for v in vids if v.get("status") == "RECORDING"), None)
    if not rec: return say("Эфир идёт, но запись (VOD) не найдена — у стримера могут быть выключены записи. Нарезать нечего.")
    blog("live_check", live=True, vod=rec["id"], game=(st.get("game") or {}).get("displayName"))
    warn = []
    if S.get("live_on"): warn.append(f"нарезка эфира уже идёт (VOD {S.get('vod')}) — перезапущу")
    ask(f"🔴 {TWITCH} в эфире: {esc((st.get('game') or {}).get('displayName', ''))}, {st.get('viewersCount', '?')} зрителей, "
        f"VOD {rec['id']} (уже {fmt(rec['lengthSeconds'])}).\n<b>Составить нарезки идущего стрима?</b>\n"
        "Каждые 4 мин пришлю, что нарезается; остановить — кнопкой.", live_start, rec["id"], warn=warn)
def live_start(vod):
    S["vod"] = vod; S["live_on"] = True; S["live_proposed"] = []; save(); vdir(vod)
    d = vdir(); open(os.path.join(d, "transcript_live.txt"), "a").close()
    p = S.get("watch_pid")
    if not (p and pid_alive(p)):
        pr = subprocess.Popen(["bash", os.path.join(T, "live_update.sh"), vod, d], env=dict(os.environ, LIVE_EVERY="240"),
                              stdout=open(os.path.join(d, "live.log"), "a"), stderr=subprocess.STDOUT, start_new_session=True)
        S["watch_pid"] = pr.pid; save()
    blog("live_start", vod=vod)
    say("▶️ Нарезка эфира запущена. Первый отчёт через ~4 мин (сначала догоняю расшифровку с начала стрима).",
        [[{"text": "⏹ Остановить нарезку эфира", "callback_data": "live:stop"}]])
    threading.Thread(target=live_loop, args=(vod,), daemon=True).start()
def live_stop():
    S["live_on"] = False; save(); cmd_stop(None, ""); blog("live_stop", vod=S.get("vod"))
def live_loop(vod):
    d = vdir(vod); tr = os.path.join(d, "transcript_live.txt"); last_len = -1
    while S.get("live_on") and S.get("vod") == vod:
        time.sleep(240)
        if not S.get("live_on"): break
        st, vids = twitch_info(2); rec = next((v for v in vids if v["id"] == vod), {})
        lines = sum(1 for _ in open(tr)) if os.path.exists(tr) else 0
        upto = 0.0
        if lines:
            try: upto = float(open(tr).read().strip().splitlines()[-1].split("-")[0])
            except Exception: pass
        ev = []
        if lines > 50:
            run(["python3", os.path.join(T, "auto_events.py"), tr, "--json", os.path.join(d, "events.json")])
            try: ev = json.load(open(os.path.join(d, "events.json")))
            except Exception: ev = []
        done = ev[:-1]                                                     # the last topic is still going on
        good = [e for e in done if e["score"] >= float(os.environ.get("LIVE_MIN_SCORE", 3.8)) and 4 <= e["dur_min"] <= 45 and not e.get("risk")]
        new = [e for e in good if e["start"] not in S.get("live_proposed", [])]
        jobs = [f"{j}: {(jload(j) or {}).get('stage')}" for j in sorted(os.listdir(os.path.join(WORK, 'jobs')))[-5:]] if os.path.isdir(os.path.join(WORK, "jobs")) else []
        blog("live_tick", vod=vod, vod_len=rec.get("lengthSeconds"), transcript_upto=upto, topics=len(ev), good=len(good), new=len(new), live=bool(st))
        txt = (f"📡 <b>Эфир</b>: {'идёт' if st else 'ЗАКОНЧИЛСЯ'} · VOD {fmt(rec.get('lengthSeconds', 0))} · расшифровано до {fmt(upto)}\n"
               f"Тем найдено: {len(ev)} (завершённых {len(done)}, годных в нарезку {len(good)})"
               + ("\n\n<b>Новые темы для нарезки:</b>\n" + "\n".join(f"• {fmt(e['start'])}–{fmt(e['end'])} ({e['dur_min']} мин) ⭐{e['score']} «{esc(e['first'][:90])}»" for e in new) if new else "")
               + ("\n\nНарезки: " + "; ".join(esc(x) for x in jobs) if jobs else ""))
        kb = [[{"text": f"✂️ {fmt(e['start'])} ({e['dur_min']}м)", "callback_data": f"cut:{fmt(e['start'])}-{fmt(e['end'])}"}] for e in new[:4]]
        kb.append([{"text": "⏹ Остановить нарезку эфира", "callback_data": "live:stop"}])
        say(txt, kb)
        S.setdefault("live_proposed", []).extend(e["start"] for e in new); save()
        if not st:
            S["live_on"] = False; save(); say("🏁 Стрим закончился. /map — все темы, нарезка по кнопкам."); break

def mode_past():
    st, vids = twitch_info(7)
    done = [v for v in vids if v.get("status") != "RECORDING"]
    if not done: return say("Не нашёл прошедших стримов (VOD скрыты/удалены?)")
    blog("past_list", vods=[v["id"] for v in done])
    kb = [[{"text": f"📼 {when(v['createdAt'])} · {fmt(v['lengthSeconds'])} · {(v.get('game') or {}).get('displayName', '')[:18]}",
            "callback_data": f"past:{v['id']}"}] for v in done[:6]]
    say("Последние стримы — какой нарезаем?" + ("\n(сейчас идёт эфир — для него «🔴 Прямой эфир»)" if st else ""), kb)
def past_pick(vod):
    st, vids = twitch_info(8); v = next((x for x in vids if x["id"] == vod), None)
    if not v: return say("Этот VOD не найден (удалён?)")
    warn = []
    if v.get("status") == "RECORDING": warn.append("это ИДУЩИЙ эфир — лучше режим «🔴 Прямой эфир»")
    tr = os.path.join(WORK, f"vod{vod}", "transcript_live.txt")
    have = os.path.exists(tr) and os.path.getsize(tr) > 1000
    if have: warn.append("расшифровка уже есть — сразу покажу темы")
    if S.get("live_on"): warn.append("сейчас идёт нарезка эфира — она продолжится параллельно")
    ask(f"Нарезать стрим <b>{when(v['createdAt'])}</b> ({fmt(v['lengthSeconds'])}, {esc((v.get('game') or {}).get('displayName', ''))}), VOD {vod}?"
        + ("" if have else f"\nСначала расшифровка (~{int(v['lengthSeconds'] / 27 / 60) + 3} мин)."), past_start, vod, v["lengthSeconds"], have, warn=warn)
def past_start(vod, length, have):
    S["vod"] = vod; S["vod_len"] = length; save(); vdir(vod); blog("past_start", vod=vod, have_transcript=have)
    if have: return cmd_map(None, "")
    def job():
        cmd_transcribe_job(); cmd_map(None, "")
    bg("расшифровка", job)

def cmd_logs(m, a):
    n = int(a) if a and a.isdigit() else 1
    files = sorted(f for f in os.listdir(LOGD) if f.endswith(".jsonl"))[-n:]
    if not files: return say("Логов пока нет")
    if len(files) == 1: return send_file(os.path.join(LOGD, files[0]), f"Лог бота {files[0]} — перешли его Claude для улучшения бота")
    z = os.path.join(WORK, "logs.zip"); run(["zip", "-j", "-q", z] + [os.path.join(LOGD, f) for f in files])
    send_file(z, f"Логи бота за {len(files)} дн.")

CMDS = {"start": cmd_start, "menu": cmd_start, "help": cmd_help, "logs": cmd_logs, "live": lambda m, a: mode_live(), "past": lambda m, a: mode_past(), "status": cmd_status, "vod": cmd_vod, "watch": cmd_watch, "stop": cmd_stop,
        "transcribe": cmd_transcribe, "map": cmd_map, "cut": cmd_cut, "title": cmd_title, "chapters": cmd_chapters,
        "chapter": cmd_chapter, "desc": cmd_desc, "publish": cmd_publish, "jobs": cmd_jobs, "job": cmd_job, "short": cmd_short,
        "stitle": cmd_stitle, "shorts": cmd_shorts, "pubshorts": cmd_pubshorts, "cookies": cmd_cookies, "panel": cmd_panel,
        "limits": cmd_limits, "pair": cmd_pair, "drop": cmd_drop, "add": cmd_add, "go": cmd_go}

def handle(u):
    if "callback_query" in u:
        q = u["callback_query"]; tg("answerCallbackQuery", callback_query_id=q["id"])
        if q["from"]["id"] != S.get("owner"): return
        k, v = q["data"].split(":", 1)
        blog("in", button=q["data"])
        if k == "cut": cmd_cut(q["message"], v)
        if k in ("ok", "no"): return confirm_answer(k, v)
        if k == "mode": return mode_live() if v == "live" else (mode_past() if v == "past" else (cmd_shorts(None, "") if v == "shorts" else cmd_status(None, "")))
        if k == "past": return past_pick(v)
        if k == "live" and v == "stop": return live_stop()
        if k == "go": cmd_go(q["message"], v)
        return
    m = u.get("message") or {}
    if not m: return
    uid = m.get("from", {}).get("id"); txt = m.get("text", "") or m.get("caption", "") or ""
    if uid != S.get("owner"):
        if txt.startswith("/pair"): cmd_pair(m, txt[5:].strip())
        return                                                           # strangers are ignored
    blog("in", text=txt[:500], photo="photo" in m, document=(m.get("document") or {}).get("file_name"))
    if "photo" in m: return on_photo(m)
    if "document" in m: return on_document(m)
    if txt.startswith("/"):
        c, _, a = txt[1:].partition(" "); c = c.split("@")[0].lower()
        f = CMDS.get(c)
        if f: f(m, a.strip())
        else: say("Не знаю такую команду. /help")

def main():
    log("bot started; work dir", WORK); blog("start", work=WORK, owner=bool(S.get("owner")))
    tg("setMyCommands", commands=[{"command": c, "description": d} for c, d in [
        ("status", "что происходит"), ("vod", "выбрать VOD/канал"), ("watch", "следить за стримом"), ("transcribe", "расшифровать VOD"),
        ("start", "меню: эфир / прошедший стрим"), ("live", "нарезать идущий эфир"), ("past", "нарезать прошедший стрим"), ("logs", "логи бота"),
        ("map", "темы стрима"), ("cut", "нарезать тему"), ("jobs", "нарезки"), ("title", "название"), ("chapters", "тайм-коды"),
        ("desc", "описание"), ("publish", "опубликовать"), ("short", "собрать шортс"), ("shorts", "полка шортсов"),
        ("pubshorts", "опубликовать полку"), ("limits", "18+/ограничения"), ("cookies", "прислать cookies"), ("panel", "поднять Studio"), ("help", "помощь")]])
    off = S.get("offset", 0)
    while True:
        r = tg("getUpdates", offset=off, timeout=50)
        for u in r.get("result", []) if r else []:
            off = u["update_id"] + 1; S["offset"] = off; save()
            try: handle(u)
            except Exception: log(traceback.format_exc())

if __name__ == "__main__":
    main()
