"""Faster renderer for long cuts. Same plan.json as edit.py.
Splits each clip at zoom windows: plain sub-parts get only blur (no per-frame scaling),
zoom sub-parts get the smooth zoom. Audio is normalized once on the final file.
usage: python3 edit2.py src.mp4 plan.json out.mp4 [jobs]"""
import json, subprocess, sys, os, concurrent.futures as cf

src, plan, out = sys.argv[1], json.load(open(sys.argv[2])), sys.argv[3]
jobs = int(sys.argv[4]) if len(sys.argv) > 4 else 2
Z = plan.get("zoom", {}).get("factor", 1.5)
ax, ay = plan.get("zoom", {}).get("anchor", [1, 1])
RAMP, PAD = 0.35, 0.4
tmp = out + ".parts"; os.makedirs(tmp, exist_ok=True)

def blur(inp, boxes=None):
    f, cur = "", inp
    for i, (x, y, w, h) in enumerate(plan.get("blur", []) if boxes is None else boxes):
        f += f"[{cur}]split[b{i}a][b{i}b];[b{i}b]crop={w}:{h}:{x}:{y},gblur=sigma=25[b{i}c];[b{i}a][b{i}c]overlay={x}:{y}[bl{i}];"
        cur = f"bl{i}"
    return cur, f

# build sub-parts: (start, end, zoom_window_relative or None)
# a clip may carry its own "blur" list (e.g. a full-screen-webcam part must not get screen boxes)
subs = []
for c in plan["clips"]:
    s, e = c["s"], c["e"]; cur = s; bl = c.get("blur")
    for zz in sorted(c.get("zoom", [])):                 # [a, b] or [a, b, factor] (own factor, e.g. 2.77 = webcam full frame)
        a, b = zz[0], zz[1]; zf = zz[2] if len(zz) > 2 else Z
        za, zb = max(s, s + a - PAD), min(e, s + b + PAD)
        if za > cur + 0.05: subs.append((cur, za, None, bl))
        subs.append((za, zb, (s + a - za, s + b - za, zf), bl)); cur = zb
    if e > cur + 0.05: subs.append((cur, e, None, bl))

def render(i_sub):
    i, (s, e, zw, bl) = i_sub
    p = f"{tmp}/{i:04d}.mp4"
    lab, bf = blur("0:v", bl)
    if zw:
        a, b, zf = zw
        k = f"min(clip((t-{a})/{RAMP},0,1),clip(({b}-t)/{RAMP},0,1))"
        z = f"(1+({zf}-1)*{k})"
        vf = (bf + f"[{lab}]scale=w='trunc(1920*{z}/2)*2':h='trunc(1080*{z}/2)*2':eval=frame:flags=bicubic,"
              f"crop=1920:1080:x='floor(1920*({z}-1)*{ax})':y='floor(1080*({z}-1)*{ay})',fps=60,format=yuv420p[v]")
    else:
        vf = bf + f"[{lab}]fps=60,format=yuv420p[v]"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", f"{s:.3f}", "-to", f"{e:.3f}", "-i", src,
                    "-filter_complex", vf, "-map", "[v]", "-map", "0:a",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-g", "120", "-threads", str(max(1, 4 // jobs)),
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", p], check=True)
    return p

with cf.ThreadPoolExecutor(jobs) as ex:
    parts = list(ex.map(render, list(enumerate(subs))))
    print("rendered", len(parts), "parts", flush=True)
with open(f"{tmp}/list.txt", "w") as f:
    for p in parts: f.write(f"file '{os.path.abspath(p)}'\n")
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{tmp}/list.txt",
                "-c:v", "copy", "-af", "loudnorm=I=-14:TP=-1.5:LRA=11", "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
                "-movflags", "+faststart", out], check=True)
subprocess.run(["rm", "-rf", tmp])
print("done", out, flush=True)
