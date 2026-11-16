"""Exact blur boxes for the stream's overlay banners (FONBET / promo banner top-right, donation widget top-left) —
rule 16.11.2026: blur ONLY inside the banner window, never the video around it (like promo messages in chat).
Samples frames, finds the banner by colour (red/pink banner, lilac widget) in its corner, takes the union bbox over
all samples + 4 px. usage: python3 banner_box.py ev.mp4 → prints [[x,y,w,h], ...] for the plan "blur" list."""
import sys, subprocess, json, numpy as np
v = sys.argv[1]; n = int(sys.argv[2]) if len(sys.argv) > 2 else 24
D = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", v]))
def frame(t):
    raw = subprocess.check_output(["ffmpeg", "-loglevel", "error", "-ss", f"{t:.1f}", "-i", v, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"])
    return np.frombuffer(raw, np.uint8).reshape(1080, 1920, 3).astype(np.int16)
red_bb = []; wid_bb = []
for i in range(n):
    f = frame(D * (i + 0.5) / n)
    tr = f[0:200, 1500:1920]; r, g, b = tr[..., 0], tr[..., 1], tr[..., 2]
    m = (r > 150) & (r - g > 70) & (r - b > 50)                       # red banner
    ys, xs = np.where(m)
    if len(xs) > 2000: red_bb.append((xs.min() + 1500, ys.min(), xs.max() + 1500, ys.max()))
    tl = f[0:200, 0:400]; r, g, b = tl[..., 0], tl[..., 1], tl[..., 2]
    m = (b > 150) & (r > 150) & (g > 140) & (abs(r - b) < 70)          # light lilac/white widget
    ys, xs = np.where(m)
    if len(xs) > 2000: wid_bb.append((xs.min(), ys.min(), xs.max(), ys.max()))
out = []
for bb in (wid_bb, red_bb):
    if bb:
        a = np.array(bb); x0, y0 = np.percentile(a[:, 0], 5), np.percentile(a[:, 1], 5); x1, y1 = np.percentile(a[:, 2], 95), np.percentile(a[:, 3], 95)
        x0, y0 = max(0, int(x0) - 4), max(0, int(y0) - 4); out.append([x0, y0, min(1920, int(x1) + 5) - x0, int(y1) + 5 - y0])
print(json.dumps(out))
