"""Light AI for the bot (no API key). Reliability first — tested 14.11 on a live stream:
a 3B model on CPU invents commands, so it is NOT allowed to build commands. Instead:
  • understanding requests = deterministic NLU: action words + topic reference (number, time range, or words
    searched in the topics' transcript) → the bot proposes ONE exact command and confirms it with ✅/✖️;
  • state questions («что делаешь», «где нарезки», «почему стоит») → answered from the bot's real state;
  • the local model (Qwen2.5-3B GGUF via llama-cpp, env ASSIST_MODEL) only SUMMARIZES a found topic in 1–2
    sentences, or answers a general question from the facts (no commands).
API: answer(text, topics, lines, facts) -> (reply_html_or_None, rule_key_or_None, command_or_None)
  topics — events.json list; lines — transcript [(s, e, text)] (for search/summary)"""
import os, re, json, threading, collections, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import auto_events as AE

MODEL = os.environ.get("ASSIST_MODEL", "")
_llm = None; _lock = threading.Lock()

STATE_RULES = [
    (r"(что|чем) (ты )?(сейчас )?(делаешь|занят)|что происходит|статус|^как дела\??$|ты тут|ты жив", "status"),
    (r"где.*нарезк|какие нарезк|что с нарезк|нарезки есть|готов[аоы]? ли", "status"),
    (r"почему.*(стоит|не ид[её]т|завис|медлен|не двига|молчит)|застрял|не работает|ошибк|сломал", "problem"),
    (r"что (ты )?умеешь|помощь|команды|как (тобой )?пользоваться|что нажимать|инструкц", "help"),
]
ACT_CUT = r"(нарежь|нарезать|нарезку|вырежи|сделай (видео|ролик)|смонтируй)"
ACT_MAP = r"(покажи|какие|список|все).*(тем)|о ч[её]м (был )?стрим|что было на стриме"
ACT_STOP = r"(останови|стоп|хватит|выключи).*(эфир|нарезк|слежени)|^стоп$"
ACT_BEST = r"(лучш|самая|топ).*(тем)|сколько тем"
STOPW = set("про о об тему темы тема нарежь нарезать нарезку вырежи сделай видео ролик когда была был было что как где "
            "покажи мне пожалуйста давай момент момента моменты кусок стрима стрим нибудь что-нибудь чтонибудь любую любой "
            "какую-нибудь лучшее лучшую интересное интересную смешное".split())

def parse_t(s):
    p = [float(x) for x in s.split(":")]
    return p[0] * 60 if len(p) == 1 else (p[0] * 60 + p[1] if len(p) == 2 else p[0] * 3600 + p[1] * 60 + p[2])
def fmt(t): t = int(t); return f"{t // 3600}:{t % 3600 // 60:02d}:{t % 60:02d}" if t >= 3600 else f"{t // 60}:{t % 60:02d}"

def find_topic(text, topics, lines):
    """topic by number («тему 11»), by time («в 1:30», «с 1:30 до 1:38»), or by words searched in each topic's speech"""
    t = text.lower()
    m = re.search(r"тем[уаы]?\s*(?:№|#|номер)?\s*(\d{1,3})\b", t)
    if m and 1 <= int(m[1]) <= len(topics): return topics[int(m[1]) - 1], f"тема №{m[1]}"
    m = re.search(r"(\d{1,2}:\d{2}(?::\d{2})?)\s*(?:-|до|по)\s*(\d{1,2}:\d{2}(?::\d{2})?)", t)
    if m: return {"start": parse_t(m[1]), "end": parse_t(m[2]), "dur_min": round((parse_t(m[2]) - parse_t(m[1])) / 60, 1), "first": ""}, "по времени"
    m = re.search(r"\b(\d{1,2}:\d{2}(?::\d{2})?)\b", t)
    if m:
        x = parse_t(m[1]); hit = next((e for e in topics if e["start"] <= x < e["end"]), None)
        if hit: return hit, f"тема, где {m[1]}"
    q = [AE.stem(w) for w in re.findall(r"[а-яёa-z0-9]+", t) if len(w) > 3 and w not in STOPW]
    if not q: return None, ""
    best, bs = None, 0
    for e in topics:
        words = collections.Counter(AE.stem(w) for s, _, x in lines if e["start"] <= s < e["end"] for w in re.findall(r"[а-яёa-z0-9]+", x.lower()))
        sc = sum(min(words[w], 5) for w in q) + 0.01 * e.get("score", 0)
        if sc > bs: best, bs = e, sc
    return (best, "по словам: " + ", ".join(q)) if bs >= 1 else (None, "")

def _load():
    global _llm
    if _llm is None and MODEL and os.path.exists(MODEL):
        from llama_cpp import Llama
        _llm = Llama(model_path=MODEL, n_ctx=4096, n_threads=int(os.environ.get("ASSIST_THREADS", 3)), verbose=False)
    return _llm
def preload(): threading.Thread(target=_load, daemon=True).start()
def llm(system, user, n=160):
    m = _load()
    if not m: return ""
    with _lock:
        out = m.create_chat_completion(messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
                                       max_tokens=n, temperature=0.2)
    return out["choices"][0]["message"]["content"].strip()

def summary(e, lines):
    txt = " ".join(x for s, _, x in lines if e["start"] <= s < e["end"])[:3500]
    r = llm("Ты кратко пересказываешь кусок стрима T2x2 по расшифровке (в ней ошибки распознавания). 1–2 предложения "
            "по-русски, без мата, только о чём говорили. Не выдумывай.", txt, 80)
    import chapter_titles as CT
    r = CT.MAT.sub("", r); r = re.sub(r"\s{2,}", " ", r)
    r = " ".join(re.split(r"(?<=[.!?])\s+", r)[:2])[:260]
    return r or ("«" + (e.get("first") or txt[:150]) + "…»")

def answer(text, topics, lines, facts):
    t = text.lower().strip()
    for rx, k in STATE_RULES:
        if re.search(rx, t): return None, k, None
    if re.search(ACT_STOP, t): return "Остановить нарезку эфира?", None, "/stop"
    if re.search(ACT_BEST, t) and topics:
        b = max(topics, key=lambda e: e["score"])
        return (f"Тем найдено: {len(topics)}. Лучшая по оценке: №{topics.index(b) + 1} {fmt(b['start'])}–{fmt(b['end'])} "
                f"({b['dur_min']} мин, ⭐{b['score']}) — {summary(b, lines)}"), None, f"/cut {topics.index(b) + 1}"
    if re.search(ACT_MAP, t) and not re.search(ACT_CUT, t): return None, "topics", None
    want_cut = re.search(ACT_CUT, t)
    e, how = find_topic(t, topics, lines) if (topics or re.search(r"\d:\d\d", t)) else (None, "")
    if e:
        rng = f"{fmt(e['start'])}-{fmt(e['end'])}"
        desc = f"{fmt(e['start'])}–{fmt(e['end'])} ({e['dur_min']} мин; нашёл {how})"
        if want_cut: return f"Нашёл: {desc}. Нарезать?", None, f"/cut {rng}"
        return f"Это {desc}: {summary(e, lines)}", None, (f"/cut {rng}" if re.search(r"когда|где|про", t) else None)
    if want_cut and topics:
        done = topics[:-1] or topics; b = max(done, key=lambda e: e["score"])
        return (f"Конкретную тему не назвал — предлагаю лучшую законченную: №{topics.index(b) + 1} {fmt(b['start'])}–{fmt(b['end'])} "
                f"({b['dur_min']} мин, ⭐{b['score']}). Нарезать?"), None, f"/cut {fmt(b['start'])}-{fmt(b['end'])}"
    if want_cut: return "Не понял, какой кусок нарезать. Напиши номер темы (из /map), время «с 1:30:00 до 1:38:50» или слова из темы.", None, None
    r = llm("Ты помощник бота «Двойная резка» (нарезает стримы T2x2). Отвечай по-русски, 1–3 предложения, только по "
            "фактам ниже; если ответа нет — скажи и посоветуй /status или /start. Никаких команд не придумывай.",
            "ФАКТЫ:\n" + json.dumps(facts, ensure_ascii=False)[:5000] + "\n\nВОПРОС: " + text, 150)
    return (r or "Не понял вопрос. Нажми /start — меню, или /status — что происходит."), None, None
