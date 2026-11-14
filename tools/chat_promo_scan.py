"""Find betting-promo messages (FONBET bot) in the stream CHAT and output timed blur boxes for edit2.py.
Owner rule 14.11.2026: blur ONLY the message box with the promo (not the whole chat).

usage: python3 chat_promo_scan.py video.mp4 [--region x,y,w,h] [--fps 2] [--out boxes.json]
  region — where the chat overlay is in the 1920x1080 frame (default: right column 1540,90,380,900)
Prints / writes a JSON list [[x, y, w, h, t0, t1], ...] — t in seconds of THIS file (same timeline as plan clips).
Add them to the clip's "blur" list (or plan "blur"); edit2.py applies [x,y,w,h,t0,t1] only between t0 and t1.

How it works: frames at --fps -> cheap prefilter (the bot's message is highlighted with a maroon background
~RGB(60,40,48)) -> OCR (RapidOCR; Cyrillic comes out as Latin look-alikes: «Фонбет» -> «PoH6eT», «промокоду» ->
«npoMoKoAy») -> lines matching promo keywords -> the whole message (from its «HH:MM» line to the next message)
-> box padded a bit; boxes of consecutive samples are merged. Always CHECK a few frames by eye after rendering.
Needs: pip install rapidocr-onnxruntime"""
import sys, json, re, subprocess, argparse
import numpy as np

ap = argparse.ArgumentParser(); ap.add_argument("video"); ap.add_argument("--region", default="1540,90,380,900")
ap.add_argument("--fps", type=float, default=4.0); ap.add_argument("--out")
a = ap.parse_args()
RX, RY, RW, RH = map(int, a.region.split(","))
KEY = re.compile(r"(?i)(fonbet|фонбет|poh6|фрибет|p6et|npom|промокод|15\s?000|депозит|fkeit|ставк|казино|kasino)")   # NOT the bot name: it also posts harmless messages
TIME = re.compile(r"^\s*\d{1,2}[:.]\d{2}")
from rapidocr_onnxruntime import RapidOCR
ocr = RapidOCR()

def maroon_score(img):
    r, g, b = img[..., 0].astype(int), img[..., 1].astype(int), img[..., 2].astype(int)
    m = (r > 45) & (r < 95) & (g > 25) & (g < 55) & (b > 30) & (b < 65) & (r - g > 12) & (r > b)
    return int(m.sum())

def promo_boxes(img):
    res, _ = ocr(np.ascontiguousarray(img[..., ::-1]))           # BGR for the OCR
    if not res: return []
    lines = sorted([(min(p[1] for p in b), max(p[1] for p in b), min(p[0] for p in b), t) for b, t, c in res])
    out = []
    for i, (y0, y1, x0, t) in enumerate(lines):
        if not KEY.search(t): continue
        j = i                                                    # go up to the message start («HH:MM» line)
        while j > 0 and not TIME.match(lines[j][3]) and lines[j][0] - lines[j - 1][1] < 25: j -= 1
        k = i                                                    # go down until the next message starts
        while k + 1 < len(lines) and not TIME.match(lines[k + 1][3]) and lines[k + 1][0] - lines[k][1] < 25: k += 1
        out.append((int(lines[j][0]) - 5, int(lines[k][1]) + 6))
    merged = []
    for y0, y1 in sorted(out):
        if merged and y0 <= merged[-1][1]: merged[-1] = (merged[-1][0], max(merged[-1][1], y1))
        else: merged.append((y0, y1))
    return merged

fr = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-i", a.video, "-vf", f"fps={a.fps},crop={RW}:{RH}:{RX}:{RY}",
                       "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
n = RW * RH * 3; k = 0; hits = []
while True:
    buf = fr.stdout.read(n)
    if len(buf) < n: break
    t = k / a.fps; k += 1
    img = np.frombuffer(buf, np.uint8).reshape(RH, RW, 3)
    if maroon_score(img) < 1500: continue
    for y0, y1 in promo_boxes(img):
        hits.append([RX, RY + max(0, y0), RW, min(RH, y1) - max(0, y0), t - 0.5 / a.fps - 0.05, t + 0.5 / a.fps + 0.05])
# merge consecutive samples with near-identical boxes (chat scrolls -> a new box when it moves)
boxes = []
for h in hits:
    if boxes and abs(boxes[-1][1] - h[1]) < 12 and abs(boxes[-1][3] - h[3]) < 16 and h[4] <= boxes[-1][5] + 0.05:
        b = boxes[-1]; y0 = min(b[1], h[1]); y1 = max(b[1] + b[3], h[1] + h[3]); b[1], b[3], b[5] = y0, y1 - y0, h[5]
    else: boxes.append(list(h))
boxes = [[int(b[0]), int(b[1]), int(b[2]), int(b[3]), round(max(0, b[4]), 2), round(b[5], 2)] for b in boxes]
js = json.dumps(boxes)
if a.out: open(a.out, "w").write(js)
print(f"promo boxes: {len(boxes)} (samples with promo: {len(hits)})", file=sys.stderr); print(js)
