"""Style 2 (simple TikTok): caption overlay PNG 1080x1920 — two lines, line 1 red, line 2 white, thick black outline,
centred at y=Y (the seam between webcam and screen). usage: python3 tt_v2_overlay.py "LINE1|LINE2" out.png [Y]"""
import sys, os, glob
from PIL import Image, ImageDraw, ImageFont
FD = os.environ.get("SHORTS_FONTS", "/tmp/claude-0/-home-user-Pol/21f855aa-1037-5ef8-98f2-de37327dd4d8/scratchpad/fonts/all")
_F = {}
def _f(kind, s):
    k = (kind, s)
    if k not in _F: _F[k] = ImageFont.truetype(f"{FD}/montserrat-{kind}-900-normal.woff", s)
    return _F[k]
font = lambda s: s  # size only; glyphs are drawn per char (cyrillic file has no ?!.,— etc.)
pick = lambda ch, s: _f("cyrillic" if "\u0400" <= ch <= "\u04ff" else "latin", s)
def tlen(t, s): return sum(d.textlength(ch, font=pick(ch, s)) for ch in t)
l = sys.argv[1].split("|"); out = sys.argv[2]; Y = int(sys.argv[3]) if len(sys.argv) > 3 else 950
im = Image.new("RGBA", (1080, 1920), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
def fit(t, s):
    while tlen(t, s) > 1000 and s > 30: s -= 2
    return s
cols = [(255, 45, 45), (255, 255, 255)]
lines = [(t, fit(t, 66), cols[min(i, 1)]) for i, t in enumerate(l)]
h = sum(f + 10 for _, f, _ in lines); y = Y - h + 30
for t, f, c in lines:
    x = (1080 - tlen(t, f)) / 2
    for ch in t:
        d.text((x, y), ch, font=pick(ch, f), fill=c, stroke_width=7, stroke_fill=(0, 0, 0)); x += d.textlength(ch, font=pick(ch, f))
    y += f + 10
im.save(out)
