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
    if rows and rows[0][0] != 0: rows[0] = (0, rows[0][1])
    if len(rows) < 3: return ""                                  # YouTube needs ≥ 3 chapters → skip if the model gave fewer
    return "\n".join(f"{F(o)} - {t}" for o, t in rows if o < dur - 10)

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
    env = {"MAT": "1"}
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
    ("set_title", "поменять название текущей нарезки", {"title": {"type": "string"}}),
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
def chat(txt):
    if re.match(r"(?i)^\s*(вош[её]л|готово|залогинился)\s*$", txt) and B.S.get("after_login"):
        B.S["after_login"] = ""; B.save()
        B.run(["node", os.path.join(B.T, "yt", "export_cookies.js"), os.path.join(B.WORK, "cookies.txt")], cwd=B.REPO, timeout=120)
        return B.say("👍 Сессию сохранил (переживёт перезапуск). Повтори последнее действие — Studio снова доступна.")
    r = load_map() or {}
    ctx = {"статус": re.sub(r"<[^>]+>", "", B.status_text())[:1500],
           "карта": [{"№": i + 1, "время": f"{F(e['from'])}-{F(e['to'])}", "название": e["title"], "частей": len(e["parts"])} for i, e in enumerate(r.get("events", []))],
           "шортсы": [f"{k + 1}. {s['line1']} / {s['line2']}" for k, s in enumerate(r.get("shorts", []))]}
    sysm = (brain.RULES + "\n\nТы — Telegram-бот-помощник владельца канала. Отвечай коротко по-русски. Если просьбу можно выполнить "
            "функцией — вызови её (одну). Если неясно — уточни вопросом. Факты бота:\n" + json.dumps(ctx, ensure_ascii=False))
    HIST.append({"role": "user", "content": txt}); del HIST[:-12]
    while HIST and HIST[0]["role"] != "user": HIST.pop(0)
    msg = brain.ask([{"role": "system", "content": sysm}] + HIST, tools=tools(), temperature=0.3, timeout=120)
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
