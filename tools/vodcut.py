"""Build a playable file for a time range from segments already downloaded by vod.py
(the <out>.segs dir). Avoids a full-VOD copy on disk.
usage: python3 vodcut.py <vod_id> <format> <segs_dir> <start_sec> <end_sec> <out.mp4>
Prints the real start offset of the first segment (to align plan timestamps)."""
import subprocess, sys, re, urllib.request, os

vid, fmt, sd, a, b, out = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]), float(sys.argv[5]), sys.argv[6]
url = subprocess.check_output(["yt-dlp", "--no-warnings", "-g", "-f", fmt,
                               f"https://www.twitch.tv/videos/{vid}"], text=True).strip().splitlines()[-1]
base = url.rsplit("/", 1)[0]
pl = urllib.request.urlopen(url, timeout=30).read().decode()
init = re.search(r'#EXT-X-MAP:URI="([^"]+)"', pl)
t, dur, i, pick, first = 0.0, 0, 0, [], None
for line in pl.splitlines():
    if line.startswith("#EXTINF"): dur = float(line[8:].split(",")[0])
    elif line and not line.startswith("#"):
        if t + dur > a and t < b:
            pick.append(i); first = t if first is None else first
        t += dur; i += 1
raw = out + ".raw"
with open(raw, "wb") as f:
    if init: f.write(urllib.request.urlopen(base + "/" + init.group(1), timeout=60).read())
    for k in pick: f.write(open(f"{sd}/{k:06d}", "rb").read())
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-fflags", "+genpts", "-i", raw, "-c", "copy",
                "-avoid_negative_ts", "make_zero", out], check=True)
os.remove(raw)
print(f"offset {first:.3f}")
