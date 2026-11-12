"""Shorts design v2 (1080x1920) — modern, clean styles (owner 12.11.2026: old grunge template is «too scary»).
Five styles: clean | glass | light | neon | sticker. Panels are inset cards with rounded corners.

As a library: build(style, lines, talk) -> dict(bg=RGBA, fg=RGBA, panels=[(x,y,w,h,r)...], blur_bg=bool)
  bg   — static layer under everything (for glass/sticker it is a tint over the blurred video, blur_bg=True)
  fg   — layer over the video panels (title, borders, avatar)
  panels — where the screen / webcam panels go: [(screen), (webcam)] or [(webcam)] when talk=True
CLI mockup: python3 shorts_template2.py STYLE "LINE1|LINE2" frame_or_panels out.png [--talk]
  frame = an existing rendered short frame 1080x1920 (old layout: screen 0,380,1080x607 / cam 0,1000,1080x615,
  talk: 0,380,1080x1235) — the panels are cut from it, for quick visual comparison.
Fonts (env SHORTS_FONTS, default ./fonts): montserrat-{cyrillic,latin}-{800,900}-normal.woff, russo-one-*-400-normal.woff.
Avatar: env SHORTS_AVATAR (default assets/intro/avatar_channel.png)."""
import os, sys, math, functools
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1080, 1920
FD = os.environ.get("SHORTS_FONTS", "fonts")
AVATAR = os.environ.get("SHORTS_AVATAR", os.path.join(os.path.dirname(__file__), "..", "assets", "intro", "avatar_channel.png"))
M = 36; PW = W - 2 * M                                  # inset cards
SCR = (M, 392, PW, 567, 34); CAM = (M, 983, PW, 567, 34)  # 16:9 cards; bottom ~370px stays for the YouTube UI
TALK = (M, 392, PW, 1158, 34)
STYLES = ["clean", "glass", "light", "neon", "sticker"]

@functools.lru_cache(None)
def F(kind, sz, lat):
    name = {"m9": "montserrat-{a}-900-normal.woff", "m8": "montserrat-{a}-800-normal.woff", "russo": "russo-one-{a}-400-normal.woff"}[kind]
    return ImageFont.truetype(os.path.join(FD, name.format(a="latin" if lat else "cyrillic")), sz)
def islat(c): return not ("Ѐ" <= c <= "ӿ")
_D = ImageDraw.Draw(Image.new("L", (1, 1)))
def tw(s, kind, sz): return sum(_D.textlength(c, font=F(kind, sz, islat(c))) for c in s)
def text(s, kind, sz, fill, stroke=0, sfill=(0, 0, 0)):
    w = int(tw(s, kind, sz)) + 2 * stroke + 8; im = Image.new("RGBA", (w, int(sz * 1.45) + 2 * stroke), (0, 0, 0, 0)); d = ImageDraw.Draw(im); x = stroke + 4
    for c in s:
        f = F(kind, sz, islat(c)); d.text((x, stroke + sz * 1.08), c, font=f, fill=fill, anchor="ls", stroke_width=stroke, stroke_fill=sfill); x += d.textlength(c, font=f)
    t, b = text_box(kind, sz, stroke)
    return im.crop((0, t, im.width, b))
@functools.lru_cache(None)
def text_box(kind, sz, stroke=0):
    """common vertical crop for a font size (same for every line -> pills evenly padded): accents..descenders"""
    im = Image.new("L", (900, int(sz * 1.45) + 2 * stroke)); d = ImageDraw.Draw(im); x = stroke + 4
    for c in "ЙДЁQ":
        f = F(kind, sz, islat(c)); d.text((x, stroke + sz * 1.08), c, font=f, fill=255, anchor="ls", stroke_width=stroke); x += d.textlength(c, font=f)
    b = im.getbbox(); return b[1], b[3]
def fit_size(s, kind, sz, maxw):
    while tw(s, kind, sz) > maxw and sz > 40: sz -= 2
    return sz

def rr_mask(w, h, r, scale=4):
    m = Image.new("L", (w * scale, h * scale), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, w * scale - 1, h * scale - 1], r * scale, fill=255)
    return m.resize((w, h), Image.LANCZOS)
def pill(content, bg, padx=28, pady=16, r=None, outline=None):
    w, h = content.width + 2 * padx, content.height + 2 * pady; r = h // 2 if r is None else r
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0)); fill = Image.new("RGBA", (w, h), bg); im.paste(fill, (0, 0), rr_mask(w, h, r))
    if outline: ImageDraw.Draw(im).rounded_rectangle([1, 1, w - 2, h - 2], r, outline=outline, width=3)
    im.alpha_composite(content, (padx, pady)); return im
def shadow(w, h, r, blur=26, alpha=150, off=(0, 14)):
    pad = blur * 3; s = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    s.paste(Image.new("RGBA", (w, h), (0, 0, 0, alpha)), (pad, pad), rr_mask(w, h, r)); return s.filter(ImageFilter.GaussianBlur(blur)), pad - off[0], pad - off[1]
def vgrad(c0, c1, w=W, h=H):
    t = np.linspace(0, 1, h)[:, None, None]; a = np.array(c0, np.float32) * (1 - t) + np.array(c1, np.float32) * t
    return Image.fromarray(np.repeat(a, w, 1).astype(np.uint8)).convert("RGBA")
def blob(img, cx, cy, r, col, a):
    lay = Image.new("RGBA", img.size, (0, 0, 0, 0)); ImageDraw.Draw(lay).ellipse([cx - r, cy - r, cx + r, cy + r], fill=col + (a,))
    img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(r * 0.6)))
@functools.lru_cache(4)
def avatar(d, ring, ring_col):
    a = Image.open(AVATAR).convert("RGB").resize((d, d), Image.LANCZOS).convert("RGBA"); a.putalpha(rr_mask(d, d, d // 2))
    out = Image.new("RGBA", (d + 2 * ring, d + 2 * ring), (0, 0, 0, 0)); out.paste(Image.new("RGBA", out.size, ring_col), (0, 0), rr_mask(out.width, out.height, out.width // 2))
    out.alpha_composite(a, (ring, ring)); return out

def title_block(style, lines):
    """returns RGBA image of the title (max width W-2M)"""
    maxw = W - 2 * M - 40; l1, l2 = (lines + [""])[:2]
    if style in ("clean", "glass", "neon"):
        kind = "russo" if style == "neon" else "m9"
        s1 = fit_size(l1, kind, 84, maxw); t1 = text(l1, kind, s1, (255, 255, 255, 255))
        if style == "clean":
            s2 = fit_size(l2, "m9", 76, maxw - 60); t2 = pill(text(l2, "m9", s2, (17, 17, 17, 255)), (255, 212, 59, 255), 30, 18, 22)
        elif style == "glass":
            s2 = fit_size(l2, "m9", 80, maxw); t2 = text(l2, "m9", s2, (255, 255, 255, 255))
            g = np.array(t2, np.float32); x = np.linspace(0, 1, t2.width)[None, :, None]
            g[..., :3] = np.array([255, 120, 170]) * (1 - x) + np.array([255, 190, 90]) * x; t2 = Image.fromarray(g.astype(np.uint8))
        else:
            s2 = fit_size(l2, "russo", 80, maxw); t2 = text(l2, "russo", s2, (255, 255, 255, 255))
            g = np.array(t2, np.float32); x = np.linspace(0, 1, t2.width)[None, :, None]
            g[..., :3] = np.array([80, 230, 255]) * (1 - x) + np.array([190, 110, 255]) * x; t2 = Image.fromarray(g.astype(np.uint8))
        gap = 18
    elif style == "light":
        s1 = fit_size(l1, "m9", 84, maxw); t1 = text(l1, "m9", s1, (20, 20, 22, 255))
        s2 = fit_size(l2, "m9", 80, maxw - 40); tt = text(l2, "m9", s2, (20, 20, 22, 255))
        t2 = Image.new("RGBA", (tt.width + 40, tt.height + 20), (0, 0, 0, 0)); d = ImageDraw.Draw(t2)   # marker highlight
        d.rounded_rectangle([4, int(tt.height * 0.42) + 10, t2.width - 4, t2.height - 2], 12, fill=(255, 214, 64, 255)); t2.alpha_composite(tt, (20, 6))
        gap = 8
    else:  # sticker
        s1 = fit_size(l1, "m9", 78, maxw - 70); s2 = fit_size(l2, "m9", 78, maxw - 70)
        inner = Image.new("RGBA", (1, 1)); a_ = text(l1, "m9", s1, (17, 17, 17, 255)); b_ = text(l2, "m9", s2, (230, 40, 60, 255))
        w = max(a_.width, b_.width); inner = Image.new("RGBA", (w, a_.height + b_.height + 14), (0, 0, 0, 0))
        inner.alpha_composite(a_, ((w - a_.width) // 2, 0)); inner.alpha_composite(b_, ((w - b_.width) // 2, a_.height + 14))
        st = pill(inner, (255, 255, 255, 255), 40, 30, 34); return st.rotate(-2.2, expand=True, resample=Image.BICUBIC)
    w = max(t1.width, t2.width); out = Image.new("RGBA", (w, t1.height + gap + t2.height), (0, 0, 0, 0))
    out.alpha_composite(t1, ((w - t1.width) // 2, 0)); out.alpha_composite(t2, ((w - t2.width) // 2, t1.height + gap)); return out

def build(style, lines, talk=False):
    panels = [TALK] if talk else [SCR, CAM]; blur_bg = style in ("glass", "sticker")
    if style == "clean":
        bg = vgrad((16, 16, 20), (26, 26, 34)); blob(bg, 900, 120, 260, (120, 70, 255), 90); blob(bg, 120, 1800, 300, (255, 170, 40), 40)
    elif style == "glass":
        bg = vgrad((20, 10, 40), (40, 10, 30)); bg.putalpha(150)                       # tint over the blurred video
    elif style == "light":
        bg = vgrad((246, 244, 238), (236, 233, 226)); d = ImageDraw.Draw(bg)
        for y in range(20, H, 36):
            for x in range(20, W, 36): d.ellipse([x - 1.5, y - 1.5, x + 1.5, y + 1.5], fill=(205, 200, 190, 255))
    elif style == "neon":
        bg = vgrad((8, 8, 22), (14, 6, 30)); blob(bg, 160, 300, 320, (60, 200, 255), 70); blob(bg, 940, 1500, 360, (170, 80, 255), 80)
    else:
        bg = vgrad((0, 0, 0), (0, 0, 0)); bg.putalpha(95)
    fg = Image.new("RGBA", (W, H), (0, 0, 0, 0)); under = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for x, y, w, h, r in panels:                                                    # shadows go under the panels
        s, ox, oy = shadow(w, h, r, alpha=90 if style == "light" else 170); under.alpha_composite(s, (x - ox, y - oy))
        d = ImageDraw.Draw(fg)
        if style == "clean": d.rounded_rectangle([x, y, x + w - 1, y + h - 1], r, outline=(255, 255, 255, 40), width=2)
        if style == "glass": d.rounded_rectangle([x, y, x + w - 1, y + h - 1], r, outline=(255, 255, 255, 110), width=3)
        if style == "light": d.rounded_rectangle([x, y, x + w - 1, y + h - 1], r, outline=(255, 255, 255, 255), width=6)
        if style == "sticker": d.rounded_rectangle([x - 3, y - 3, x + w + 2, y + h + 2], r + 3, outline=(255, 255, 255, 255), width=8)
        if style == "neon":
            glow = Image.new("RGBA", (W, H), (0, 0, 0, 0)); gd = ImageDraw.Draw(glow)
            gd.rounded_rectangle([x - 2, y - 2, x + w + 1, y + h + 1], r + 2, outline=(150, 110, 255, 255), width=5)
            fg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(10))); fg.alpha_composite(glow)
    bg.alpha_composite(under) if not blur_bg else None
    if blur_bg: bg = Image.alpha_composite(bg, under)
    tb = title_block(style, lines)
    if style == "glass":                                                          # frosted card behind the title
        cw, ch = W - 2 * M, max(tb.height + 70, 300); card = Image.new("RGBA", (cw, ch), (255, 255, 255, 38))
        c2 = Image.new("RGBA", (cw, ch), (0, 0, 0, 0)); c2.paste(card, (0, 0), rr_mask(cw, ch, 34)); ImageDraw.Draw(c2).rounded_rectangle([1, 1, cw - 2, ch - 2], 34, outline=(255, 255, 255, 90), width=2)
        fg.alpha_composite(c2, (M, (392 - ch) // 2 + 4))
    fg.alpha_composite(tb, ((W - tb.width) // 2, max(18, (392 - tb.height) // 2 + 6)))
    ring_col = {"clean": (255, 255, 255, 255), "glass": (255, 255, 255, 200), "light": (20, 20, 22, 255), "neon": (150, 110, 255, 255), "sticker": (255, 255, 255, 255)}[style]
    av = avatar(108, 5, ring_col); ybot = (talk and TALK[1] + TALK[3]) or (CAM[1] + CAM[3])
    fg.alpha_composite(av, ((W - av.width) // 2, ybot + 34))
    return dict(bg=bg, fg=fg, panels=panels, blur_bg=blur_bg)

def blurred_cover(src, darken=0.55):
    s = max(W / src.width, H / src.height); im = src.resize((int(src.width * s) + 1, int(src.height * s) + 1), Image.BILINEAR)
    im = im.crop(((im.width - W) // 2, (im.height - H) // 2, (im.width - W) // 2 + W, (im.height - H) // 2 + H)).filter(ImageFilter.GaussianBlur(42))
    return Image.eval(im, lambda v: int(v * darken)).convert("RGBA")

def compose(style, lines, panel_imgs, talk=False, cover_src=None):
    """mockup / single frame: panel_imgs = [screen, cam] or [cam] (PIL images, any size; cropped to fill)"""
    t = build(style, lines, talk)
    base = blurred_cover(cover_src or panel_imgs[0]) if t["blur_bg"] else Image.new("RGBA", (W, H), (0, 0, 0, 255))
    base.alpha_composite(t["bg"])
    for (x, y, w, h, r), im in zip(t["panels"], panel_imgs):
        s = max(w / im.width, h / im.height); p = im.convert("RGBA").resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
        p = p.crop(((p.width - w) // 2, (p.height - h) // 2, (p.width - w) // 2 + w, (p.height - h) // 2 + h)); base.paste(p, (x, y), rr_mask(w, h, r))
    base.alpha_composite(t["fg"]); return base.convert("RGB")

if __name__ == "__main__":
    style, title, frame, out = sys.argv[1:5]; talk = "--talk" in sys.argv
    fr = Image.open(frame).convert("RGB")
    panels = [fr.crop((0, 380, 1080, 1615))] if talk else [fr.crop((0, 380, 1080, 987)), fr.crop((0, 1000, 1080, 1615))]
    compose(style, title.split("|"), panels, talk).save(out, quality=92); print("ok", out)
