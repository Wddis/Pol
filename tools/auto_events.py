"""Find candidate events (topics) in a stream transcript WITHOUT an LLM — for the Telegram bot.
The owner still picks/approves events; this only proposes them with a score and the opening phrases.

usage: python3 auto_events.py transcript.txt [--from SEC] [--to SEC] [--min 180] [--max 2400] [--json out.json]
transcript lines: "  123.4-  128.9 text" (tools/stt.py / live_update.sh format)

Method (heuristics, tuned on T2x2 streams 08–12.11.2026):
- topic boundaries: long silence (> 25 s without speech) OR a drop of lexical similarity between the previous and the
  next 90 s of speech (bag of word stems, cosine) OR explicit switch phrases («так, ну что», «давайте посмотрим»,
  «следующее», «включаю», «поехали»...)
- event score: speech density, reaction markers (!, ?!, laughter «ахах/хаха», «чё/что за/как так/пиздец/офигеть»),
  questions to chat, length bonus 5–30 min; donation reading lowers the score (it's cut anyway)
- each event: start/end (snapped to speech), duration, score, keywords, first & last phrases."""
import re, sys, json, math, argparse, collections

STOP = set("и в во не на я ты он она оно мы вы они что это как так вот ну да нет а но же ли бы то там тут уже еще ещё "
           "блять бля блин нахуй пиздец сука это-то типа короче просто вообще значит ага ой ох эх у к с со по за от до из "
           "для о об при про над под без мне меня тебя тебе его ее её их им нам вам себе свой мой твой наш ваш кто где "
           "когда зачем почему чё че чего сейчас вот-вот можно надо нужно будет был была было были есть всё все весь".split())
SWITCH = re.compile(r"(?i)^(так,? ну что|ну что,? (давайте|посмотрим|погнали)|давайте посмотрим|следующ|включаю|поехали|"
                    r"а теперь|переходим|ладно,? (всё|все|давайте)|так,? (давайте|что у нас))")
REACT = re.compile(r"(?i)(\?!|!|ахах|хаха|ахаха|пиздец|офигеть|охуеть|ёбан|ебан|что за|как так|серьёзно|серьезно|чё\?|что\?)")
RISK = re.compile(r"(?i)(порн|парнух|секс|дроч|встр\b|школьниц|классниц|несовершеннолет|голая|голые|сиськ|член|минет|"
                  r"казино|ставк|фонбет|fonbet|промокод|наркот|закладк)")      # 18+ triggers (docs/no-yellow-card.md)
DONAT = re.compile(r"(?i)(спасибо за донат|первый донат|донат|рублей|задонатил|спасибо,? \w+,? за)")

def stem(w): return w[:6]
def words(t): return [stem(w) for w in re.findall(r"[а-яёa-z0-9]+", t.lower()) if len(w) > 2 and w not in STOP]

def load(path, a=0, b=1e9):
    out = []
    for l in open(path, encoding="utf-8"):
        m = re.match(r"\s*([\d.]+)-\s*([\d.]+)\s(.*)", l)
        if not m: continue
        s, e, t = float(m[1]), float(m[2]), m[3].strip()
        if a <= s <= b and t and not re.fullmatch(r"(\w+[-,\s]*)\1{6,}", t): out.append((s, e, t))
    out.sort(); dedup = []
    for x in out:                                                       # batched whisper can repeat a line
        if dedup and abs(dedup[-1][0] - x[0]) < 1.0 and dedup[-1][2][:20] == x[2][:20]: continue
        dedup.append(x)
    return dedup

def cos(c1, c2):
    num = sum(c1[k] * c2.get(k, 0) for k in c1); den = math.sqrt(sum(v * v for v in c1.values()) * sum(v * v for v in c2.values()))
    return num / den if den else 0.0

def boundaries(L, win=90.0):
    cuts = set()
    for i in range(1, len(L)):
        if L[i][0] - L[i - 1][1] > 25: cuts.add(i)
        if SWITCH.search(L[i][2]): cuts.add(i)
    # lexical drop every ~30 s
    i = 0
    while i < len(L):
        t = L[i][0]
        prev = collections.Counter(w for s, e, x in L if t - win <= s < t for w in words(x))
        nxt = collections.Counter(w for s, e, x in L if t <= s < t + win for w in words(x))
        if sum(prev.values()) > 25 and sum(nxt.values()) > 25 and cos(prev, nxt) < 0.06: cuts.add(i)
        j = i
        while j < len(L) and L[j][0] < t + 30: j += 1
        i = max(j, i + 1)
    return sorted(cuts)

def events(L, mn=180, mx=2400):
    cuts = [0] + boundaries(L) + [len(L)]
    segs = [(cuts[k], cuts[k + 1]) for k in range(len(cuts) - 1) if cuts[k + 1] > cuts[k]]
    merged = []                                                         # glue tiny pieces to neighbours
    for a, b in segs:
        if merged and (L[b - 1][1] - L[a][0] < mn * 0.5 or L[merged[-1][1] - 1][1] - L[merged[-1][0]][0] < mn * 0.5):
            merged[-1] = (merged[-1][0], b)
        else: merged.append((a, b))
    out = []
    for a, b in merged:
        part = L[a:b]; st, en = part[0][0], part[-1][1]; dur = en - st
        if dur < mn: continue
        if dur > mx:                                                    # split very long ones evenly
            n = math.ceil(dur / mx); step = dur / n
            for q in range(n):
                sub = [x for x in part if st + q * step <= x[0] < st + (q + 1) * step]
                if sub: out.append(score(sub))
            continue
        out.append(score(part))
    return sorted(out, key=lambda e: e["start"])

def score(part):
    st, en = part[0][0], part[-1][1]; dur = max(1.0, en - st)
    speech = sum(e - s for s, e, _ in part) / dur
    react = sum(len(REACT.findall(t)) for _, _, t in part) / (dur / 60)
    don = sum(1 for _, _, t in part if DONAT.search(t)) / (dur / 60)
    lenb = 1.0 if 300 <= dur <= 1800 else (0.6 if dur < 300 else 0.8)
    risk = [t for _, _, t in part if RISK.search(t)]
    sc = round((speech * 4 + min(react, 6) * 1.2 - min(don, 3) * 1.0) * lenb - 3.0 * len(risk), 2)
    kw = collections.Counter(w for _, _, t in part for w in words(t)).most_common(8)
    return {"start": round(st, 1), "end": round(en, 1), "dur_min": round(dur / 60, 1), "score": sc,
            "keywords": [k for k, _ in kw], "first": " / ".join(t for _, _, t in part[:3])[:220],
            "last": " / ".join(t for _, _, t in part[-2:])[:160], "risk": [r[:80] for r in risk[:3]]}

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("tr"); ap.add_argument("--from", dest="a", type=float, default=0)
    ap.add_argument("--to", dest="b", type=float, default=1e9); ap.add_argument("--min", type=float, default=180)
    ap.add_argument("--max", type=float, default=2400); ap.add_argument("--json")
    x = ap.parse_args(); ev = events(load(x.tr, x.a, x.b), x.min, x.max)
    if x.json: json.dump(ev, open(x.json, "w"), ensure_ascii=False, indent=1)
    for i, e in enumerate(ev, 1):
        print(f"#{i} {int(e['start']//60)}–{int(e['end']//60)} мин ({e['dur_min']} мин) score {e['score']} | {', '.join(e['keywords'][:5])}\n   «{e['first'][:150]}»")
