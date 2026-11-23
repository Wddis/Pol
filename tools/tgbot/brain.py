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
MODELS = [m.strip() for m in os.environ.get("GEMINI_MODELS", "gemini-3.5-flash,gemini-3.8-flash,gemini-3.7-flash,gemini-flash-latest").split(",") if m.strip()]

def available(): return bool(os.environ.get("GEMINI_API_KEY"))

def _post(body, timeout=300):
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), method="POST", headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + os.environ.get("GEMINI_API_KEY", "")})
    return json.load(urllib.request.urlopen(req, timeout=timeout))

def ask(messages, tools=None, json_mode=False, temperature=0.4, timeout=300):
    """returns the assistant message dict ({"content":…, "tool_calls":[…]}). Tries the models in order on overload/quota."""
    err = None
    for m in MODELS:
        body = {"model": m, "messages": messages, "temperature": temperature}
        if tools: body["tools"] = tools
        if json_mode: body["response_format"] = {"type": "json_object"}
        for attempt in range(2):
            try:
                r = _post(body, timeout)
                msg = r["choices"][0]["message"]; msg["_model"] = m; return msg
            except urllib.error.HTTPError as e:
                txt = e.read().decode("utf-8", "replace")[:300]; err = f"{m}: HTTP {e.code} {txt}"
                if e.code in (429, 503, 500): time.sleep(4 * (attempt + 1)); continue   # overload / quota → retry, then next model
                if e.code in (404, 400) and "no longer available" in txt or e.code == 404: break
                raise RuntimeError(err)
            except Exception as e:                                   # timeouts, network
                err = f"{m}: {e}"; time.sleep(3)
    raise RuntimeError("Gemini недоступен: " + (err or "?"))

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
