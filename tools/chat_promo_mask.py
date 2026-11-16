"""Frame-accurate blur MASK for betting-promo messages in the stream chat (owner rule 14.11: blur ONLY the promo
message box, only while it is on screen). Replaces per-box timed blur (16.11: 191 boxes made the render very slow and
boxes drifted when the chat scrolled between samples).
The bot's promo messages are drawn on a maroon highlight → every frame (at --fps) the maroon bands of the chat are
found by colour and painted white in a grey mask video (chat-region size). edit2.py uses it: one blur of the chat
region per frame, alpha-masked → exactly the highlighted messages are blurred, nothing else.
usage: python3 chat_promo_mask.py ev.mp4 mask.mkv [--region 1630,90,290,900 = the chat panel only] [--fps 20]
prints the plan snippet:  "mask": {"file": "mask.mkv", "x": 1540, "y": 90, "w": 380, "h": 900}"""
import sys, json, subprocess, argparse, numpy as np
ap = argparse.ArgumentParser(); ap.add_argument("video"); ap.add_argument("out"); ap.add_argument("--region", default="1630,90,290,900")
ap.add_argument("--fps", type=float, default=20.0); a = ap.parse_args()
X, Y, W, H = map(int, a.region.split(","))
def maroon(img):
    r, g, b = img[..., 0].astype(np.int16), img[..., 1].astype(np.int16), img[..., 2].astype(np.int16)
    return (r > 45) & (r < 95) & (g > 25) & (g < 55) & (b > 30) & (b < 65) & (r - g > 12) & (r > b)
rd = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-i", a.video, "-vf", f"fps={a.fps},crop={W}:{H}:{X}:{Y}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
wr = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{W}x{H}", "-r", str(a.fps), "-i", "-",
                       "-c:v", "ffv1", a.out], stdin=subprocess.PIPE)
n = W * H * 3; k = hit = 0
while True:
    buf = rd.stdout.read(n)
    if len(buf) < n: break
    img = np.frombuffer(buf, np.uint8).reshape(H, W, 3); m = maroon(img)
    out = np.zeros((H, W), np.uint8)
    rows = m.mean(1) > 0.2; y = 0
    while y < H:
        if rows[y]:
            y0 = y
            while y < H and rows[y]: y += 1
            if y - y0 >= 10:
                cols = np.where(m[y0:y].mean(0) > 0.25)[0]
                if len(cols) > 0.7 * W and cols.min() < 0.15 * W: out[max(0, y0 - 2):min(H, y + 2), cols.min():cols.max() + 1] = 255; hit += 1
        y += 1
    wr.stdin.write(out.tobytes()); k += 1
wr.stdin.close(); wr.wait()
print(f"frames {k}, promo bands {hit}", file=sys.stderr)
print(json.dumps({"mask": {"file": a.out, "x": X, "y": Y, "w": W, "h": H}}))
