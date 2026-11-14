"""Build an edit2 plan for a VOD range WITHOUT an LLM (Telegram bot). Rules from docs/montage-style.md:
- the event is kept whole from its start to its end (no cutting of the beginning/end of the event);
- inside: cut silence > SIL seconds (nobody talks), cut repeated humming/«ооооо» lines, cut donation READING that gets
  no reaction (donation phrase and no reaction marker in the next 8 s) — conservative: when unsure, keep;
- no zoom (owner rule 12.11), screen blur boxes: donation widget + FONBET banner, + timed boxes for promo messages
  in chat (tools/chat_promo_scan.py, added by the pipeline after the cut file exists);
- chapters for the description: sub-topics via auto_events (min 60 s), title = the opening phrase (owner edits).

usage: python3 auto_plan.py transcript.txt VOD_FROM VOD_TO FILE_OFFSET out_plan.json [--sil 6]
FILE_OFFSET = real start of the cut file (vodcut.py prints it) -> plan times are file times."""
import sys, json, re, argparse
sys.path.insert(0, __import__("os").path.dirname(__file__))
import auto_events as AE

ap = argparse.ArgumentParser(); ap.add_argument("tr"); ap.add_argument("a", type=float); ap.add_argument("b", type=float)
ap.add_argument("off", type=float); ap.add_argument("out"); ap.add_argument("--sil", type=float, default=float(__import__("os").environ.get("AP_SIL", 90)))
ap.add_argument("--target", type=float, default=0, help="minutes: long range (game for hours) -> best moments up to this length")
x = ap.parse_args()
L = AE.load(x.tr, x.a - 1, x.b + 1)
def is_hum(t):
    """humming / «ооооо» / «рэс, рэс, рэс…» lines (no backtracking regex: those hang on long lines)"""
    s = re.sub(r"\W", "", t.lower())
    if len(s) >= 10 and len(set(s)) <= 3: return True
    toks = [w for w in re.split(r"[\s,.!?-]+", t.lower()) if w]
    return len(toks) >= 6 and max(toks.count(w) for w in set(toks)) / len(toks) > 0.7
# donation blocks: a greeting/donation phrase starts a block; it lasts while the lines are the donation text or his
# short reply, until the talk returns to the event topic (topic words) or after a pause; no laugh/joke -> cut whole
DON_START = re.compile(r"(?i)^\W*(а,?\s*|ну,?\s*|о,?\s*|боже,?[^.]*\.\s*)?(привет,?\s*(тош|тох|антон|тоша|тошка|тоха)|мой первый донат|это мой первый|"
                       r"передай,?\s*(пожалуйста,?\s*)?привет|у меня (сегодня|вчера|\d+ числа)? ?(был|была)? ?(день|др)|"
                       r"поздрав(ь|ьте) меня|с днём рождения|с днем рождения|задонат|донатик)")
import collections
topic = collections.Counter(w for _, _, t in L for w in AE.words(t))
top = {w for w, _ in topic.most_common(40)}
cut_iv = []
i = 0
while i < len(L):
    s0, e0, t0 = L[i]
    if DON_START.search(t0) and __import__("os").environ.get("AP_DON", "0") == "1":   # off by default: transcript-only detection is ~50/50 (eval 14.11)
        j = i; joke = 0
        while j + 1 < len(L) and L[j + 1][0] - L[j][1] < 4 and L[j + 1][0] - s0 < float(__import__("os").environ.get("AP_DONMAX", 40)):
            nxt = L[j + 1][2]; ov = len(set(AE.words(nxt)) & top)
            if j > i + 1 and ov >= 2 and not DON_START.search(nxt): break
            j += 1
        blk = L[i:j + 1]
        joke = sum(len(re.findall(r"(?i)(ахах|хаха|ха-ха|ору|пиздец|ебать|офигеть)", t)) for _, _, t in blk)
        if joke < 2: cut_iv.append((s0 - 0.3, blk[-1][1] + 0.3))
        i = j + 1; continue
    i += 1
def in_cut(s, e): return any(a <= s and e <= b + 0.5 for a, b in cut_iv)
keep = []
for i, (s, e, t) in enumerate(L):
    if is_hum(t) or in_cut(s, e): continue
    keep.append((s, e))

STORY = re.compile(r"(?i)(письм|почт|сын|папочк|мам|дочь|щен|собак|товарищ|министерств|задани|приказ|смена|начинается|"
                   r"купил|апгрейд|прокач|улучш|автопилот|кран|новый|первый раз|концовк|финал|спасибо за игру|"
                   r"конец|последн|сюжет|босс|умер|убил|победа|проиграл|вор|украд|спизд)")
def highlights(L, clips, A, B, target, W=30.0):
    """game playthrough -> best moments (eval 14.11 on «Картошка»: manual cut = first contact with the game
    (~60 % of the time from the first ~12 %), story beats (letters, son, puppy, upgrades), and the finale)."""
    import math, os
    span = max(1.0, B - A); n = int(span // W) + 1; sc = [0.0] * n
    pw = float(os.environ.get("HL_POS", 18.0)); ew = float(os.environ.get("HL_END", 12.0)); stw = float(os.environ.get("HL_STORY", 0.8)); tau = float(os.environ.get("HL_TAU", 0.09))
    for i in range(n):
        s0 = A + i * W; part = [l for l in L if s0 <= l[0] < s0 + W]
        pos = (s0 - A) / span
        speech = sum(e - s for s, e, _ in part) / W
        react = sum(len(AE.REACT.findall(t)) for _, _, t in part)
        story = sum(1 for _, _, t in part if STORY.search(t))
        don = sum(1 for _, _, t in part if AE.DONAT.search(t) or re.search(r"(?i)привет,?\s*(тош|антон|тох)", t))
        risk = sum(1 for _, _, t in part if AE.RISK.search(t))
        sc[i] = (pw * math.exp(-pos / tau) + ew * math.exp(-(1 - pos) / 0.025) + 1.2 * speech + 0.35 * min(react, 6)
                 + stw * min(story, 3) - 1.2 * min(don, 3) - 4 * risk)
    sm = [(sc[max(0, i - 1)] + 2 * sc[i] + sc[min(n - 1, i + 1)]) / 4 for i in range(n)]   # moments, not single windows
    order = sorted(range(n), key=lambda i: -sm[i]); pick = set(); tot = 0.0
    for i in order:
        if tot >= target: break
        if risk_in(L, A + i * W, A + (i + 1) * W): continue
        pick.add(i); tot += W
    wins = []
    for i in sorted(pick):
        s0, e0 = A + i * W, A + (i + 1) * W
        if wins and s0 - wins[-1][1] <= W * float(os.environ.get("HL_GAP", 1.0)): wins[-1][1] = e0
        else: wins.append([s0, e0])
    # snap edges to sentence boundaries
    out = []
    for s0, e0 in wins:
        ss = max([l[0] for l in L if l[0] <= s0 + 2] or [s0]); ee = min([l[1] for l in L if l[1] >= e0 - 2] or [e0])
        out.append((ss, ee))
    res = [[max(s, ws), min(e, we)] for s, e in clips for ws, we in out if min(e, we) - max(s, ws) > 1.0]
    print(f"highlights: {len(out)} moments, {sum(e - s for s, e in res) / 60:.1f} min")
    return res
def risk_in(L, a, b): return any(AE.RISK.search(t) for s, e, t in L if a <= s < b)

clips = []
for s, e in keep:
    s, e = max(x.a, s - 0.35), min(x.b, e + 0.45)
    if clips and s - clips[-1][1] <= x.sil: clips[-1][1] = max(clips[-1][1], e)
    else: clips.append([s, e])
if clips: clips[0][0] = x.a; clips[-1][1] = x.b                 # the event's start and end stay as chosen
def subtract(cl, cuts):
    out = []
    for s, e in cl:
        segs = [[s, e]]
        for a, b in cuts:
            nxt = []
            for u, v in segs:
                if b <= u or a >= v: nxt.append([u, v])
                else:
                    if a > u: nxt.append([u, a])
                    if b < v: nxt.append([b, v])
            segs = nxt
        out += [g for g in segs if g[1] - g[0] > 1.0]
    return out
if __import__("os").environ.get("AP_DEBUG"): print("CUTS", [(round(a), round(b)) for a, b in cut_iv])
clips = subtract(clips, cut_iv)                                   # donation blocks stay cut even inside merged pauses
if x.target and sum(e - s for s, e in clips) > x.target * 60 * 1.15:
    clips = highlights(L, clips, x.a, x.b, x.target * 60)
clips = [c for c in clips if c[1] - c[0] > 1.0]
BL = [[0, 0, 262, 105], [1600, 18, 320, 245]]
plan = {"zoom": {"factor": 1.5, "anchor": [1, 1]}, "blur": BL, "vod_from": x.a, "vod_to": x.b, "file_offset": x.off,
        "clips": [{"s": round(s - x.off, 2), "e": round(e - x.off, 2), "zoom": [], "blur": list(BL)} for s, e in clips]}
# moments = kept windows (for the owner's review in the bot) and chapters (≤ 14, ≥ 60 s, first = 0:00)
mom = []
for s, e in clips:
    if mom and s - mom[-1][1] < 30: mom[-1][1] = e
    else: mom.append([s, e])
plan["moments"] = [{"from": round(s, 1), "to": round(e, 1),
                    "text": " / ".join(t for ss, ee, t in L if s <= ss < e)[:200],
                    "risk": [t[:80] for ss, ee, t in L if s <= ss < e and AE.RISK.search(t)][:3]} for s, e in mom]
subs = AE.events([l for l in L if any(s <= l[0] < e for s, e in mom)], mn=60, mx=600)
while len(subs) > 14:                                                   # merge the two closest neighbours
    i = min(range(len(subs) - 1), key=lambda k: subs[k + 1]["start"] - subs[k]["start"])
    subs[i]["end"] = subs[i + 1]["end"]; subs.pop(i + 1)
import chapter_titles as CT
_ctx = [l for l in L if any(s <= l[0] < e for s, e in mom)]
plan["chapters"] = [{"src": round(max(ev["start"], x.a) - x.off + 0.5, 2),
                     "title": CT.title([l for l in _ctx if ev["start"] <= l[0] < ev["end"]], _ctx)} for ev in subs] or \
                   [{"src": round(x.a - x.off + 0.5, 2), "title": "Начало"}]
json.dump(plan, open(x.out, "w"), ensure_ascii=False, indent=1)
kept = sum(e - s for s, e in clips); print(f"moments {len(plan['moments'])}, risky {sum(1 for m in plan['moments'] if m['risk'])}, clips {len(clips)}, kept {kept/60:.1f} of {(x.b-x.a)/60:.1f} min, chapters {len(plan['chapters'])}")
