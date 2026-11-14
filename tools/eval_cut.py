"""Benchmark: the bot's automatic cutting (tools/auto_events.py + tools/auto_plan.py) vs OUR manual cuts.
usage: python3 tools/eval_cut.py [--verbose]
Ground truth = the plans we cut by hand (work/*/…_plan_off<OFF>.json, kartoshka_plan_blocks.json) on the full transcripts.
Metrics (time-weighted):
  T1 events  — for each manual event: the bot's /map topic(s) that best cover it; score = IoU of [start,end]
  T2 inside  — bot plan inside the manual event range vs manual kept time: IoU of kept seconds
  T3 game    — 3.6 h game → best moments (target = manual length): IoU of kept seconds vs manual blocks
Total = mean of T1, T2, T3 (per task: mean over events)."""
import json, os, re, sys, subprocess, glob
R = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(R, "tools")); import auto_events as AE
V = "--verbose" in sys.argv
CASES = [  # (name, transcript, plan file)
    ("podium", "work/2026-11-08_vod2923062211/transcript.txt", "work/2026-11-08_vod2923062211/podium_plan_off2890.json"),
    ("pogoni", "work/2026-11-08_vod2923062211/transcript.txt", "work/2026-11-08_vod2923062211/pogoni_plan_off4600.json"),
    ("monstry", "work/2026-11-08_vod2923062211/transcript.txt", "work/2026-11-08_vod2923062211/monstry_plan_off6130.json"),
    ("nvidia", "work/2026-11-12_vod2926718724/transcript_full.txt", "work/2026-11-12_vod2926718724/nvidia_plan_off2050.json"),
    ("steamframe", "work/2026-11-12_vod2926718724/transcript_full.txt", "work/2026-11-12_vod2926718724/steamframe_plan_off5630.json"),
    ("veriti", "work/2026-11-12_vod2926718724/transcript_full.txt", "work/2026-11-12_vod2926718724/veriti_plan_off6580.json")]
GAME = ("kartoshka", "work/2026-11-12_vod2926718724/transcript_full.txt", "work/2026-11-12_vod2926718724/kartoshka_plan_blocks.json")

def manual(plan_file):
    p = json.load(open(os.path.join(R, plan_file)))
    if "blocks" in p: return [(b["vod_from"], b["vod_to"]) for b in p["blocks"]]
    off = float(re.search(r"off(\d+)", plan_file)[1]); return [(c["s"] + off, c["e"] + off) for c in p["clips"]]
def union_len(iv):
    iv = sorted(iv); tot = 0; cur = None
    for a, b in iv:
        if cur and a <= cur[1]: cur[1] = max(cur[1], b)
        else:
            if cur: tot += cur[1] - cur[0]
            cur = [a, b]
    return tot + (cur[1] - cur[0] if cur else 0)
def inter(A, B): return sum(max(0, min(b, d) - max(a, c)) for a, b in A for c, d in B)
def iou(A, B):
    i = inter(A, B); u = union_len(A) + union_len(B) - i; return i / u if u else 1.0
def auto_plan(tr, a, b, target=0):
    out = "/tmp/_ev_plan.json"
    cmd = ["python3", os.path.join(R, "tools", "auto_plan.py"), os.path.join(R, tr), str(a), str(b), "0", out] + (["--target", str(target)] if target else [])
    subprocess.run(cmd, capture_output=True, check=True); p = json.load(open(out)); return [(c["s"], c["e"]) for c in p["clips"]]

res = {"T1": [], "T2": [], "T3": []}
cache = {}
for name, tr, pf in CASES:
    M = manual(pf); a, b = M[0][0], M[-1][1]
    if tr not in cache: cache[tr] = AE.events(AE.load(os.path.join(R, tr)))
    ev = cache[tr]
    # T1: best contiguous run of topics covering the manual event
    best = 0; bi = None
    for i in range(len(ev)):
        for k in range(i, min(len(ev), i + 12)):
            s = iou([(a, b)], [(ev[i]["start"], ev[k]["end"])])
            if s > best: best, bi = s, (i, k)
    ra, rb = AE.refine(AE.load(os.path.join(R, tr)), ev[bi[0]]["start"], ev[bi[1]]["end"]) if os.environ.get("REFINE", "1") == "1" else (ev[bi[0]]["start"], ev[bi[1]]["end"])
    best = iou([(a, b)], [(ra, rb)]); res["T1"].append(best)
    t2 = iou(M, auto_plan(tr, a, b)); res["T2"].append(t2)
    if V: print(f"{name:11s} T1 {best:.3f} topics#{bi[0] + 1}-{bi[1] + 1} ({ev[bi[0]]['start']:.0f}-{ev[bi[1]]['end']:.0f} vs {a:.0f}-{b:.0f})  T2 {t2:.3f}")
name, tr, pf = GAME; M = manual(pf); tgt = union_len(M) / 60
t3 = iou(M, auto_plan(tr, M[0][0], M[-1][1], target=tgt)); res["T3"].append(t3)
if V: print(f"{name:11s} T3 {t3:.3f} (target {tgt:.1f} min)")
avg = {k: sum(v) / len(v) for k, v in res.items()}
print(" ".join(f"{k} {v * 100:.1f}%" for k, v in avg.items()), f"| TOTAL {sum(avg.values()) / 3 * 100:.1f}%")
