"""YouTube-cut of a clip: webcam and film take turns in ONE panel (default 2 s each), title bar and captions stay put.

Usage:
  python3 alternate_clip.py IN.mp4 START END OUT.mp4 --cam X,Y,W,H --film X,Y,W,H \
      --title "Фильм" --streamer login [--words words.json] [--period 2] [--cta "Полная запись — в описании"]
Layout 1080x1920: title bar 400..500, panel 540..1350 (1080x810, webcam or film), captions over panel bottom,
CTA below. Reuses captions/censoring from tiktok_clip.py. Audio is the original stream audio (one track).
"""
import argparse, json, os, subprocess, sys, tempfile
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tiktok_clip as tc  # noqa: E402

W, H = 1080, 1920
BAR_Y, BAR_H, PANEL_Y, PANEL_H = 400, 100, 540, 810


def overlay(path, title, streamer, cta):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.rectangle([0, BAR_Y, W, BAR_Y + BAR_H], fill=(145, 70, 255, 255))
    t = f"{streamer} смотрит «{title}»"; size = 44
    while d.textlength(t, font=tc.font("Inter-ExtraBold.otf", size)) > W - 80: size -= 2
    f = tc.font("Inter-ExtraBold.otf", size)
    d.text(((W - d.textlength(t, font=f)) / 2, BAR_Y + (BAR_H - size) / 2 - 4), t, font=f, fill=(255, 255, 255))
    f2 = tc.font("Inter-ExtraBold.otf", 46); w2 = d.textlength(cta, font=f2)
    x2, y2 = (W - w2 - 60) / 2, PANEL_Y + PANEL_H + 50
    d.rounded_rectangle([x2, y2, x2 + w2 + 60, y2 + 76], 38, fill=(255, 255, 255, 240))
    d.text((x2 + 30, y2 + 12), cta, font=f2, fill=(46, 18, 92))
    f3 = tc.font("Inter-ExtraBold.otf", 30); brand = "КИНОВАЙБ · фильмы со стримерами"
    d.text(((W - d.textlength(brand, font=f3)) / 2, y2 + 100), brand, font=f3, fill=(215, 200, 245))
    img.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inp"); ap.add_argument("start", type=float); ap.add_argument("end", type=float); ap.add_argument("out")
    ap.add_argument("--cam", required=True); ap.add_argument("--film", required=True)
    ap.add_argument("--title", required=True); ap.add_argument("--streamer", required=True)
    ap.add_argument("--words"); ap.add_argument("--period", type=float, default=2.0)
    ap.add_argument("--cta", default="Полная запись — в описании")
    a = ap.parse_args()
    cx, cy, cw, ch = map(int, a.cam.split(",")); fx, fy, fw, fh = map(int, a.film.split(","))
    tmp = tempfile.mkdtemp(); ov = os.path.join(tmp, "ov.png"); overlay(ov, a.title, a.streamer, a.cta)
    p = a.period; dur = a.end - a.start
    vf = (f"[0:v]split=3[a][b][c];"
          f"[a]crop={cw}:{ch}:{cx}:{cy},scale={W}:{PANEL_H}:force_original_aspect_ratio=increase,crop={W}:{PANEL_H}[cam];"
          f"[b]crop={fw}:{fh}:{fx}:{fy},scale={W}:{PANEL_H}:force_original_aspect_ratio=increase,crop={W}:{PANEL_H}[film];"
          f"[c]crop={fw}:{fh}:{fx}:{fy},scale=270:480:force_original_aspect_ratio=increase,crop=270:480,boxblur=20:2,scale={W}:{H},eq=brightness=-0.35[bg];"
          f"[bg][film]overlay=0:{PANEL_Y}:shortest=1[v1];"
          f"[v1][cam]overlay=0:{PANEL_Y}:enable='lt(mod(t,{2 * p}),{p})'[v2];"
          f"[v2][1:v]overlay=0:0[v3]")
    last = "v3"
    if a.words:
        ass = os.path.join(tmp, "cap.ass"); tc.captions_ass(ass, json.load(open(a.words)), a.start, a.end)
        # move captions to the bottom of the panel of this layout
        s = open(ass, encoding="utf-8").read().replace(f"\\pos({W // 2},{tc.FILM_Y + tc.FILM_H - 170})", f"\\pos({W // 2},{PANEL_Y + PANEL_H - 120})")
        open(ass, "w", encoding="utf-8").write(s)
        vf += f";[v3]subtitles={ass}:fontsdir={tc.F}[v4]"; last = "v4"
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", str(a.start), "-t", str(dur), "-i", a.inp, "-i", ov,
           "-filter_complex", vf, "-map", f"[{last}]", "-map", "0:a", "-r", "30", "-t", str(dur),
           "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", a.out]
    subprocess.run(cmd, check=True); print("rendered", a.out)


if __name__ == "__main__":
    main()
