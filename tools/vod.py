"""Download a Twitch VOD (or a time range of it) segment-by-segment in parallel.
usage: python3 vod.py <vod_id> <format> <out.mp4> [start_sec end_sec]
format: audio_only | 720p60 | 1080p60 ... (see yt-dlp -F)
Segments are saved as <out>.segs/<GLOBAL index> (same numbering vodcut.py uses), so several
ranges can be fetched into one dir. SEGS_ONLY=1: keep the segments, skip the joined file
(then cut events with vodcut.py — no full copy on disk).
"""
import subprocess, sys, os, re, urllib.request, concurrent.futures as cf, time

vid, fmt, out = sys.argv[1], sys.argv[2], sys.argv[3]
rng = (float(sys.argv[4]), float(sys.argv[5])) if len(sys.argv) > 5 else None
url = subprocess.check_output(["yt-dlp", "--no-warnings", "-g", "-f", fmt,
                               f"https://www.twitch.tv/videos/{vid}"], text=True).strip().splitlines()[-1]
base = url.rsplit("/", 1)[0]
pl = urllib.request.urlopen(url, timeout=30).read().decode()
segs, t, dur, gi = [], 0.0, None, 0
init = re.search(r'#EXT-X-MAP:URI="([^"]+)"', pl)
for line in pl.splitlines():
    if line.startswith("#EXTINF"): dur = float(line[8:].split(",")[0])
    elif line and not line.startswith("#"):
        if rng is None or (t + dur > rng[0] and t < rng[1]):
            segs.append((t, line, gi))
        t += dur; gi += 1
tmp = out + ".segs"; os.makedirs(tmp, exist_ok=True)

def get(i_name):
    i, name = i_name
    p = f"{tmp}/{i:06d}"
    if os.path.exists(p): return
    for k in range(6):
        try:
            data = urllib.request.urlopen(base + "/" + name, timeout=60).read()
            open(p + ".part", "wb").write(data); os.replace(p + ".part", p); return
        except Exception as e:
            time.sleep(1 + k)
    raise RuntimeError(f"failed {name}")

# "-muted" fallback: Twitch renames muted segments
def get_any(i_name):
    i, name = i_name
    try: get((i, name))
    except RuntimeError:
        alt = name.replace(".mp4", "-muted.mp4").replace(".ts", "-muted.ts")
        if alt == name: alt = name.replace("-muted", "")
        get((i, alt))

t0 = time.time()
with cf.ThreadPoolExecutor(12) as ex:
    for n, _ in enumerate(ex.map(get_any, [(s[2], s[1]) for s in segs])):
        if n % 200 == 0: print(f"{n}/{len(segs)} {time.time()-t0:.0f}s", flush=True)
if os.environ.get("SEGS_ONLY") == "1":
    print("segments ready", tmp, len(segs), f"{time.time()-t0:.0f}s", flush=True); sys.exit(0)
with open(out + ".raw", "wb") as f:
    if init:
        f.write(urllib.request.urlopen(base + "/" + init.group(1), timeout=60).read())
    for s in segs:
        f.write(open(f"{tmp}/{s[2]:06d}", "rb").read())
offset = segs[0][0] if segs else 0
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-fflags", "+genpts", "-i", out + ".raw", "-c", "copy",
                "-avoid_negative_ts", "make_zero", "-movflags", "+faststart", out], check=True)
os.remove(out + ".raw")
subprocess.run(["rm", "-rf", tmp])
print("done", out, "segments", len(segs), "offset", offset, f"{time.time()-t0:.0f}s", flush=True)
