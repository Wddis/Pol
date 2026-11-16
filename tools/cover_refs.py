"""Cover references for the owner — ALWAYS the same complete set (rule 16.11.2026, docs/cover-refs.md).
usage: python3 cover_refs.py VIDEO.mp4 OUT_DIR [--orig YOUTUBE_ID_OR_URL] [--game] [--cam x,y,w,h]
Makes OUT_DIR/<name>_cover_refs.zip with:
  01_original_cover.jpg    — thumbnail of the ORIGINAL video (1280x720, checked: ≥ 1280 wide, not the grey placeholder)
                             — REQUIRED for reactions; the script FAILS if it cannot get it (never silently skipped)
  02..09_t2x2_face_N.jpg   — 8 big sharp webcam crops of T2x2 (2× upscaled), different moments/emotions
  10..13_t2x2_cutout_N.png — 4 of them with the background removed (rembg) — ready «персонаж для вырезки»
  20..23_screen_N.jpg      — 4 screen frames (game / video on stream) without the webcam
  sheet.jpg                — contact sheet of everything (sent as a picture too)
The video ID is taken EXACTLY as YouTube gives it (case-sensitive: q6fg… ≠ Q6fg… — 16.11 mistake)."""
import os, sys, re, argparse, subprocess, io, zipfile, urllib.request
from PIL import Image, ImageStat
ap = argparse.ArgumentParser(); ap.add_argument("video"); ap.add_argument("out"); ap.add_argument("--orig")
ap.add_argument("--game", action="store_true"); ap.add_argument("--cam", default="655,368,1265,712", help="w,h,x,y of the webcam in 1920x1080")
a = ap.parse_args(); os.makedirs(a.out, exist_ok=True)
name = os.path.splitext(os.path.basename(a.video))[0]; files = []
def dur(f): return float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f]))
D = dur(a.video); cw, ch, cx, cy = map(int, a.cam.split(","))
# 1. original cover (required for reactions)
if a.orig:
    vid = re.search(r"(?:v=|youtu\.be/|shorts/)?([A-Za-z0-9_-]{11})(?:\b|$)", a.orig.strip())[1]
    ok = False
    for q in ("maxresdefault", "sddefault", "hqdefault"):
        try:
            data = urllib.request.urlopen(f"https://i.ytimg.com/vi/{vid}/{q}.jpg", timeout=20).read()
            im = Image.open(io.BytesIO(data)).convert("RGB")
            if im.width >= 480 and ImageStat.Stat(im).stddev[0] > 12:         # not the grey «…» placeholder
                im.save(os.path.join(a.out, "01_original_cover.jpg"), quality=95); ok = True; break
        except Exception: pass
    if not ok: sys.exit(f"ERROR: no thumbnail for '{vid}' — check the ID (case-sensitive!) / that the video exists")
    files.append("01_original_cover.jpg")
elif not a.game:
    sys.exit("ERROR: reaction video without --orig: the original cover is REQUIRED (or pass --game for a game video)")
# 2. faces: 8 moments, pick the sharpest of 3 nearby frames
def frame(t, vf, path):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", f"{t:.2f}", "-i", a.video, "-frames:v", "1", "-vf", vf, "-q:v", "2", path], check=True)
def sharp(p):
    im = Image.open(p).convert("L").resize((320, 180)); import numpy as np
    x = np.asarray(im, float); return float(np.abs(np.diff(x, axis=0)).mean() + np.abs(np.diff(x, axis=1)).mean())
faces = []
for i in range(8):
    best = None
    for dt in (-2, 0, 2):
        t = max(11, min(D - 1, D * (i + 0.5) / 8 + dt)); p = os.path.join(a.out, f"_tmp_{i}_{dt}.jpg")
        frame(t, f"crop={cw}:{ch}:{cx}:{cy},scale={cw * 2}:{ch * 2}:flags=lanczos", p); s = sharp(p)
        if not best or s > best[0]: best = (s, p)
    fn = f"{i + 2:02d}_t2x2_face_{i + 1}.jpg"; os.replace(best[1], os.path.join(a.out, fn)); faces.append(fn); files.append(fn)
for f in os.listdir(a.out):
    if f.startswith("_tmp_"): os.remove(os.path.join(a.out, f))
# 3. cutouts (rembg)
try:
    from rembg import remove
    for k, fn in enumerate(faces[:4]):
        out = remove(Image.open(os.path.join(a.out, fn))); cfn = f"{10 + k}_t2x2_cutout_{k + 1}.png"
        out.save(os.path.join(a.out, cfn)); files.append(cfn)
except Exception as e: print("cutouts skipped:", e, file=sys.stderr)
# 4. screen frames (webcam corner covered by cropping to the left/top part)
for k in range(4):
    t = max(11, D * (k + 0.5) / 4); fn = f"{20 + k}_screen_{k + 1}.jpg"
    frame(t, "crop=1260:1080:0:0" if not a.game else "scale=1920:1080", os.path.join(a.out, fn)); files.append(fn)
# 5. sheet + zip
ims = [Image.open(os.path.join(a.out, f)).convert("RGB").resize((480, 270)) for f in files]
sheet = Image.new("RGB", (1440, 270 * ((len(ims) + 2) // 3)), (20, 20, 20))
for i, im in enumerate(ims): sheet.paste(im, ((i % 3) * 480, (i // 3) * 270))
sheet.save(os.path.join(a.out, "sheet.jpg"), quality=85)
z = os.path.join(a.out, f"{name}_cover_refs.zip")
with zipfile.ZipFile(z, "w") as zf:
    for f in files: zf.write(os.path.join(a.out, f), f)
print(f"OK {z}: original={'yes' if files and files[0].startswith('01') else 'no (game)'}, faces={len(faces)}, files={len(files)}")
