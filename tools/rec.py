"""Record a live Twitch HLS stream segment by segment (robust to proxy drops).
usage: python3 rec.py <channel> <format> <outdir> <seconds>"""
import subprocess, sys, time, urllib.request, os, re

ch, fmt, out, dur = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
os.makedirs(out, exist_ok=True)

def get_url():
    return subprocess.check_output(
        ["yt-dlp", "--no-warnings", "-g", "-f", fmt, f"https://www.twitch.tv/{ch}"],
        text=True, timeout=90).strip().splitlines()[-1]

def fetch(u, path, tries=5):
    for i in range(tries):
        try:
            with urllib.request.urlopen(u, timeout=30) as r, open(path + ".part", "wb") as f:
                f.write(r.read())
            os.replace(path + ".part", path)
            return True
        except Exception as e:
            print("retry", path, e, flush=True); time.sleep(1 + i)
    return False

pl = get_url()
seen, n, t0, have_init = set(), 0, time.time(), False
log = open(os.path.join(out, "segments.txt"), "a")
while time.time() - t0 < dur:
    try:
        txt = urllib.request.urlopen(pl, timeout=20).read().decode()
    except Exception as e:
        print("playlist err", e, flush=True)
        try: pl = get_url()
        except Exception as e2: print("reurl err", e2, flush=True)
        time.sleep(2); continue
    base = pl.rsplit("/", 1)[0]
    m = re.search(r'#EXT-X-MAP:URI="([^"]+)"', txt)
    if m and not have_init:
        iu = m.group(1) if m.group(1).startswith("http") else base + "/" + m.group(1)
        have_init = fetch(iu, os.path.join(out, "init.mp4"))
    ad = False
    for line in txt.splitlines():
        if line.startswith("#EXT-X-DATERANGE") and "stitched-ad" in line: ad = True
        if line.startswith("#EXTINF") and ("Amazon" in line or "live" not in line and "," in line and line.split(",",1)[1] not in ("", "live")):
            pass
        if line and not line.startswith("#"):
            u = line if line.startswith("http") else base + "/" + line
            key = u.split("?")[0]
            if key in seen: continue
            seen.add(key)
            p = os.path.join(out, f"{n:06d}.mp4")
            if fetch(u, p):
                log.write(f"{n:06d} {time.time():.1f} {key}\n"); log.flush(); n += 1
    time.sleep(2)
print("done", n, "segments", flush=True)
