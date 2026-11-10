"""Vertical 9:16 TikTok clip in KinoVibe style: streamer webcam on top, film below, word captions.

Usage:
  python3 tiktok_clip.py IN.mp4 START END OUT.mp4 --cam X,Y,W,H --film X,Y,W,H \
      --title "Пленницы" --streamer zubarefff [--words words.json]

--cam / --film: crop rectangles in source pixels (webcam overlay, film area without the webcam).
--words: JSON [{"start": s, "end": s, "word": "..."}] with times relative to the source video
         (faster-whisper word timestamps); captions are burned word by word.
Layout (1080x1920): webcam 0..690, title bar 690..790, film 790..1600, bottom 1600..1920 ("Полная запись в био").
"""
import argparse, json, os, subprocess, tempfile
from PIL import Image, ImageDraw, ImageFont

F = "/usr/share/fonts/opentype/inter/"
W, H = 1080, 1920
CAM_H, BAR_Y, BAR_H, FILM_Y, FILM_H = 690, 690, 100, 790, 810


def font(n, s):
    return ImageFont.truetype(F + n, s)


def overlay_png(path, title, streamer):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # title bar between webcam and film
    d.rectangle([0, BAR_Y, W, BAR_Y + BAR_H], fill=(145, 70, 255, 255))
    t = f"{streamer} смотрит «{title}»"
    size = 44
    while d.textlength(t, font=font("Inter-ExtraBold.otf", size)) > W - 80:
        size -= 2
    f = font("Inter-ExtraBold.otf", size)
    d.text(((W - d.textlength(t, font=f)) / 2, BAR_Y + (BAR_H - size) / 2 - 4), t, font=f, fill=(255, 255, 255))
    # bottom zone: brand + call to action (TikTok UI covers the very bottom, keep it high)
    f2 = font("Inter-ExtraBold.otf", 46)
    cta = "Полная запись — в био"
    w2 = d.textlength(cta, font=f2)
    x2, y2 = (W - w2 - 60) / 2, FILM_Y + FILM_H + 40
    d.rounded_rectangle([x2, y2, x2 + w2 + 60, y2 + 76], 38, fill=(255, 255, 255, 240))
    d.text((x2 + 30, y2 + 12), cta, font=f2, fill=(46, 18, 92))
    f3 = font("Inter-ExtraBold.otf", 30)
    brand = "КИНОВАЙБ · фильмы со стримерами"
    d.text(((W - d.textlength(brand, font=f3)) / 2, y2 + 100), brand, font=f3, fill=(215, 200, 245))
    img.save(path)


def ass_time(t):
    t = max(0, t)
    return f"{int(t // 3600)}:{int(t % 3600 // 60):02d}:{t % 60:05.2f}"


MAT = ("бля", "хуй", "хуе", "хуё", "хуя", "хул", "пизд", "еба", "ёба", "ебл", "ебн", "ебу", "уеб", "заеб", "сук", "мудак", "пидор", "охуе")


def censor(word):
    """Masks swear words in captions for TikTok: 'блять' -> 'Б**ТЬ' (audio is left as is)."""
    core = word.strip()
    low = core.lower()
    if any(m in low for m in MAT) and len(core) > 2:
        return core[0] + "*" * (len(core) - 3) + core[-2:] if len(core) > 4 else core[0] + "*" * (len(core) - 1)
    return core


def captions_ass(path, words, start, end):
    """Groups words into short lines (<=3 words), current word highlighted in brand pink."""
    ws = [w for w in words if w["end"] > start and w["start"] < end]
    lines, cur = [], []
    for w in ws:
        cur.append(w)
        if len(cur) >= 3 or w["word"].strip().endswith((".", "!", "?", ",")):
            lines.append(cur); cur = []
    if cur:
        lines.append(cur)
    out = ["[Script Info]", "ScriptType: v4.00+", f"PlayResX: {W}", f"PlayResY: {H}", "",
           "[V4+ Styles]",
           "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
           "Style: Cap,Inter ExtraBold,74,&H00FFFFFF,&H00FFFFFF,&H00000000,&H64000000,1,0,0,0,100,100,0,0,1,6,2,5,60,60,0,1",
           "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"]
    y = FILM_Y + FILM_H - 170
    for ln in lines:
        for i, w in enumerate(ln):
            s = w["start"] - start
            e = (ln[i + 1]["start"] if i + 1 < len(ln) else w["end"]) - start
            txt = " ".join(("{\\c&H8C3CFF&}" + censor(x["word"]).upper() + "{\\c&HFFFFFF&}") if j == i else censor(x["word"]).upper()
                           for j, x in enumerate(ln))
            out.append(f"Dialogue: 0,{ass_time(s)},{ass_time(max(e, s + 0.15))},Cap,,0,0,0,,{{\\pos({W // 2},{y})}}{txt}")
    open(path, "w", encoding="utf-8").write("\n".join(out) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inp"); ap.add_argument("start", type=float); ap.add_argument("end", type=float); ap.add_argument("out")
    ap.add_argument("--cam", required=True); ap.add_argument("--film", required=True)
    ap.add_argument("--title", required=True); ap.add_argument("--streamer", required=True)
    ap.add_argument("--words")
    a = ap.parse_args()
    cx, cy, cw, ch = map(int, a.cam.split(",")); fx, fy, fw, fh = map(int, a.film.split(","))
    tmp = tempfile.mkdtemp()
    ov = os.path.join(tmp, "ov.png"); overlay_png(ov, a.title, a.streamer)
    vf = (f"[0:v]split=2[a][b];"
          f"[a]crop={cw}:{ch}:{cx}:{cy},scale={W}:{CAM_H}:force_original_aspect_ratio=increase,crop={W}:{CAM_H}[cam];"
          f"[b]crop={fw}:{fh}:{fx}:{fy},scale={W}:{FILM_H}:force_original_aspect_ratio=increase,crop={W}:{FILM_H}[film];"
          f"color=c=0x0E0A1A:s={W}x{H}:r=30[bg];"
          f"[bg][cam]overlay=0:0:shortest=1[v1];[v1][film]overlay=0:{FILM_Y}[v2];[v2][1:v]overlay=0:0[v3]")
    last = "v3"
    if a.words:
        ass = os.path.join(tmp, "cap.ass"); captions_ass(ass, json.load(open(a.words)), a.start, a.end)
        vf += f";[v3]subtitles={ass}:fontsdir={F}[v4]"; last = "v4"
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", str(a.start), "-t", str(a.end - a.start), "-i", a.inp, "-i", ov,
           "-filter_complex", vf, "-map", f"[{last}]", "-map", "0:a", "-r", "30", "-t", str(a.end - a.start),
           "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", a.out]
    subprocess.run(cmd, check=True)
    print("rendered", a.out)


if __name__ == "__main__":
    main()
