"""«Умный» слой Telegram-бота (23.11.2026): мозг Gemini (brain.py) + конвейер по текущему регламенту ветки.
Подключается в bot.py: smart.init(sys.modules[__name__]) — пользуется функциями бота (say, ask, bg, run, jsave, …).

Что добавляет:
- /smap — карта стрима от Gemini: события (реакции/игры), части длинной игры, что вырезать (донаты/18+/тех.паузы), шортсы;
- ✂️ по кнопке — нарезка события целиком или СЕРИЕЙ частей: make_main.sh (без зума, блюр баннеров, звук, мат глушится,
  новое интро, строгие референсы) + аутро для серий; 18+ сцены ВЫРЕЗАЮТСЯ (правило 23.11: блюр не спас GTA #5);
- описание в стиле канала + блок «Все части» (обновляется во всех частях после каждой публикации);
- после публикации: через 2 ч проверка 18+/претензий, блокирующие песни → Erase song (правило 18.11);
- шортсы в стиле tiktok-style2 (tools/shorts_tt) с хуком от Gemini → полка → публикация на YouTube с интервалом;
- вход в Studio слетел → бот сам поднимает пульт + туннель и присылает ссылку;
- обычный текст → Gemini с функциями бота (тяжёлое — только после ✅).
"""
import json, os, re, subprocess, threading, time, traceback
import brain

B = None                                                  # the bot module
def init(bot):
    global B; B = bot

# ----------------------------------------------------------------- helpers
def lines():
    tr = os.path.join(B.vdir(), "transcript_live.txt")
    if not os.path.exists(tr) or os.path.getsize(tr) < 100: return []
    out = []
    for l in open(tr, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*(\d+(?:\.\d+)?)-\s*(\d+(?:\.\d+)?)\s(.*)", l)
        if m: out.append((float(m[1]), float(m[2]), m[3]))
    return out
def mapf(): return os.path.join(B.vdir(), "brain_map.json")
def load_map():
    try: return json.load(open(mapf()))
    except Exception: return None
def F(t): return B.fmt(t)

def cut_before(t, A, cuts): return sum(max(0, min(b, t) - max(a, A)) for a, b in cuts if a < t)
def out_time(t, A, cuts, intro=10.0): return intro + (t - A) - cut_before(t, A, cuts)

def chapters_text(chs, A, B_, cuts, dur):
    """[[stream_t, title]] → YouTube timecodes for the rendered file (intro 10 s; cuts removed); rules of docs/timecodes.md"""
    rows = []
    for t, title in sorted(chs or [], key=lambda x: x[0]):
        if not (A - 30 <= t < B_): continue
        if any(a <= t < b for a, b in cuts): continue
        o = 0 if not rows else out_time(t, A, cuts)
        if rows and o - rows[-1][0] < 10: continue
        title = brain.clean_title(title, 60) or "…"
        rows.append((o, title))
    rows = [r for r in rows if r[0] < dur - 10]                   # chapters at the very end are dropped FIRST (bug found by
    if len(rows) < 3: return ""                                  # the bot's agent 23.11: the ≥3 check ran before this filter)
    return "\n".join(f"{F(o)} - {t}" for o, t in rows)

def tunnel_link():
    """Studio login lost → owner signs in himself through the pult page. Cloudflare quick tunnel (no account) → devtunnel."""
    B.panel_start(); tok = B.S.get("rb_token", "")
    cf = os.path.join(B.WORK, "bin", "cloudflared")
    if not os.path.exists(cf):
        os.makedirs(os.path.dirname(cf), exist_ok=True)
        B.run(f"curl -sSL -o {cf} https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 && chmod +x {cf}", timeout=300)
    logf = os.path.join(B.WORK, "cf.log")
    if not B.S.get("tunnel_pid") or not B.pid_alive(B.S["tunnel_pid"]):
        p = subprocess.Popen([cf, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8765"], stdout=open(logf, "w"),
                             stderr=subprocess.STDOUT, start_new_session=True)
        B.S["tunnel_pid"] = p.pid; B.save()
    for _ in range(30):
        time.sleep(2)
        m = re.findall(r"https://[a-z0-9-]+\.trycloudflare\.com", open(logf).read())
        if m: return f"{m[0]}/?t={tok}"
    return ""
def need_login(where):
    link = tunnel_link()
    B.say(f"🔐 YouTube просит подтвердить вход ({B.esc(where)}). Открой и войди сам (пароль/код вводишь только ты):\n{link or '(туннель не поднялся — /panel)'}\n"
          "Когда войдёшь — напиши «вошёл», я повторю действие.")
    B.S["after_login"] = where; B.save()

def node(script, *args, timeout=900):
    if not B.panel_start(): return False, "Studio не поднялась (cookies?)"
    ok, out = B.run(["node", os.path.join(B.T, "yt", script), "cdp:9222", *args], cwd=B.REPO, timeout=timeout, tail=40)
    if "LOGIN_NEEDED" in out or "accounts.google.com" in out or "NO_STUDIO_TAB" in out:
        need_login(script); return False, "LOGIN"
    return ok, out

# ----------------------------------------------------------------- map
def cmd_smap(m, a):
    if not brain.available(): return B.say("Нет ключа Gemini (GEMINI_API_KEY в секретах). Пока работает /map.")
    L = lines()
    if not L: return B.say("Нет расшифровки: /transcribe (прошедший стрим) или /watch (эфир)")
    def job():
        B.activity("Gemini читает расшифровку"); B.say(f"🧠 Читаю расшифровку ({len(L)} фраз, до {F(L[-1][0])}) и ищу события и шортсы…")
        r = brain.pick_events(L)
        r = validate_map(r, L[-1][1]); json.dump(r, open(mapf(), "w"), ensure_ascii=False, indent=1)
        B.blog("smap", events=len(r["events"]), shorts=len(r["shorts"])); show_map(r)
    B.bg("карта стрима (Gemini)", job)
def validate_map(r, end):
    ev = []
    for e in r.get("events", []):
        try: a, b = float(e["from"]), float(e["to"])
        except Exception: continue
        if not (0 <= a < b <= end + 5) or b - a < 120: continue
        e["from"], e["to"] = a, b
        e["parts"] = [p for p in e.get("parts", []) if a - 5 <= float(p["from"]) < float(p["to"]) <= b + 5 and float(p["to"]) - float(p["from"]) >= 300]
        e["cut"] = [[float(c[0]), float(c[1]), (c[2] if len(c) > 2 else "")] for c in e.get("cut", []) if a <= float(c[0]) < float(c[1]) <= b]
        e["title"] = brain.clean_title(e.get("title", "")) or ("T2x2 ИГРАЕТ В " + (e.get("game") or "ИГРУ")) if e.get("kind") == "game" else (brain.clean_title(e.get("title", "")) or "T2x2 СМОТРИТ: …")
        ev.append(e)
    sh = []
    for s in r.get("shorts", []):
        try: a, b = float(s["from"]), float(s["to"])
        except Exception: continue
        if not (0 <= a < b <= end + 5 and 12 <= b - a <= 60): continue
        l1, l2 = brain.clean_title(s.get("line1", ""), 30).upper(), brain.clean_title(s.get("line2", ""), 30).upper()
        if not l1: continue
        sh.append({"from": a, "to": b, "line1": l1, "line2": l2, "why": s.get("why", "")[:160]})
    return {"events": ev, "shorts": sh, "at": time.time()}
def show_map(r):
    rows, kb = [], []
    for i, e in enumerate(r["events"], 1):
        parts = "".join(f"\n   ▫️ часть {k}: {F(p['from'])}–{F(p['to'])} {B.esc(p.get('title', ''))}" for k, p in enumerate(e["parts"], 1))
        cuts = "".join(f"\n   ✂️ вырежу {F(c[0])}–{F(c[1])}: {B.esc(c[2])}" for c in e["cut"][:6])
        rows.append(f"<b>{i}.</b> {F(e['from'])}–{F(e['to'])} ({(e['to'] - e['from']) / 60:.0f} мин) · {e.get('score', '?')}/10\n"
                    f"   <b>{B.esc(e['title'])}</b>{(' · ⚠️ ' + B.esc(e['risk'])) if e.get('risk') else ''}{parts}{cuts}")
        kb.append([{"text": f"✂️ {i}. {e['title'][:28]}" + (f" ({len(e['parts'])} ч.)" if e["parts"] else ""), "callback_data": f"sm:ev:{i}"}])
    sh = "\n".join(f"🎬 <b>{k}.</b> {F(s['from'])}–{F(s['to'])} «{B.esc(s['line1'])} / {B.esc(s['line2'])}» — {B.esc(s['why'][:90])}" for k, s in enumerate(r["shorts"], 1))
    if r["shorts"]: kb.append([{"text": f"🎬 Собрать все шортсы ({len(r['shorts'])})", "callback_data": "sm:shorts:all"}])
    B.say("🧠 <b>Карта стрима (Gemini)</b> — время стрима:\n\n" + ("\n\n".join(rows) or "событий не нашёл") +
          (f"\n\n<b>Шортсы:</b>\n{sh}" if sh else "") + "\n\nНажми ✂️ — нарежу по регламенту (части — каждая отдельным видео).", kb)

# ----------------------------------------------------------------- main videos (event → one video or a series of parts)
def youtube_id(q):
    ok, out = B.run(["yt-dlp", "--flat-playlist", "--print", "%(id)s", f"ytsearch1:{q}"], timeout=120)
    ids = re.findall(r"^[A-Za-z0-9_-]{11}$", out, re.M); return ids[0] if ok and ids else ""
def start_event(i, confirm=True):
    r = load_map(); e = r["events"][i - 1] if r and 0 < i <= len(r["events"]) else None
    if not e: return B.say("Нет такого события (/smap)")
    parts = e["parts"] or [{"from": e["from"], "to": e["to"], "title": ""}]
    sid = "x" + time.strftime("%m%d%H%M%S")
    def go():
        B.S.setdefault("series", {})[sid] = {"title": e["title"], "links": {}}; B.save()
        for k, p in enumerate(parts, 1):
            j = f"{sid}_{k}"
            cuts = [[c[0], c[1]] for c in e["cut"] if c[1] > p["from"] and c[0] < p["to"]]
            title = e["title"] + (f" #{k}" if len(parts) > 1 else "")
            B.jsave(j, {"vod": B.S["vod"], "from": float(p["from"]), "to": float(p["to"]), "cuts": cuts, "kind": e.get("kind"), "smart": True,
                        "series": sid if len(parts) > 1 else "", "part": k, "nparts": len(parts), "title": title[:100], "game": e.get("game", ""),
                        "original": e.get("original", ""), "chapters_src": e.get("chapters", []), "stage": "queued"})
            B.bg(f"нарезка {j}", smart_job, j)
        B.say(f"✂️ В очереди: {len(parts)} видео «{B.esc(e['title'])}». Каждое пришлю с превью, названием и описанием.")
    warn = [f"{F(c[0])}–{F(c[1])}: {c[2]}" for c in e["cut"]][:6]
    if confirm: B.ask(f"Нарезать <b>{B.esc(e['title'])}</b> {F(e['from'])}–{F(e['to'])} — {len(parts)} видео?", go,
                      warn=(["вырежу: " + w for w in warn] or None))
    else: go()

def smart_job(j):
    jd = B.jload(j); d = B.jdir(j); A, Bt = jd["from"], jd["to"]
    B.need_disk((Bt - A) / 3600 * 8 + 3, "нарезка")
    env = {"MAT": "1", "NO_PREVIEW": "1"}
    if jd.get("kind") == "game" or not jd.get("original"): env["GAME"] = "1"
    else:
        oid = youtube_id(jd["original"]); env.update({"ORIG": oid} if oid else {"GAME": "1"}); jd["orig_id"] = oid
    cuts = " ".join(f"{a:.1f}-{b:.1f}" for a, b in jd.get("cuts", []))
    B.activity(f"нарезка {j}: скачиваю и рендерю {F(A)}–{F(Bt)}")
    B.say(f"✂️ <b>{B.esc(jd['title'])}</b>: {F(A)}–{F(Bt)} — качаю 1080p60, рендер по регламенту (мат, интро{', аутро' if jd.get('series') else ''})…")
    ok, out = B.run(["bash", os.path.join(B.T, "make_main.sh"), jd["vod"], str(A), str(Bt), cuts, "final", d], cwd=d, env=env, timeout=10 * 3600, tail=30)
    if not ok or not os.path.exists(os.path.join(d, "final.mp4")): raise RuntimeError("make_main: " + out)
    for f in ("ev.mp4", "out.mp4"):                                        # disk: keep only the final file
        try: os.remove(os.path.join(d, f))
        except FileNotFoundError: pass
    if jd.get("series"):
        ok, out = B.run(["bash", os.path.join(B.T, "add_outro.sh"), "final.mp4", "final_o.mp4"], cwd=d, timeout=1800)
        if ok: os.replace(os.path.join(d, "final_o.mp4"), os.path.join(d, "final.mp4"))
    dur = float(B.run("ffprobe -v error -show_entries format=duration -of csv=p=0 final.mp4", cwd=d)[1] or 0)
    try:
        mt = brain.meta(jd.get("kind", ""), jd["title"], lines(), A, Bt, jd.get("original", ""), jd.get("game", ""), jd.get("part") if jd.get("series") else None)
    except Exception as ex:
        mt = {}; B.blog("error", where="meta", err=str(ex)[:300])
    t2 = brain.clean_title(mt.get("title", ""))
    if t2 and not jd.get("series"): jd["title"] = t2
    cuts_l = [tuple(c) for c in jd.get("cuts", [])]
    jd["timecodes"] = chapters_text(mt.get("chapters") or jd.get("chapters_src"), A, Bt, cuts_l, dur)
    jd["seo"] = mt.get("seo", ""); jd["tags"] = [t for t in mt.get("tags", []) if not brain.MAT.search(t)][:15]
    jd["final"] = os.path.join(d, "final.mp4"); jd["stage"] = "preview"; B.jsave(j, jd)
    B.make_preview(d, "final.mp4", "preview.mp4"); B.send_file(os.path.join(d, "preview.mp4"), f"Превью {j}", "video")
    z = [f for f in os.listdir(os.path.join(d, "refs")) if f.endswith(".zip")] if os.path.isdir(os.path.join(d, "refs")) else []
    if z: B.send_file(os.path.join(d, "refs", z[0]), "Референсы для обложки")
    B.S["job"] = j; B.save()
    B.say(f"✅ <b>{B.esc(jd['title'])}</b> готово ({F(dur)}).\n\n<b>Описание:</b>\n<pre>{B.esc(describe(j, jd))}</pre>\n"
          "👉 Пришли обложку (фото) — сразу предложу опубликовать. Поменять название: /title …")

def describe(j, jd):
    sid = jd.get("series"); links = (B.S.get("series", {}).get(sid, {}) or {}).get("links", {}) if sid else {}
    src = (f"🎬 Оригинал видео - https://youtu.be/{jd['orig_id']}\n" if jd.get("orig_id") else
           (f"🎮 Игра - {jd['game']}\n" if jd.get("game") else ""))
    allp = ""
    if sid:
        allp = "\nВсе части:\n" + "\n".join(f"#{k} - {links.get(str(k), '(скоро)')}" for k in range(1, jd["nparts"] + 1)) + "\n"
    tc = f"\nТайм-коды:\n{jd['timecodes']}\n" if jd.get("timecodes") else ""
    date = (B.S.get("vod_date") or time.strftime("%d.%m")) + "." + time.strftime("%y")
    tags = "#t2x2 #т2х2 " + ("#прохождение #игры" if jd.get("kind") == "game" else "#реакция") + " #нарезка"
    return (f"{jd['title']}\n\n{src}💜 Твич T2x2 - https://www.twitch.tv/t2x2\n✈️ Телеграм - t.me/t2xtwitch\n{allp}{tc}\n"
            f"Запись со стрима {date}\n\n{jd.get('seo', '')}\n\n{tags}").strip()

def after_publish(j, link):
    jd = B.jload(j); sid = jd.get("series")
    if sid:
        ser = B.S.setdefault("series", {}).setdefault(sid, {"links": {}}); ser["links"][str(jd["part"])] = link; B.save()
        edits = []
        for k, lk in ser["links"].items():
            jj = B.jload(f"{sid}_{k}")
            if jj: edits.append({"id": lk.rstrip("/").split("/")[-1], "description": describe(f"{sid}_{k}", jj)})
        p = os.path.join(B.WORK, f"links_{sid}.json"); json.dump(edits, open(p, "w"), ensure_ascii=False)
        ok, out = node("studio_edit.js", p)
        B.say("🔗 Ссылки «Все части» обновлены во всех вышедших частях." if ok else f"⚠️ Ссылки частей не обновил: <code>{B.esc(out[-300:])}</code>")
    vid = link.rstrip("/").split("/")[-1]
    def later():
        time.sleep(2 * 3600); check_video(vid)
    threading.Thread(target=later, daemon=True).start()

def check_video(vid):
    ok, out = node("studio_notices.js", vid, timeout=600)
    note = (re.findall(r"\| ([^|]*)$", out.strip().splitlines()[-1]) or [""])[0] if ok and out.strip() else ""
    ok2, out2 = node("studio_erase.js", vid, timeout=900)
    er = re.findall(r"ERASED (.+)", out2)
    B.say(f"🔎 Проверка https://youtu.be/{vid}: ограничения — {B.esc(note or 'нет')}; "
          + (f"заглушил песню: {B.esc(er[0])} (YouTube применит за минуты–часы)" if er else
             ("проверка музыки ещё идёт — повторю позже" if "CHECKS_PENDING" in out2 else "блокирующей музыки нет")))
    if "CHECKS_PENDING" in out2 or er:
        threading.Thread(target=lambda: (time.sleep(3600), check_video(vid)), daemon=True).start()

# ----------------------------------------------------------------- shorts (tiktok-style2 look, published on YouTube)
LAYOUTS = {"default": {"CAM": "440:285:1300:745", "SCR": "1264:711:328:0"},
           "game_small_cam": {"CAM": "396:272:1404:676", "SCR": "1060:596:338:230"}}
def start_shorts(which="all"):
    r = load_map()
    if not r or not r["shorts"]: return B.say("Шортсов в карте нет (/smap)")
    w = str(which).strip().lower()
    idx = range(1, len(r["shorts"]) + 1) if w in ("all", "все", "всё", "") or not re.findall(r"\d+", w) else [int(x) for x in re.findall(r"\d+", w) if 0 < int(x) <= len(r["shorts"])]
    for k in idx:
        s = r["shorts"][k - 1]; j = f"t{time.strftime('%m%d%H%M%S')}_{k}"
        B.jsave(j, {"vod": B.S["vod"], "from": s["from"], "to": s["to"], "kind": "short", "lines": f"{s['line1']}|{s['line2']}",
                    "why": s["why"], "stage": "queued", "smart": True})
        B.bg(f"шортс {j}", short_job, j)
    B.say(f"🎬 Собираю {len(list(idx))} шортс(ов). Каждый пришлю на проверку; публикация — /pubshorts 5 (интервал, мин).")
def short_job(j):
    jd = B.jload(j); d = B.jdir(j)
    ok, out = B.run(["python3", os.path.join(B.T, "vod.py"), jd["vod"], "1080p60", "src.mp4", str(jd["from"] - 15), str(jd["to"] + 15)], cwd=d, timeout=1800)
    if not ok: raise RuntimeError(out)
    off = float(re.findall(r"offset ([\d.]+)", out)[-1])
    lay = LAYOUTS.get(B.S.get("layout", "default"), LAYOUTS["default"])
    ok2, o2 = B.run(["python3", os.path.join(B.T, "layout_detect.py"), "src.mp4", f"{(jd['from'] + jd['to']) / 2 - off:.1f}"], cwd=d, timeout=120)
    try:
        det = json.loads(o2.strip().splitlines()[-1]); lay = {"CAM": det["CAM"], "SCR": det["SCR"]}; jd["layout"] = det["layout"]
    except Exception: pass                                            # face not found → the owner's/default layout
    ok, out = B.run(["bash", os.path.join(B.T, "shorts_tt", "render.sh"), "src.mp4", jd["lines"], "short.mp4",
                     f"{jd['from'] - off:.2f}-{jd['to'] - off:.2f}"], cwd=d, env=lay, timeout=1800)
    if not ok: raise RuntimeError(out)
    B.run("ffmpeg -loglevel error -y -i short.mp4 -vf scale=360:640,fps=30 -c:v libx264 -crf 24 -c:a aac -b:a 96k preview.mp4", cwd=d)
    l1, l2 = jd["lines"].split("|")
    jd["title"] = f"{l1} {l2} #shorts"[:100] if len(f"{l1} {l2}") < 90 else f"{l1}?! #shorts"
    jd["stage"] = "shelf"; B.jsave(j, jd); os.remove(os.path.join(d, "src.mp4"))
    B.send_file(os.path.join(d, "preview.mp4"), f"Шортс {j}: {jd['lines']}", "video")

# ----------------------------------------------------------------- smart chat (Gemini + bot functions)
FUNCS = [
    ("status", "что бот делает сейчас, очередь, нарезки", {}),
    ("map_stream", "прочитать расшифровку выбранного стрима и найти события, части и шортсы", {}),
    ("cut_event", "нарезать событие из карты стрима по номеру", {"n": {"type": "integer"}}),
    ("cut_range", "нарезать кусок стрима по времени (секунды стрима)", {"from": {"type": "number"}, "to": {"type": "number"}, "title": {"type": "string"}}),
    ("make_shorts", "собрать шортсы из карты: все или номер", {"which": {"type": "string"}}),
    ("publish_shorts", "опубликовать полку шортсов на YouTube с интервалом в минутах", {"interval": {"type": "integer"}}),
    ("set_title", "поменять название ТЕКУЩЕЙ нарезки, которая ещё НЕ опубликована (стадия превью)", {"title": {"type": "string"}}),
    ("publish", "опубликовать текущую нарезку (нужна обложка)", {}),
    ("check_video", "проверить опубликованное видео: 18+, претензии, заглушить блокирующую музыку", {"video": {"type": "string"}}),
    ("pick_stream", "выбрать прошедший стрим из списка или идущий эфир", {"live": {"type": "boolean"}}),
    ("layout", "раскладка стрима для шортсов: default или game_small_cam", {"name": {"type": "string"}}),
]
HEAVY = {"cut_event", "cut_range", "make_shorts", "publish_shorts", "publish"}
RU = {"cut_event": lambda a: f"нарезать событие №{a.get('n')} из карты (части — отдельными видео)",
      "cut_range": lambda a: f"нарезать кусок {F(a.get('from', 0))}–{F(a.get('to', 0))}",
      "make_shorts": lambda a: f"собрать шортсы: {a.get('which', 'все')}",
      "publish_shorts": lambda a: f"опубликовать полку шортсов на YouTube, интервал {a.get('interval', 10)} мин",
      "publish": lambda a: "опубликовать текущую нарезку"}
def confirm(name, args): B.ask(f"🤖 {B.esc(RU[name](args))}?", run_fn, name, args)
def md(t):                                             # Gemini markdown → Telegram HTML
    t = B.esc(t); t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t); return re.sub(r"(?m)^\s*[*-]\s+", "• ", t)
def tools():
    return [{"type": "function", "function": {"name": n, "description": d, "parameters": {"type": "object", "properties": p}}} for n, d, p in FUNCS]
def run_fn(name, a):
    if name == "status": return B.cmd_status(None, "")
    if name == "map_stream": return cmd_smap(None, "")
    if name == "cut_event": return start_event(int(a.get("n", 0)), confirm=False)
    if name == "cut_range": return B.start_cut(float(a["from"]), float(a["to"]), 0)
    if name == "make_shorts": return start_shorts(str(a.get("which", "all")))
    if name == "publish_shorts": return B.pubshorts_do(None, str(a.get("interval", 10)))
    if name == "set_title": return B.cmd_title(None, a.get("title", ""))
    if name == "publish": return B.cmd_publish(None, "")
    if name == "check_video":
        v = re.findall(r"[A-Za-z0-9_-]{11}", a.get("video", "")); return B.bg("проверка видео", check_video, v[-1]) if v else B.say("Дай ссылку на видео")
    if name == "pick_stream": return B.mode_live() if a.get("live") else B.mode_past()
    if name == "layout": B.S["layout"] = a.get("name", "default"); B.save(); return B.say(f"Раскладка шортсов: {B.S['layout']}")
HIST = []
RULES_FB = [(r"(?i)что (ты )?делаешь|статус|что происходит|почему (ничего|стоит)", lambda t: B.cmd_status(None, "")),
            (r"(?i)повтори", lambda t: retry_last()),
            (r"(?i)мест[оа]|диск|почисти", lambda t: run_fn("free_disk", {})),
            (r"(?i)карт[ау]|темы|что было на стриме|разбери стрим", lambda t: cmd_smap(None, "")),
            (r"(?i)шортс", lambda t: B.cmd_shorts(None, "")),
            (r"(?i)видео канала|список видео|наши видео", lambda t: run_fn("list_videos", {}))]
def fallback(txt):
    """Gemini is overloaded/out of quota → the most common requests by rules, the rest — commands"""
    for rx, f in RULES_FB:
        if re.search(rx, txt): B.say("🤖 (мозг перегружен, отвечаю по правилам)"); return f(txt)
    B.say("🤖 Мозг Gemini сейчас перегружен/кончилась бесплатная квота — повтори через минуту или командой: "
          "/status, /smap, /jobs, /shorts, /pubshorts 10, /check ссылка, /help.")
def chat(txt):
    if re.match(r"(?i)^\s*(вош[её]л|готово|залогинился)\s*$", txt) and B.S.get("after_login"):
        B.S["after_login"] = ""; B.save()
        B.run(["node", os.path.join(B.T, "yt", "export_cookies.js"), os.path.join(B.WORK, "cookies.txt")], cwd=B.REPO, timeout=120)
        if B.S.pop("after_login_job", None): B.save(); B.say("👍 Сессию сохранил. Повторяю то, что упало из-за входа…"); return retry_last()
        return B.say("👍 Сессию сохранил (переживёт перезапуск). Повтори последнее действие — Studio снова доступна.")
    r = load_map() or {}
    ctx = {"статус": re.sub(r"<[^>]+>", "", B.status_text())[:1500],
           "карта": [{"№": i + 1, "время": f"{F(e['from'])}-{F(e['to'])}", "название": e["title"], "частей": len(e["parts"])} for i, e in enumerate(r.get("events", []))],
           "шортсы": [f"{k + 1}. {s['line1']} / {s['line2']}" for k, s in enumerate(r.get("shorts", []))],
           "текущая_нарезка": (lambda j: f"{j}: {(B.jload(j) or {}).get('title', '')} · {(B.jload(j) or {}).get('stage', '')}" if j else "нет")(B.S.get("job")),
           "видео_канала": [f"{v['title']} — https://youtu.be/{v['id']}" for v in B.S.get("videos_cache", [])[:25]]}
    sysm = (brain.RULES + "\n\nТы — Telegram-бот-помощник владельца канала. Отвечай коротко по-русски.\n"
            "ЖЁСТКИЕ ПРАВИЛА: 1) Если просьбу можно выполнить функцией — ВЫЗОВИ её (одну), не описывай словами. "
            "2) НИКОГДА не пиши «запустил/сделал/готово», если не вызвал функцию в этом ответе. "
            "3) Названия, тексты, время — передавай В ФУНКЦИЮ ДОСЛОВНО как написал владелец, ничего не меняй. "
            "4) Удаление видео/канала и любые необратимые действия, которых нет в функциях, — не выполняешь: вежливо откажи. "
            "5) Неясно, что делать или с каким видео, — задай ОДИН уточняющий вопрос. "
            "6) На вопрос о факте (ссылка, статус, сколько частей) отвечай по фактам ниже, без выдумок.\nФакты бота:\n" + json.dumps(ctx, ensure_ascii=False))
    HIST.append({"role": "user", "content": txt}); del HIST[:-12]
    while HIST and HIST[0]["role"] != "user": HIST.pop(0)
    try: msg = brain.ask([{"role": "system", "content": sysm}] + HIST, tools=tools(), temperature=0.2, timeout=45)
    except Exception:
        HIST.pop()                                                   # no unanswered question left in the history
        return fallback(txt)
    calls = msg.get("tool_calls") or []
    if calls:
        c = calls[0]["function"]; name = c["name"]
        try: args = json.loads(c.get("arguments") or "{}")
        except Exception: args = {}
        B.blog("smart_call", q=txt[:300], fn=name, args=args)
        HIST.append({"role": "assistant", "content": None, "tool_calls": [calls[0]]})
        HIST.append({"role": "tool", "tool_call_id": calls[0].get("id", "c0"), "content": "принято, выполняю" + (" после подтверждения владельца" if name in HEAVY else "")})
        if name in HEAVY:
            return confirm(name, args)
        return run_fn(name, args)
    ans = (msg.get("content") or "").strip() or "Не понял — переформулируй, пожалуйста."
    fake = re.search(r"\[вызов (\w+) (\{.*?\})\]", ans)                 # the model wrote a call as text
    if fake and fake[1] in dict((n, 1) for n, _, _ in FUNCS):
        args = json.loads(fake[2]); name = fake[1]
        return confirm(name, args) if name in HEAVY else run_fn(name, args)
    HIST.append({"role": "assistant", "content": ans}); B.blog("smart_chat", q=txt[:300], a=ans[:500]); B.say("🤖 " + md(ans))

# ----------------------------------------------------------------- self-healing (the owner wants it to «работать бесперебойно»)
RETRY = {}                                                     # name → (fn, args) of failed jobs, for «повтори»
def cleanup(keep_job=None):
    """free disk: intermediates of every job (sources, renders, parts), finals of published jobs, VOD video segments"""
    freed = 0; root = os.path.join(B.WORK, "jobs")
    for j in os.listdir(root) if os.path.isdir(root) else []:
        if j == keep_job: continue
        d = os.path.join(root, j); jd = B.jload(j) or {}
        names = ["ev.mp4", "out.mp4", "src.mp4", "final_o.mp4", "final_nomm.mp4"] + (["final.mp4", "preview.mp4"] if jd.get("stage") == "published" else [])
        for n in names + [x for x in os.listdir(d) if x.endswith(".parts") or x.endswith(".segs")]:
            p = os.path.join(d, n)
            if os.path.exists(p):
                freed += sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(p) for f in fs) if os.path.isdir(p) else os.path.getsize(p)
                subprocess.run(["rm", "-rf", p])
    for v in [x for x in os.listdir(B.WORK) if x.startswith("vod")]:
        for n in ("video.mp4.segs", "audio.wav"):
            p = os.path.join(B.WORK, v, n)
            if os.path.exists(p): subprocess.run(["rm", "-rf", p]); freed += 1
    return freed / 2**30

NET = re.compile(r"(?i)timed? ?out|connection (reset|refused|aborted)|temporary failure|HTTP Error 5\d\d|50[234]|ECONNRESET|EAI_AGAIN|RemoteDisconnected|IncompleteRead")
def heal(name, fn, args, ex, tries=0):
    """called by the bot's job worker on any failure: fix what can be fixed automatically, then retry; else explain"""
    err = str(ex); RETRY[name] = (fn, args); B.S["last_failed"] = name; B.save()
    def again(why, delay=0):
        B.say(f"🛠 <b>{B.esc(name)}</b>: {B.esc(why)} — исправил, повторяю{' через ' + str(delay // 60) + ' мин' if delay >= 60 else ''}.")
        threading.Thread(target=lambda: (time.sleep(delay), B.bg(name + " (повтор)", fn, *args)), daemon=True).start()
    if tries >= 2: return explain(name, err)
    if "мало места" in err or "No space left" in err:
        gb = cleanup(); return again(f"кончилось место, освободил {gb:.1f} ГБ")
    if err == "LOGIN" or "LOGIN" in err or "accounts.google.com" in err:
        B.S["after_login_job"] = name; B.save(); return need_login(name)
    if "UPLOAD_LIMIT" in err or err.strip() == "LIMIT":
        B.S.setdefault("deferred", []).append({"at": time.time() + 24 * 3600 + 600, "job": B.S.get("job"), "what": "publish"}); B.save()
        return B.say(f"⛔ Суточный лимит загрузок YouTube. Сам повторю публикацию через 24 ч (переживёт перезапуск сервера).")
    if "9222" in err and ("ECONNREFUSED" in err or "connectOverCDP" in err):
        subprocess.run(["pkill", "-f", "remote_browser.js"]); time.sleep(2); B.panel_start(); return again("браузер Studio упал — перезапустил")
    if NET.search(err): return again("сбой сети/сервиса", 90 * (tries + 1))
    return explain(name, err)
def explain(name, err):
    txt = f"❌ <b>{B.esc(name)}</b>: <code>{B.esc(err[:500])}</code>"
    if brain.available():
        try:
            a = brain.ask([{"role": "system", "content": "Ты инженер видео-конвейера (ffmpeg, yt-dlp, playwright/YouTube Studio, Python). Объясни владельцу "
                            "по-русски в 2–3 коротких предложениях: что сломалось и что сделать (команда бота или действие). Без воды."},
                           {"role": "user", "content": f"Задача: {name}\nОшибка:\n{err[-2500:]}"}], timeout=60)
            txt += "\n🤖 " + md(a.get("content", ""))
        except Exception: pass
    B.say(txt + "\nПовторить: напиши «повтори».")
def retry_last():
    n = B.S.get("last_failed"); f = RETRY.get(n)
    if not f: return B.say("Нечего повторять (после перезапуска сервера — повтори командой).")
    B.bg(n + " (повтор)", f[0], *f[1])
def deferred_loop():
    while True:
        time.sleep(600)
        due = [d for d in B.S.get("deferred", []) if d["at"] <= time.time()]
        if not due: continue
        B.S["deferred"] = [d for d in B.S["deferred"] if d["at"] > time.time()]; B.save()
        for d in due:
            if d["what"] == "publish" and d.get("job"):
                B.S["job"] = d["job"]; B.save(); B.say(f"⏰ Повторяю отложенную публикацию {d['job']}."); B.publish_do(None, "")

# ----------------------------------------------------------------- channel videos: find by words, title / cover / music
def videos(shorts=False):
    ok, out = node("studio_list.js", B.CHANNEL, *(["--shorts"] if shorts else []), timeout=300)
    vs = [dict(zip(("id", "dur", "title"), [x.strip() for x in l.split("|", 2)])) for l in out.splitlines() if re.match(r"^[A-Za-z0-9_-]{11} \|", l)] if ok else []
    if vs and not shorts: B.S["videos_cache"] = vs[:40]; B.save()                  # the chat knows the channel's videos
    return vs
def find_video(q):
    ids = re.findall(r"(?:youtu\.be/|v=|shorts/|^)([A-Za-z0-9_-]{11})(?:\b|$)", q.strip())
    if ids and len(q.strip()) <= 60 and " " not in q.strip(): return {"id": ids[-1], "title": ids[-1]}
    vs = videos() + videos(True)
    if not vs: return None
    if brain.available():
        r = brain.ask_json("Выбери видео канала по описанию владельца. Верни JSON {\"id\":\"...\"} или {\"id\":\"\"}.",
                           f"Описание: {q}\nВидео:\n" + "\n".join(f"{v['id']} | {v['title']}" for v in vs[:80]), timeout=60)
        v = next((x for x in vs if x["id"] == r.get("id")), None)
        if v: return v
    words = [w for w in re.findall(r"\w+", q.lower()) if len(w) > 2]
    best = max(vs, key=lambda v: sum(w in v["title"].lower() for w in words)); return best if any(w in best["title"].lower() for w in words) else None
def set_video_title(q, title):
    v = find_video(q); t = brain.clean_title(title)
    if not v: return B.say("Не нашёл такое видео на канале — уточни название или пришли ссылку.")
    if not t: return B.say("Название пустое или с матом/«18+» — так нельзя (риск 18+).")
    p = os.path.join(B.WORK, "edit_title.json"); json.dump([{"id": v["id"], "title": t}], open(p, "w"), ensure_ascii=False)
    ok, out = node("studio_edit.js", p); B.say(f"✅ Название «{B.esc(v['title'][:60])}» → <b>{B.esc(t)}</b>" if ok else f"❌ <code>{B.esc(out[-300:])}</code>")
def ask_cover(q):
    v = find_video(q)
    if not v: return B.say("Не нашёл такое видео на канале — уточни название или пришли ссылку.")
    B.S["await_cover_for"] = v; B.save(); B.say(f"🖼 Пришли новую обложку фото — поставлю на «{B.esc(v['title'][:80])}» (https://youtu.be/{v['id']}).")
def apply_cover(path):
    v = B.S.pop("await_cover_for", None); B.save()
    p = os.path.join(B.WORK, "edit_thumb.json"); json.dump([{"id": v["id"], "thumb": path}], open(p, "w"))
    ok, out = node("studio_edit.js", p); B.say(f"✅ Обложка заменена: https://youtu.be/{v['id']}" if ok else f"❌ <code>{B.esc(out[-300:])}</code>")

# ----------------------------------------------------------------- re-generation and intro styles
def regenerate(j=None):
    j = j or B.S.get("job"); jd = B.jload(j) if j else None
    if not jd or not jd.get("smart"): return B.say("Нет текущей нарезки для перегенерации (/jobs).")
    jd["stage"] = "queued"; B.jsave(j, jd)
    for f in ("final.mp4", "preview.mp4"):
        try: os.remove(os.path.join(B.jdir(j), f))
        except FileNotFoundError: pass
    B.bg(f"перегенерация {j}", smart_job, j)
PRESETS = {"neon": "неон (пурпур/бирюза)", "brand": "фирменный синий+жёлтый", "cold": "холодный", "warm": "тёплый", "pastel": "пастель", "mono_pop": "монохром с акцентом"}
def new_intro(preset):
    preset = preset if preset in PRESETS else "brand"; d = os.path.join(B.WORK, "intro"); os.makedirs(d, exist_ok=True)
    src = os.path.join(B.REPO, "assets", "intro", "intro_t2x2.mp4"); out = os.path.join(d, f"intro_{preset}.mp4")
    ok, o = B.run(["bash", os.path.join(B.T, "intro", "tint_intro.sh"), preset, src, out], timeout=900)
    if not ok: raise RuntimeError("интро: " + o)
    B.send_file(out, f"Интро «{PRESETS[preset]}» — ставить основным?", "video")
    B.ask(f"Сделать интро «{PRESETS[preset]}» основным для новых видео?", use_intro, out)
def use_intro(path):
    B.S["intro_override"] = path; B.save(); os.environ["INTRO_FILE"] = path
    B.say("✅ Новое интро будет в следующих нарезках.")

# chat tools for all of the above
FUNCS += [
    ("set_video_title", "поменять название видео, которое УЖЕ на канале (есть в списке «видео_канала»; видео — ссылка или слова из названия)", {"video": {"type": "string"}, "title": {"type": "string"}}),
    ("replace_cover", "поменять обложку видео, которое УЖЕ на канале (потом владелец пришлёт фото)", {"video": {"type": "string"}}),
    ("list_videos", "показать последние видео канала", {}),
    ("regenerate", "перегенерировать (заново отрендерить) текущую нарезку", {}),
    ("new_intro", "сделать интро в другом современном оттенке: neon, brand, cold, warm, pastel, mono_pop", {"preset": {"type": "string"}}),
    ("free_disk", "освободить место на диске сервера", {}),
    ("retry_last", "повторить последнюю упавшую задачу", {}),
]
HEAVY |= {"set_video_title", "regenerate"}
RU.update({"set_video_title": lambda a: f"переименовать видео «{a.get('video', '')}» → «{a.get('title', '')}»",
           "regenerate": lambda a: "заново отрендерить текущую нарезку"})
_run_fn0 = run_fn
def run_fn(name, a):
    if name == "set_video_title": return B.bg("переименование видео", set_video_title, a.get("video", ""), a.get("title", ""))
    if name == "replace_cover": return B.bg("поиск видео для обложки", ask_cover, a.get("video", ""))
    if name == "list_videos":
        vs = videos(); return B.say("\n".join(f"• {B.esc(v['title'][:80])} — https://youtu.be/{v['id']}" for v in vs[:15]) or "Не смог открыть список (Studio?)")
    if name == "regenerate": return regenerate()
    if name == "new_intro": return B.bg("новое интро", new_intro, a.get("preset", "brand"))
    if name == "free_disk": return B.say(f"🧹 Освободил {cleanup():.1f} ГБ. {B.disk()}")
    if name == "retry_last": return retry_last()
    return _run_fn0(name, a)

# ----------------------------------------------------------------- the agent with its own cloud Linux (agent.py)
AGENT = {"cur": None}
def agent_task(task):
    import agent
    if AGENT["cur"] and not AGENT["cur"].stop: return B.say("🧑‍💻 Агент уже занят задачей — «стоп», чтобы остановить.")
    def work():
        try:
            a = agent.Agent(task, say=B.say, send=lambda p, c: B.send_file(p, c, "video" if p.endswith(".mp4") else ("photo" if p.endswith((".png", ".jpg")) else "document")))
            AGENT["cur"] = a; B.blog("agent_start", task=task[:500], mode=a.sb.mode); r = a.run()
            B.blog("agent_end", result=str(r)[:800], steps=len(a.log))
        except Exception as ex:
            B.blog("error", where="agent", err=str(ex)[:500], tb=traceback.format_exc()[-1500:]); B.say(f"❌ Агент: <code>{B.esc(str(ex)[:300])}</code>")
        finally:
            if AGENT["cur"]: AGENT["cur"].stop = True
    threading.Thread(target=work, daemon=True).start()
def agent_stop():
    if AGENT["cur"] and not AGENT["cur"].stop: AGENT["cur"].stop = True; return B.say("⏹ Останавливаю агента после текущего шага.")
    B.say("Агент сейчас ничем не занят.")
FUNCS += [("agent_task", "сложная задача для агента со своим облачным Linux: написать/отладить код, смонтировать/сгенерировать видео или "
           "картинку ffmpeg/python, исследовать проблему, сделать скриншоты — всё, чего нет в других функциях", {"task": {"type": "string"}}),
          ("agent_stop", "остановить агента", {})]
HEAVY |= {"agent_task"}
RU["agent_task"] = lambda a: f"поручить агенту (свой Linux): {a.get('task', '')[:200]}"
_run_fn1 = run_fn
def run_fn(name, a):
    if name == "agent_task": return agent_task(a.get("task", ""))
    if name == "agent_stop": return agent_stop()
    return _run_fn1(name, a)
_chat0 = chat
def chat(txt):
    if re.match(r"(?i)^\s*(стоп|stop|хватит)\s*!?\s*$", txt) and AGENT["cur"] and not AGENT["cur"].stop: return agent_stop()
    return _chat0(txt)
