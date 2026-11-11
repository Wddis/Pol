"""Render a cut per the montage style guide.
usage: python3 edit.py src.mp4 plan.json out.mp4
plan.json: {"zoom": {"factor":1.5, "anchor":[ax,ay]},   # anchor in 0..1 (0,1 = bottom-left)
            "blur": [[x,y,w,h], ...],                    # always-on blur boxes (source px)
            "clips": [{"s":12.0,"e":40.5,"zoom":[[a,b],...]}, ...]}  # zoom windows, clip-relative sec
"""
import json, subprocess, sys, os

src, plan, out = sys.argv[1], json.load(open(sys.argv[2])), sys.argv[3]
Z = plan.get("zoom", {}).get("factor", 1.5)
ax, ay = plan.get("zoom", {}).get("anchor", [0, 1])
RAMP = 0.35  # seconds of smooth zoom in/out
tmp = out + ".parts"; os.makedirs(tmp, exist_ok=True)

def zexpr(wins):
    # piecewise smooth zoom factor as a function of t
    if not wins: return "1"
    terms = []
    for a, b in wins:
        up = f"clip((t-{a})/{RAMP},0,1)"
        dn = f"clip(({b}-t)/{RAMP},0,1)"
        terms.append(f"min({up},{dn})")
    k = terms[0]
    for t in terms[1:]: k = f"max({k},{t})"
    return f"(1+({Z}-1)*{k})"

def blur_chain(inp, boxes):
    if not boxes: return inp, ""
    f, cur = "", inp
    for i, (x, y, w, h) in enumerate(boxes):
        f += f"[{cur}]split[b{i}a][b{i}b];[b{i}b]crop={w}:{h}:{x}:{y},gblur=sigma=25[b{i}c];[b{i}a][b{i}c]overlay={x}:{y}[bl{i}];"
        cur = f"bl{i}"
    return cur, f

parts = []
for i, c in enumerate(plan["clips"]):
    z = zexpr(c.get("zoom", []))
    lab, bf = blur_chain("0:v", plan.get("blur", []))
    vf = (bf + f"[{lab}]scale=w='trunc(1920*{z}/2)*2':h='trunc(1080*{z}/2)*2':eval=frame:flags=bicubic,"
          f"crop=1920:1080:x='floor(1920*({z}-1)*{ax})':y='floor(1080*({z}-1)*{ay})',fps=60,format=yuv420p[v]")
    p = f"{tmp}/{i:03d}.mp4"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(c["s"]), "-to", str(c["e"]), "-i", src,
                    "-filter_complex", vf, "-map", "[v]", "-map", "0:a",
                    "-af", "afade=t=in:d=0.04,loudnorm=I=-14:TP=-1.5:LRA=11",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-g", "120",
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", p], check=True)
    parts.append(p); print("part", i, c["s"], c["e"], flush=True)

with open(f"{tmp}/list.txt", "w") as f:
    for p in parts: f.write(f"file '{os.path.abspath(p)}'\n")
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{tmp}/list.txt",
                "-c", "copy", "-movflags", "+faststart", out], check=True)
print("done", out)
