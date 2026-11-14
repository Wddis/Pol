"""Тайм-коды (главы YouTube) для основного видео — в стиле, утверждённом владельцем 14.11.2026
(эталон: «T2x2 ГОТОВ К GTA VI», канал «ТОХА GTAШНЫЙ», https://youtu.be/Oasl9sPZMdQ). Правила — docs/timecodes.md.

usage: python3 timecodes.py chapters.json [--intro 10.0]
chapters.json:
  {"parts": ["r_b01.mp4", "r_b02.mp4", ...],          # rendered pieces IN ORDER (the final video = intro + these)
   "chapters": [{"part": 0, "at": 0.0, "title": "…"},   # at = seconds inside that piece (0 = its start)
                {"part": 4, "at": 0.0, "title": "…"}, ...]}
  or, for a single-file render with edit2 plan:  {"plan": "plan.json", "chapters": [{"src": 123.4, "title": "…"}]}
  (src = time in the source file of the plan; mapped through the plan clips)
Prints the description block:
  Тайм-коды:
  0:00 - …
  1:23 - …
Checks YouTube's chapter rules: first 0:00, ≥ 3 chapters, each ≥ 10 s, ascending."""
import sys, json, subprocess, argparse

ap = argparse.ArgumentParser(); ap.add_argument("cfg"); ap.add_argument("--intro", type=float, default=10.0)
a = ap.parse_args(); cfg = json.load(open(a.cfg))

def dur(f): return float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f]))
def fmt(t):
    t = int(t + 1e-6); h, m, s = t // 3600, t % 3600 // 60, t % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"

pts = []
if "parts" in cfg:
    starts, acc = [], a.intro
    for f in cfg["parts"]: starts.append(acc); acc += dur(f)
    total = acc
    for c in cfg["chapters"]: pts.append((starts[c["part"]] + c.get("at", 0.0), c["title"]))
else:
    plan = json.load(open(cfg["plan"])); acc, total = a.intro, None; m = []
    for cl in plan["clips"]: m.append((cl["s"], cl["e"], acc)); acc += cl["e"] - cl["s"]
    total = acc
    for c in cfg["chapters"]:
        t = next((o + c["src"] - s for s, e, o in m if s - 0.5 <= c["src"] <= e), None)
        if t is None: sys.exit(f"chapter src {c['src']} is not inside any clip")
        pts.append((t, c["title"]))
pts.sort(); pts[0] = (0.0, pts[0][1])                      # first chapter must be 0:00 (it also covers the intro)
bad = [f"«{pts[i][1]}» короче 10 с" for i in range(len(pts)) if (pts[i + 1][0] if i + 1 < len(pts) else total) - pts[i][0] < 10]
if len(pts) < 3: bad.append("меньше 3 глав — YouTube не покажет главы")
print("Тайм-коды:")
for t, title in pts: print(f"{fmt(t)} - {title}")
if bad: print("\n⚠ " + "; ".join(bad), file=sys.stderr)
print(f"(видео {fmt(total)}, глав {len(pts)})", file=sys.stderr)
