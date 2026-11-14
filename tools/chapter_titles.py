"""Chapter titles WITHOUT an LLM, in the style of docs/timecodes.md (reference: «Во сколько начнем играть в GTA VI»,
«Благодаря ЧАТУ будет играть в GTA VI раньше»): extract the most telling phrase of the chapter and clean it.

title(lines, context_lines) -> str
  lines         — [(s, e, text)] of the chapter
  context_lines — the whole event (for keyword weighting: words frequent in THIS chapter but not everywhere)
Steps: candidate phrases (sentences 3–11 words) → score: chapter-specific keywords (tf-idf vs the event), story/
emotion words, question/exclamation, position (opening phrases of a chapter state its topic), penalties for donation
reading, greetings, 18+ words, garbage (whisper noise) → clean: drop mat and fillers, fix the first letter, «?!» kept,
≤ 60 chars → if the phrase has no topic keyword, add it in «( … )» like the reference.
CLI: python3 chapter_titles.py transcript.txt FROM TO  (prints a title)"""
import re, math, collections, sys, os
sys.path.insert(0, os.path.dirname(__file__))
import auto_events as AE

MAT = re.compile(r"(?i)\b(бля\w*|блят\w*|блядь\w*|бляд\w*|ебать|ебан\w*|ёбан\w*|еба\w*|нахуй|нахуя|хуй\w*|хуе\w*|хуя\w*|пизд\w*|сук[аи]\w*|"
                 r"охуе\w*|похуй|блин\w*|заеб\w*|выеб\w*|долбо\w*|мудак\w*|мудил\w*|залуп\w*|ебл\w*|ёб\w*|ебт\w*|бл\b)")
FILL = re.compile(r"(?i)\b(короче|типа|ну|вот|значит|как бы|то есть|слушай|смотри|кстати|в общем|получается|"
                  r"собственно|просто|прям|реально|ага|ой|ох|эх|ммм|э+|а+)\b[,.]?")
BAD = re.compile(r"(?i)(привет,?\s*(тош|антон|тох)|донат|рубл|подпис|спасибо за|передай привет|день рождени|"
                 r"зрители|подписывайтесь|ставьте лайк)")

def clean(t):
    t = MAT.sub("", t); t = FILL.sub("", t)
    t = re.sub(r"\s+([,.!?])", r"\1", t); t = re.sub(r"[,]{2,}", ",", t); t = re.sub(r"\s{2,}", " ", t)
    t = re.sub(r"^[\s,.\-—–:;!?]+", "", t).strip(" ,;-—")
    t = re.sub(r"(\b\w+\b)(,?\s+\1\b)+", r"\1", t, flags=re.I)        # «реально, реально» → «реально»
    t = re.sub(r"(?i)\b(\w+\s+\w+(?:\s+\w+)?)\b(.*?)\b\1\b", r"\1\2", t)   # repeated 2–3-word phrase
    if not t: return ""
    t = t[0].upper() + t[1:]
    t = re.sub(r",\s*!", "!", t); t = re.sub(r"[.,;:\-—]+$", "", t).strip()
    return t[:60].rsplit(" ", 1)[0] if len(t) > 60 else t

def sentences(lines):
    out = []
    for i, (s, e, t) in enumerate(lines):
        for p in re.split(r"(?<=[.!?])\s+", t):
            w = re.findall(r"[А-Яа-яЁёA-Za-z0-9]+", p)
            if 3 <= len(w) <= 14: out.append((i, p.strip()))
    return out

def title(lines, context_lines):
    if not lines: return "Начало"
    ctx = collections.Counter(w for _, _, t in context_lines for w in set(AE.words(t)))
    nctx = max(1, len(context_lines))
    loc = collections.Counter(w for _, _, t in lines for w in AE.words(t))
    tfidf = {w: c * math.log(1 + nctx / (1 + ctx[w])) for w, c in loc.items() if len(w) >= 4}
    top = sorted(tfidf, key=lambda w: -tfidf[w])[:6]
    best, bs = None, -1e9
    for i, p in sentences(lines):
        ws = AE.words(p)
        if not ws: continue
        sc = sum(tfidf.get(w, 0) for w in ws) / (len(ws) ** 0.5)
        sc += 1.5 * bool(re.search(r"\?", p)) + 0.8 * bool(re.search(r"!", p)) + 1.0 * bool(AE.REACT.search(p))
        sc += 2.2 * bool(re.search(r"(?i)(письм|почт|сын|папочк|щен|кран|автопилот|финал|концовк|последн|вагон|план|родин|"
                                   r"лидер|сопротивл|первый|новый|купил|украл|воров|спизд|фронт|комиссар|министерств)", p))
        sc += 2.0 * bool(re.search(r"\d", p))                               # «25 копеек», «20 копеек»
        sc -= 1.2 * bool(re.search(r"(?i)\b(это|что)\b.*\b\1\b", p))
        sc -= 4 * bool(re.search(r"(?i)\w(бля|хуй|пизд)|(бля|хуй|пизд)\w*[а-я]{4,}", p.replace(" ", " ")) and re.search(r"(?i)[а-я](бля|хуй|пизд)", p))  # whisper glued mat
        sc -= 2 * (len(re.findall(r",", p)) > 3)
        sc -= 0.04 * i                                                     # the topic is usually stated early
        sc -= 6 * bool(BAD.search(p)) + 6 * bool(AE.RISK.search(p)) + 3 * bool(re.match(r"(?i)\W*привет", p))
        cl = clean(p); nw = len(cl.split())
        if nw < 3: continue
        sc -= 1.5 * (nw > 10) + 1.0 * (nw < 4) + 2 * bool(re.search(r"(\w)\1{3,}", cl))     # long / short / whisper noise
        if sc > bs: bs, best = sc, cl
    if not best: best = clean(lines[0][2]) or "Начало"
    kw = next((w for w in top if w not in best.lower()), None)
    if kw and not any(w[:5] in best.lower() for w in top[:3]):
        full = next((m.group(0) for _, _, t in lines for m in re.finditer(r"[А-Яа-яЁёA-Za-z]+", t) if m.group(0).lower().startswith(kw)), kw)
        cand = f"{best} ( {full.lower()} )"
        if len(cand) <= 70: best = cand
    return best

if __name__ == "__main__":
    tr, a, b = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
    L = AE.load(tr, a - 600, b + 600); part = [l for l in L if a <= l[0] < b]
    print(title(part, L))
