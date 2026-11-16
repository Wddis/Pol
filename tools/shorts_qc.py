"""Quick QC of the source range BEFORE a Shorts render and of the finished file AFTER it (owner's feedback 16.11.2026:
«звук давится», «картинка экрана не ровная», «кадр экрана на экране вебки»).

before:  python3 shorts_qc.py src SRC.mp4 "s1-e1 s2-e2" [--scr 1264:711:328:0] [--cam 620:349:1300:716]
         -> black / frozen moments of the SCREEN crop (src seconds) to cut + qc_cam_*.png zoomed strips of the CAM
            crop edges for an eye check (no game strip inside the webcam card); measured T2x2 webcam window: x≥1298, y≥712
after:   python3 shorts_qc.py out OUT.mp4
         -> one audio stream, 48 kHz, integrated loudness -14±1 LUFS, peak ≤ -1 dBFS; FAIL lines must be fixed
"""
import sys, re, subprocess, json
import numpy as np

def run(cmd): return subprocess.run(cmd, capture_output=True, text=True).stderr

def src_check(src, segs, scr, cam):
    bad = 0
    for r in segs.split():
        a, b = map(float, r.split("-"))
        e = run(["ffmpeg", "-nostats", "-ss", str(a), "-to", str(b), "-i", src, "-an", "-vf",
                 f"crop={scr},scale=288:162,fps=10,blackdetect=d=0.2:pix_th=0.08,freezedetect=n=0.003:d=1.5", "-f", "null", "-"])
        for s, t in re.findall(r"black_start:([\d.]+) black_end:([\d.]+)", e):
            print(f"BLACK  {a + float(s):.1f}-{a + float(t):.1f}  (cut it, check speech in the transcript)"); bad += 1
        for s in re.findall(r"freeze_start: ([\d.]+)", e):
            print(f"FREEZE from {a + float(s):.1f} (paused video on stream is OK; loading screen -> cut)")
        # webcam crop edges -> zoomed strips for an EYE check (automatic edge search was tested 16.11 and mistakes the
        # shelves behind the streamer for the window border): left strip = crop's left edge ±30 px, top strip = top edge ±30 px.
        # In the strip the crop border is the red line; screen/game texture right of / below it = move CAM.
        w, h, x, y = map(int, cam.split(":")); t = (a + b) / 2; o = f"qc_cam_{int(a)}.png"
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(t), "-i", src, "-filter_complex",
                        f"[0:v]split[l][t];[l]crop=60:{h}:{x - 30}:{y},drawbox=x=30:y=0:w=1:h={h}:color=red,scale=240:{h * 2}:flags=neighbor[L];"
                        f"[t]crop={w}:60:{x}:{y - 30},drawbox=x=0:y=30:w={w}:h=1:color=red,scale={w * 2}:240:flags=neighbor,"
                        f"pad={w * 2 + 260}:{max(240, h * 2)}:260:0[T];[T][L]overlay=0:0", "-frames:v", "1", o])
        print(f"CAM    eye-check {o}: left of/above the red line may be game, right of/below it must be ONLY webcam")
    print("SRC OK" if not bad else f"SRC: {bad} problem(s)")

def out_check(f):
    p = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-of", "json", f], capture_output=True, text=True).stdout)
    au = [s for s in p["streams"] if s["codec_type"] == "audio"]
    e = run(["ffmpeg", "-nostats", "-i", f, "-vn", "-af", "ebur128=peak=true", "-f", "null", "-"])
    I = float(re.findall(r"I:\s+(-?[\d.]+) LUFS", e)[-1]); pk = float(re.findall(r"Peak:\s+(-?[\d.]+) dBFS", e)[-1])
    checks = [("one audio stream", len(au) == 1), ("48 kHz", au and au[0].get("sample_rate") == "48000"),
              (f"loudness {I} LUFS (-14±1)", abs(I + 14) <= 1), (f"peak {pk} dBFS (≤ -1)", pk <= -0.9)]
    for n, ok in checks: print(("OK   " if ok else "FAIL ") + n)

if __name__ == "__main__":
    a = sys.argv[1:]
    opt = lambda k, d: a[a.index(k) + 1] if k in a else d
    if a[0] == "src": src_check(a[1], a[2], opt("--scr", "1264:711:328:0"), opt("--cam", "620:349:1300:716"))
    else: out_check(a[1])
