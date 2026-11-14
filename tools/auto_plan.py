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
ap.add_argument("off", type=float); ap.add_argument("out"); ap.add_argument("--sil", type=float, default=6.0)
ap.add_argument("--target", type=float, default=0, help="minutes: long range (game for hours) -> best moments up to this length")
x = ap.parse_args()
L = AE.load(x.tr, x.a - 1, x.b + 1)
def is_hum(t):
    """humming / «ооооо» / «рэс, рэс, рэс…» lines (no backtracking regex: those hang on long lines)"""
    s = re.sub(r"\W", "", t.lower())
    if len(s) >= 10 and len(set(s)) <= 3: return True
    toks = [w for w in re.split(r"[\s,.!?-]+", t.lower()) if w]
    return len(toks) >= 6 and max(toks.count(w) for w in set(toks)) / len(toks) > 0.7
keep = []
for i, (s, e, t) in enumerate(L):
    if is_hum(t): continue
    if AE.DONAT.search(t):
        nxt = " ".join(tt for ss, ee, tt in L[i + 1:i + 6] if ss < e + 8)
        if not AE.REACT.search(nxt) and not AE.REACT.search(t): continue
    keep.append((s, e))
clips = []
for s, e in keep:
    s, e = max(x.a, s - 0.35), min(x.b, e + 0.45)
    if clips and s - clips[-1][1] <= x.sil: clips[-1][1] = max(clips[-1][1], e)
    else: clips.append([s, e])
if clips: clips[0][0] = x.a; clips[-1][1] = x.b                 # the event's start and end stay as chosen
if x.target and sum(e - s for s, e in clips) > x.target * 60 * 1.15:
    # highlights: score 40–240 s sub-moments, keep the best (always the first and the last = start & finale)
    subs_h = AE.events(L, mn=40, mx=240)
    pick = {0, len(subs_h) - 1} if subs_h else set()
    for i in sorted(range(len(subs_h)), key=lambda i: -subs_h[i]["score"]):
        if sum(subs_h[k]["end"] - subs_h[k]["start"] for k in pick) >= x.target * 60: break
        pick.add(i)
    wins = [(subs_h[i]["start"] - 0.4, subs_h[i]["end"] + 0.5) for i in sorted(pick)]
    clips = [[max(s, ws), min(e, we)] for s, e in clips for ws, we in wins if min(e, we) - max(s, ws) > 1.0]
    print(f"highlights: {len(pick)} of {len(subs_h)} moments")
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
plan["chapters"] = [{"src": round(max(ev["start"], x.a) - x.off + 0.5, 2),
                     "title": re.sub(r"\s+", " ", ev["first"].split(" / ")[0])[:60].strip(" ,.")} for ev in subs] or \
                   [{"src": round(x.a - x.off + 0.5, 2), "title": "Начало"}]
json.dump(plan, open(x.out, "w"), ensure_ascii=False, indent=1)
kept = sum(e - s for s, e in clips); print(f"moments {len(plan['moments'])}, risky {sum(1 for m in plan['moments'] if m['risk'])}, clips {len(clips)}, kept {kept/60:.1f} of {(x.b-x.a)/60:.1f} min, chapters {len(plan['chapters'])}")
