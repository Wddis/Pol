"""«Мозг» Telegram-бота «Двойная резка» — Gemini через OpenAI-совместимый вход (owner 23.11.2026).
Ключ: env GEMINI_API_KEY (на сервере/в GitHub Actions — секрет). В облачной сессии Claude ключ подставляет прокси —
там ставь GEMINI_API_KEY=proxy-injected. Модели: GEMINI_MODELS (через запятую), по умолчанию 3.5-flash → запасные.

Что умеет:
- ask(messages, tools)         — чат с вызовом функций бота (понимание обычного текста);
- pick_events(lines, …)        — по расшифровке стрима: события/реакции, игра по миссиям, сцены 18+, шортсы с хуками;
- meta(…)                      — название (стиль канала), главы, SEO-абзац описания.
Всё, что модель вернула, бот ПРОВЕРЯЕТ (границы внутри стрима, длины, мат в названиях) — модели не доверяем вслепую.
"""
import json, os, re, time, urllib.request, urllib.error

URL = os.environ.get("GEMINI_URL", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
MODELS = [m.strip() for m in os.environ.get("GEMINI_MODELS", "gemini-3.5-flash,gemini-3.5-flash-lite,gemini-flash-lite-latest,gemini-3.1-flash-lite,gemma-4-31b-it").split(",") if m.strip()]
# free tier (tested 23.11): 3.5-flash = best; lite models = own quota; 3.6–3.8-flash = no free quota (always 429) → not used.
COOL = {}                                                         # model → time until it is skipped (429/503 cooldown)
LAST = {"ok": ""}

def available(): return bool(os.environ.get("GEMINI_API_KEY"))

def _post(body, timeout=300):
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), method="POST", headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + os.environ.get("GEMINI_API_KEY", "")})
    return json.load(urllib.request.urlopen(req, timeout=timeout))

def ask(messages, tools=None, json_mode=False, temperature=0.4, timeout=300, models=None):
    """returns the assistant message dict ({"content":…, "tool_calls":[…]}). On overload/quota/timeout it moves to the next
    model AT ONCE (no waiting) and skips that model for a minute — the owner must not wait minutes for an answer."""
    errs = []; order = models or MODELS
    for m in sorted(order, key=lambda x: COOL.get(x, 0) > time.time()):      # cooling models go last
        body = {"model": m, "messages": messages, "temperature": temperature}
        if tools: body["tools"] = tools
        if json_mode: body["response_format"] = {"type": "json_object"}
        try:
            r = _post(body, timeout)
            msg = r["choices"][0]["message"]; msg["_model"] = m; LAST["ok"] = m
            if msg.get("content"): msg["content"] = re.sub(r"(?s)<thought>.*?</thought>\s*", "", msg["content"]).strip()
            return msg
        except urllib.error.HTTPError as e:
            txt = e.read().decode("utf-8", "replace")[:200]; errs.append(f"{m}: HTTP {e.code}")
            if e.code in (429, 500, 502, 503, 504, 404): COOL[m] = time.time() + (600 if e.code == 404 else 60); continue
            if e.code == 400 and tools: continue                          # a model without tool support → next
            raise RuntimeError(f"{m}: HTTP {e.code} {txt}")
        except Exception as e:                                           # timeouts, network
            errs.append(f"{m}: {str(e)[:60]}"); COOL[m] = time.time() + 60
    raise RuntimeError("Gemini недоступен (" + "; ".join(errs[-4:]) + ")")

def ask_json(system, user, **kw):
    msg = ask([{"role": "system", "content": system}, {"role": "user", "content": user}], json_mode=True, **kw)
    txt = (msg.get("content") or "").strip()
    txt = re.sub(r"^```(json)?|```$", "", txt).strip()
    return json.loads(txt)

# ----------------------------------------------------------------------------------------------------------------
RULES = """Ты — монтажёр YouTube-канала нарезок «Двойная резка» стримера T2x2 (Антон, Twitch t2x2). Правила канала:
- Основное видео = ОДНО цельное событие стрима: реакция на конкретное видео (YouTube/TikTok) или игра. Начало и конец
  события не обрезаем. Игру длиннее ~45 мин делим на ЧАСТИ по 25–45 мин по естественным границам (миссии/смена занятия).
- Пустые донаты без реакции стримера, долгие технические паузы (настройка игры/OBS/звука без геймплея > 2 мин),
  походы в туалет — вырезать. Сцены с наготой/стрип-клубами/сексуальным контентом на экране — ВЫРЕЗАТЬ целиком (18+).
  Казино/ставки/открытие кейсов CS2 — не брать (риск 18+ и ставок).
- Название основного видео: реакция → «T2x2 СМОТРИТ: <название оригинала по-русски>»; игра → «T2x2 ИГРАЕТ В <как игру
  зовут по-русски/коротко, напр. GTA 6, СИМУЛЯТОР ЧИСТКИ КАРТОШКИ> / <полное оригинальное название, напр. Grand Theft
  Auto VI, Potato Please>» (без « #N» — номер части бот добавит сам). Без мата, без шок-слов, без «18+», ≤ 100 символов.
- Главы: первая 0:00, ≥ 3 глав, каждая ≥ 10 с, короткие живые названия по смыслу (без мата).
- Шортсы (вертикальные 20–55 с): провокационное мнение/спорный тейк стримера, на который зрители будут спорить в
  комментариях, или яркая реакция. Хук слышен в первые 2–3 с. Подпись: 2 строки КАПСОМ (1-я — тема, 2-я — вопрос/
  провокация), каждая ≤ 26 символов, без мата. Не брать: секс, наготу, ставки, оскорбления по признакам.
Время везде — в секундах стрима (как в расшифровке)."""

def compact(lines, a=0, b=1e12, max_chars=900_000):
    """[(s,e,text)] → 'S|text' lines (seconds) within [a,b], trimmed to max_chars."""
    out = [f"{int(s)}|{t}" for s, e, t in lines if a <= s <= b]
    txt = "\n".join(out)
    if len(txt) > max_chars:                                     # keep every k-th line for very long streams
        k = len(txt) // max_chars + 1; txt = "\n".join(out[::k])
    return txt

def pick_events(lines, a=0, b=1e12, hint=""):
    """Map a stream: returns {"events":[{from,to,kind,title,original?,game?,parts:[{from,to,title}],
    cut:[[a,b,why]], chapters:[[t,title]], risk}], "shorts":[{from,to,line1,line2,why}]}"""
    user = (f"Расшифровка стрима (формат: секунда|фраза). {hint}\n\nЗадача: найди ВСЕ годные основные видео (события) и "
            "лучшие шортсы. Верни JSON строго такой формы:\n"
            '{"events":[{"from":сек,"to":сек,"kind":"reaction|game|talk","title":"название по правилам",'
            '"original":"что смотрит (если реакция) — название ролика/канал","game":"игра (если игра)",'
            '"parts":[{"from":сек,"to":сек,"title":"о чём часть"}],'
            '"cut":[[сек,сек,"почему вырезать (донат/18+/тех.пауза)"]],"chapters":[[сек,"глава"]],"score":0-10,'
            '"risk":"риск 18+/ставок или пусто"}],'
            '"shorts":[{"from":сек,"to":сек,"line1":"СТРОКА 1","line2":"СТРОКА 2?","why":"чем зацепит"}]}\n'
            "parts — только для игры > 45 мин (иначе пустой список). Шортсов дай 6–12 лучших.\n\n"
            + compact(lines, a, b))
    return ask_json(RULES, user, temperature=0.3, timeout=600)

def meta(kind, title_hint, lines, a, b, original="", game="", part=None):
    """title + chapters + SEO paragraph for one finished video"""
    user = (f"Видео: {kind}; {('оригинал: ' + original) if original else ''} {('игра: ' + game) if game else ''} "
            f"{('часть ' + str(part)) if part else ''}. Черновик названия: {title_hint}\n"
            "Верни JSON: {\"title\":…, \"chapters\":[[сек_стрима, название]], \"seo\":\"2–3 предложения описания: "
            "T2x2 (Т2х2, Антон) …, нейтрально, без мата\", \"tags\":[10–15 тегов]}\n\n" + compact(lines, a, b, 300_000))
    return ask_json(RULES, user, temperature=0.4)

MAT = re.compile(r"(?i)(бля|хуй|хуе|пизд|ебат|ебан|ёбан|сука|залуп|мудак|18\+|секс|порн)")
def clean_title(t, limit=100):
    t = re.sub(r"\s+", " ", (t or "").strip())
    return "" if MAT.search(t) else t[:limit]

if __name__ == "__main__":                                       # quick self-test: python3 brain.py
    print(ask([{"role": "user", "content": "Скажи «ок»."}]).get("content"))

def locate_layout(png_path):
    """Vision: on a 1920x1080 stream frame find the webcam window and the main game/video area (for shorts crops).
    Uses Gemini's native box_2d format [ymin, xmin, ymax, xmax] on a 0–1000 scale. Returns {"cam":[x,y,w,h],"screen":[…]} px."""
    import base64
    b64 = base64.b64encode(open(png_path, "rb").read()).decode()
    msg = ask([{"role": "user", "content": [
        {"type": "text", "text": "Кадр Twitch-стрима. Найди: 1) cam — окно ВЕБКИ стримера (живой человек); 2) screen — основную "
         "область игры/видео. Верни JSON {\"cam\":{\"box_2d\":[ymin,xmin,ymax,xmax]},\"screen\":{\"box_2d\":[ymin,xmin,ymax,xmax]}} "
         "в нормированной шкале 0–1000."},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64," + b64}}]}], json_mode=True, temperature=0.0, timeout=90,
        models=["gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-flash-lite-latest"])
    txt = re.sub(r"^```(json)?|```$", "", (msg.get("content") or "").strip()).strip()
    try: r = json.loads(txt)
    except Exception: return {}
    out = {}
    for k in ("cam", "screen"):
        v = (r.get(k) or {}).get("box_2d") if isinstance(r.get(k), dict) else r.get(k)
        if not (isinstance(v, list) and len(v) == 4): continue
        y0, x0, y1, x1 = [float(t) for t in v]
        x, y, w, h = x0 * 1.92, y0 * 1.08, (x1 - x0) * 1.92, (y1 - y0) * 1.08
        if k == "cam": x, y, w, h = x + 8, y + 8, w - 16, h - 16       # strictly inside the window (no game strip)
        x, y = max(0, int(x)) // 2 * 2, max(0, int(y)) // 2 * 2
        w, h = int(min(w, 1920 - x)) // 2 * 2, int(min(h, 1080 - y)) // 2 * 2
        if w >= 160 and h >= 120: out[k] = [x, y, w, h]
    return out
