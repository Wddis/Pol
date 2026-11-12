"""Alternative channel intros for «Двойная резка / T2x2» (1920x1080 @60, original animation + synthesized sound).
Same idea as make_intro.py (greeting voice on the head -> hit -> info cards: T2X2 · АНТОН / twitch / more clips),
three different formats:
  rezka — black-and-white like the channel logo: a blade slash cuts the frame in two, the logo slams in,
          cards wipe in along the cut, phonk cowbell beat
  chat  — stream layout: webcam + live chat that greets him, pinned "alerts" with the info, lo-fi beat
  tv    — retro CRT/VHS: TV turns on, OSD «КАНАЛ T2X2», channel switching with static, teletext typing, synthwave
usage: python3 make_intro_alt.py STYLE head.png avatar.png fonts_dir voice.wav out.mp4
fonts_dir: rubik-dirt / russo-one / montserrat (600/800/900) woff files (cyrillic + latin)
STILLS="0.5,4.2" -> png stills only."""
import sys, os, math, wave, subprocess, functools, numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from scipy.signal import lfilter

STYLE, HEAD, AVATAR, FD, VOICE, OUT = sys.argv[1:7]
W, H, FPS = 1920, 1080, 60
rng = np.random.default_rng(5)

with wave.open(VOICE) as vw:
    vsr = vw.getframerate(); vx = np.frombuffer(vw.readframes(vw.getnframes()), np.int16).astype(np.float32) / 32768
    vx = vx.reshape(-1, vw.getnchannels()).mean(1)
VDUR = len(vx) / vsr
D = 0.12 + VDUR + 0.05
hop = vsr // FPS
VENV = np.array([np.sqrt(np.mean(vx[i:i + hop] ** 2)) for i in range(0, len(vx), hop)]); VENV /= VENV.max() + 1e-9
def venv(t):
    i = int((t - 0.12) * FPS); return float(VENV[i]) if 0 <= i < len(VENV) else 0.0
TB = {"rezka": 6.9, "chat": 6.6, "tv": 6.8}[STYLE]
T = D + TB; NF = int(T * FPS)

def clamp(x): return min(max(x, 0.0), 1.0)
def ease_io(x): x = clamp(x); return x * x * (3 - 2 * x)
def ease_out(x): x = clamp(x); return 1 - (1 - x) ** 3
def ease_out_back(x, s=1.7): x = clamp(x) - 1; return 1 + (s + 1) * x ** 3 + s * x ** 2
def lerp(a, b, k): return a + (b - a) * k

# ------------------------------------------------------------------ text
FONTS = {"dirt": "rubik-dirt-{a}-400-normal.woff", "russo": "russo-one-{a}-400-normal.woff",
         "m6": "montserrat-{a}-600-normal.woff", "m8": "montserrat-{a}-800-normal.woff", "m9": "montserrat-{a}-900-normal.woff"}
@functools.lru_cache(None)
def F(kind, sz, lat): return ImageFont.truetype(f"{FD}/" + FONTS[kind].format(a="latin" if lat else "cyrillic"), sz)
def islat(c): return not ("Ѐ" <= c <= "ӿ")
_D = ImageDraw.Draw(Image.new("L", (1, 1)))
@functools.lru_cache(None)
def timg(s, kind, sz, fill=(255, 255, 255), stroke=0, sfill=(0, 0, 0)):
    """tight RGBA image of a text line (per-char font: cyrillic/latin files)"""
    w = int(sum(_D.textlength(c, font=F(kind, sz, islat(c))) for c in s)) + 2 * stroke + 8
    im = Image.new("RGBA", (w, int(sz * 1.5) + 2 * stroke), (0, 0, 0, 0)); d = ImageDraw.Draw(im); x = stroke + 4
    for c in s:
        f = F(kind, sz, islat(c))
        d.text((x, stroke + sz * 1.1), c, font=f, fill=fill + (255,), anchor="ls", stroke_width=stroke, stroke_fill=sfill + (255,))
        x += d.textlength(c, font=f)
    bb = im.getbbox()
    return im.crop(bb) if bb else im

def with_alpha(im, a):
    if a >= 0.999: return im
    im = im.copy(); im.putalpha(im.getchannel("A").point(lambda v: int(v * a))); return im
def paste_c(canvas, im, cx, cy, a=1.0):
    if a <= 0.003: return
    im = with_alpha(im, a); canvas.paste(im, (int(round(cx - im.width / 2)), int(round(cy - im.height / 2))), im)
def paste_tl(canvas, im, x, y, a=1.0):
    if a <= 0.003: return
    im = with_alpha(im, a); canvas.paste(im, (int(round(x)), int(round(y))), im)

def stack(items, gap=14, align="left"):
    """items: list of (img, dx) -> one RGBA image, top to bottom"""
    w = max(im.width + dx for im, dx in items); h = sum(im.height for im, _ in items) + gap * (len(items) - 1)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0)); y = 0
    for im, dx in items:
        x = dx if align == "left" else (w - im.width) // 2
        out.alpha_composite(im, (x, y)); y += im.height + gap
    return out
def hjoin(items, gap=30):
    w = sum(im.width for im in items) + gap * (len(items) - 1); h = max(im.height for im in items)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0)); x = 0
    for im in items: out.alpha_composite(im, (x, (h - im.height) // 2)); x += im.width + gap
    return out
def fit(im, maxw):
    return im if im.width <= maxw else im.resize((maxw, round(im.height * maxw / im.width)), Image.LANCZOS)

# ------------------------------------------------------------------ assets
head0 = Image.open(HEAD).convert("RGBA")
@functools.lru_cache(512)
def head_img(scale_q, ang_q):
    h = 430 * scale_q / 100
    im = head0.resize((max(2, round(head0.width * h / head0.height)), max(2, round(h))), Image.BILINEAR)
    if ang_q: im = im.rotate(ang_q / 10, expand=True, resample=Image.BICUBIC)
    return im
@functools.lru_cache(64)
def head_shadow(scale_q):
    im = head_img(scale_q, 0); sh = Image.new("RGBA", im.size, (0, 0, 0, 0)); sh.putalpha(im.getchannel("A").point(lambda a: a * 0.5))
    pad = 40; big = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0)); big.alpha_composite(sh, (pad, pad))
    return big.filter(ImageFilter.GaussianBlur(16))
def draw_head(canvas, cx, cy, scale, ang=0.0, shadow=True, a=1.0):
    sq = int(round(scale * 100)); im = head_img(sq, int(round(ang * 10)))
    if shadow: paste_c(canvas, head_shadow(sq), cx + 14, cy + 20, a)
    paste_c(canvas, im, cx, cy, a)

AV = Image.open(AVATAR).convert("RGB")
@functools.lru_cache(8)
def avatar_round(d, ring=8, ring_col=(255, 255, 255)):
    a = AV.resize((d, d), Image.LANCZOS).convert("RGBA"); m = Image.new("L", (d, d), 0); ImageDraw.Draw(m).ellipse([0, 0, d - 1, d - 1], fill=255); a.putalpha(m)
    out = Image.new("RGBA", (d + 2 * ring, d + 2 * ring), (0, 0, 0, 0)); ImageDraw.Draw(out).ellipse([0, 0, d + 2 * ring - 1, d + 2 * ring - 1], fill=ring_col + (255,))
    out.alpha_composite(a, (ring, ring)); return out
def twitch_glyph(hgt, col=(145, 70, 255)):
    s = hgt / 100; im = Image.new("RGBA", (int(86 * s) + 2, int(101 * s) + 2), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    P = lambda pts: [(x * s, y * s) for x, y in pts]
    d.polygon(P([(0, 14), (14, 0), (84, 0), (84, 58), (56, 86), (40, 86), (26, 100), (26, 86), (0, 86)]), fill=col + (255,))
    d.rectangle(P([(34, 22), (42, 50)]), fill=(255, 255, 255, 255)); d.rectangle(P([(56, 22), (64, 50)]), fill=(255, 255, 255, 255))
    return im

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
VIGN = (1 - 0.42 * np.clip(((xx - W / 2) / (W * 0.62)) ** 2 + ((yy - H / 2) / (H * 0.62)) ** 2, 0, 1))[..., None]

# ================================================================== STYLE: rezka
if STYLE == "rezka":
    ANG = math.radians(18); DX, DY = math.cos(ANG), math.sin(ANG); NX, NY = -DY, DX
    SD = (xx - 960) * NX + (yy - 540) * NY                              # signed distance to the cut line
    TOP = (SD < 0)
    RED = (232, 28, 44)
    # shard rays (like the logo)
    rays = Image.new("L", (W, H), 0); dr = ImageDraw.Draw(rays)
    for i in range(34):
        side = 0 if i % 2 else math.pi
        a = side + rng.normal(0, 0.42); r0 = rng.uniform(260, 420); r1 = rng.uniform(900, 1400); wd = rng.uniform(0.006, 0.03)
        p0 = (960 + r0 * math.cos(a), 540 + r0 * math.sin(a))
        dr.polygon([p0, (960 + r1 * math.cos(a - wd), 540 + r1 * math.sin(a - wd)), (960 + r1 * math.cos(a + wd), 540 + r1 * math.sin(a + wd))], fill=255)
    rays = rays.filter(ImageFilter.GaussianBlur(1.2))
    RAYS = np.array(rays, np.float32)[..., None] / 255
    LOGO = AV.resize((1080, 1080), Image.LANCZOS)
    shards = [(rng.uniform(0, 2 * math.pi), rng.uniform(900, 2300), rng.uniform(10, 34), rng.uniform(-9, 9)) for _ in range(46)]

    def bar(text, sz, bg=(255, 255, 255), fg=(0, 0, 0)):
        t = timg(text, "russo", sz, fg); sk = int(sz * 0.45); pad = int(sz * 0.45); h = t.height + 2 * int(sz * 0.32)
        im = Image.new("RGBA", (t.width + 2 * pad + sk, h), (0, 0, 0, 0)); w = im.width
        ImageDraw.Draw(im).polygon([(sk, 0), (w, 0), (w - sk, h), (0, h)], fill=bg + (255,))
        im.alpha_composite(t, (pad + sk // 2, (h - t.height) // 2)); return im
    def stripe(w, h=18, col=RED):
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0)); ImageDraw.Draw(im).polygon([(h, 0), (w, 0), (w - h, h), (0, h)], fill=col + (255,)); return im
    L1 = timg("T2X2 · АНТОН", "dirt", 132)
    C1 = stack([(L1, 0), (stripe(int(L1.width * 0.62)), 26), (bar("РЕАКЦИИ, ИГРЫ", 46), 6), (bar("И ОБЩЕНИЕ С ЧАТОМ", 46), 40)], gap=16)
    C2 = hjoin([twitch_glyph(150), stack([(timg("TWITCH.TV/", "russo", 60), 6), (timg("T2X2", "dirt", 176), 0), (stripe(300), 10)], gap=14)], gap=34)
    C3 = hjoin([avatar_round(200), stack([(timg("БОЛЬШЕ НАРЕЗОК", "dirt", 92), 0), (stripe(420), 14), (bar("НА КАНАЛЕ «ДВОЙНАЯ РЕЗКА»", 40), 4)], gap=16)], gap=34)
    CARDS = [(fit(C1, 1060), 1.70, 3.45), (fit(C2, 1060), 3.45, 4.95), (fit(C3, 1060), 4.95, 6.35)]

    @functools.lru_cache(8)
    def wipe_grid(w, h):
        y, x = np.mgrid[0:h, 0:w].astype(np.float32); return x + 0.45 * (h - y), w + 0.45 * h
    def wiped(card, k_in, k_out):
        u, span = wipe_grid(card.width, card.height); a = np.array(card, np.float32)
        vis = (u < k_in * span) & (u > k_out * span)
        edge = ((np.abs(u - k_in * span) < 10) & (k_in < 1)) | ((np.abs(u - k_out * span) < 10) & (k_out > 0))
        a[..., 3] *= vis
        a[edge] = [255, 255, 255, 255]
        return Image.fromarray(a.astype(np.uint8), "RGBA")

    def grain(img, amt=9):
        n = rng.integers(-amt, amt + 1, (H // 2, W // 2, 1), dtype=np.int16).repeat(2, 0).repeat(2, 1)
        return np.clip(img.astype(np.int16) + n, 0, 255).astype(np.uint8)

    def phaseA(t, line_k=None):
        e = venv(t); base = np.zeros((H, W, 3), np.float32)
        ra = (0.10 + 0.55 * e) * (ease_out(t / 0.25))
        base += 255 * RAYS * ra
        img = Image.fromarray(base.astype(np.uint8), "RGB")
        pop = ease_out_back(t / 0.18, 1.6) if t < 0.18 else 1.0
        s = max(0.05, 1.35 * pop * (1 + 0.07 * e))
        draw_head(img, 960, 540 - 10 * e, s, math.sin(t * 11) * 4 * e)
        return img

    def logo_frame(tb):
        """black + logo (slam / full / watermark)"""
        img = Image.new("RGB", (W, H), 0)
        if tb < 0.50: s = lerp(1.7, 1.0, ease_out(tb / 0.50)); a = clamp(tb / 0.10)
        elif tb < 1.30: s = 1.0 + 0.04 * (tb - 0.5) / 0.8; a = 1
        else: s = lerp(1.032, 1.18, ease_io((tb - 1.3) / 0.35)); a = lerp(1, 0.16, ease_io((tb - 1.3) / 0.35))
        sh = 22 * math.exp(-max(0, tb - 0.45) * 7) * (tb > 0.45)
        ox, oy = sh * math.sin(tb * 91), sh * math.cos(tb * 73)
        L = LOGO.resize((int(1080 * s), int(1080 * s)), Image.BILINEAR)
        if a < 1: L = Image.eval(L, lambda v: int(v * a))
        img.paste(L, (int(960 - L.width / 2 + ox), int(540 - L.height / 2 + oy)))
        if 0.45 <= tb < 1.6:                                           # shards flying out
            d = ImageDraw.Draw(img); tau = tb - 0.45
            for ang, sp, sz, rs in shards:
                r = 150 + sp * tau * (1 - 0.25 * tau); cx, cy = 960 + r * math.cos(ang), 540 + r * math.sin(ang) * 0.6
                aa = ang + rs * tau; al = clamp(1 - tau / 1.0)
                pts = [(cx + sz * math.cos(aa + k * 2.3), cy + sz * 0.5 * math.sin(aa + k * 2.3)) for k in range(3)]
                d.polygon(pts, fill=(int(255 * al),) * 3)
        return img

    def main(tb):
        img = logo_frame(tb)
        if tb >= 1.35:                                                  # head enters from below
            k = ease_out_back((tb - 1.35) / 0.40, 1.4); hop_ = 0.0
            for _, t0, _ in CARDS[1:]:
                if t0 <= tb < t0 + 0.25: hop_ = math.sin(math.pi * (tb - t0) / 0.25)
            bob = math.sin(tb * 2 * math.pi * 0.95) * 7
            draw_head(img, 520, lerp(1500, 590, k) + bob - 40 * hop_, 1.32 * (1 + 0.04 * hop_), math.sin(tb * 3.8) * 2.5)
        for card, t0, t1 in CARDS:
            if t0 <= tb < t1 + 0.22:
                kin = ease_io((tb - t0) / 0.24); kout = ease_io((tb - t1) / 0.22) if tb >= t1 else 0
                if kout >= 1: continue
                im = wiped(card, kin, kout); cx = 790 + (1080 - card.width) / 2
                paste_tl(img, im, cx, 540 - card.height / 2 + 20)
        return img

    def slash_line(img, k, glow=True):
        lay = Image.new("L", (W, H), 0); d = ImageDraw.Draw(lay)
        p0 = (960 - DX * 1400, 540 - DY * 1400); p1 = (p0[0] + DX * 2800 * k, p0[1] + DY * 2800 * k)
        d.line([p0, p1], fill=255, width=7)
        g = lay.filter(ImageFilter.GaussianBlur(14)); a = np.array(img, np.float32)
        a += np.array(lay, np.float32)[..., None] + 1.6 * np.array(g, np.float32)[..., None] * np.array([1, 0.55, 0.6])
        return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))

    def shift(a, dy, dx):
        out = np.zeros_like(a); h, w = a.shape[:2]
        if abs(dy) >= h or abs(dx) >= w: return out
        ys, yd = (slice(0, h - dy), slice(dy, h)) if dy >= 0 else (slice(-dy, h), slice(0, h + dy))
        xs_, xd = (slice(0, w - dx), slice(dx, w)) if dx >= 0 else (slice(-dx, w), slice(0, w + dx))
        out[yd, xd] = a[ys, xs_]; return out
    def split(src, back, d):
        A = np.array(src, np.float32); B = np.array(back, np.float32)
        out = B.copy()
        for sgn, m in ((-1, TOP), (1, ~TOP)):
            dy, dx = int(round(sgn * (NY * d + DY * 0.25 * d))), int(round(sgn * (NX * d + DX * 0.25 * d)))
            ms = shift(m[..., None].astype(np.float32), dy, dx); out = out * (1 - ms) + shift(A, dy, dx) * ms
        return Image.fromarray(out.astype(np.uint8))

    _cache = {}
    def frame(t):
        if t < D: return grain(np.array(phaseA(t)))
        tb = t - D
        if tb < 0.16: return grain(np.array(slash_line(phaseA(D - 0.001), ease_out(tb / 0.16))))
        if tb < 0.50:
            if "A" not in _cache: _cache["A"] = slash_line(phaseA(D - 0.001), 1)
            back = np.array(logo_frame(tb), np.float32)
            fl = clamp(1 - (tb - 0.16) / 0.12); back = back + (255 - back) * fl * 0.85
            return grain(np.array(split(_cache["A"], Image.fromarray(back.astype(np.uint8)), ease_io((tb - 0.16) / 0.34) * 900)))
        if tb < 6.35: return grain(np.array(main(tb)))
        if "E" not in _cache: _cache["E"] = main(6.35)
        if tb < 6.47: return grain(np.array(slash_line(_cache["E"], ease_out((tb - 6.35) / 0.12))))
        if "E2" not in _cache: _cache["E2"] = slash_line(_cache["E"], 1)
        d = ease_io((tb - 6.47) / 0.33) * 1100
        out = np.array(split(_cache["E2"], Image.new("RGB", (W, H), 0), d), np.float32)
        if tb > 6.75: out *= clamp(1 - (tb - 6.75) / 0.12)
        return grain(out.astype(np.uint8))

# ================================================================== STYLE: chat
if STYLE == "chat":
    CAM = (90, 120, 1250, 960); CHAT = (1290, 120, 1830, 960); PUR = (145, 70, 255)
    cx_, cy_ = (CAM[0] + CAM[2]) / 2, (CAM[1] + CAM[3]) / 2
    r = np.sqrt(((xx - cx_) / 700) ** 2 + ((yy - cy_) / 500) ** 2)
    camg = (np.array([70, 30, 125], np.float32) * (1 - np.clip(r, 0, 1))[..., None] + np.array([22, 12, 38], np.float32) * np.clip(r, 0, 1)[..., None])
    BASE = Image.new("RGB", (W, H), (14, 14, 16))
    m = Image.new("L", (W, H), 0); ImageDraw.Draw(m).rounded_rectangle(CAM, 24, fill=255)
    BASE.paste(Image.fromarray(camg.astype(np.uint8)), (0, 0), m)
    d0 = ImageDraw.Draw(BASE)
    d0.rounded_rectangle(CAM, 24, outline=PUR, width=6)
    d0.rounded_rectangle(CHAT, 18, fill=(24, 24, 27))
    BASE.paste(timg("ЧАТ СТРИМА", "m8", 30, (220, 220, 230)), (CHAT[0] + 28, CHAT[1] + 26), timg("ЧАТ СТРИМА", "m8", 30, (220, 220, 230)))
    d0.line([(CHAT[0], CHAT[1] + 84), (CHAT[2], CHAT[1] + 84)], fill=(50, 50, 56), width=2)
    camMask = np.array(m, np.float32)[..., None] / 255
    NICKC = [(255, 99, 99), (99, 199, 255), (120, 230, 120), (255, 190, 70), (210, 120, 255), (255, 120, 200), (90, 230, 210)]
    A_MSG = [("mrakobes", "ПРИВЕТ АНТОН"), ("kotlin_bro", "здравствуйте!!"), ("vasya228", "ой ну что"), ("Lena_K", "o/ o/ o/"),
             ("t2x2_fan", "ЗДРАВСТВУЙТЕ РЕБЯТА"), ("zxcursed", "начинается"), ("pelmen", "привет из Самары"), ("abobus", "ДАРОВА"),
             ("ded_inside", "наконец-то стрим"), ("sanya_tv", "ЛЕГЕНДА")]
    B_MSG = [(0.15, "novichok", "а кто это?"), (0.95, "mrakobes", "ты чё, это же Антон"), (1.35, "Lena_K", "лучший стример"),
             (2.05, "kotlin_bro", "где смотреть?"), (2.75, "pelmen", "!twitch"), (3.05, "abobus", "!twitch"),
             (3.95, "vasya228", "а нарезки есть?"), (4.65, "t2x2_fan", "подписался!"), (4.95, "zxcursed", "+1 подписчик"),
             (5.25, "ded_inside", "ДВОЙНАЯ РЕЗКА ТОП"), (5.55, "sanya_tv", "подписался")]
    MSGS = [(0.35 + i * (D - 0.6) / len(A_MSG), n, s) for i, (n, s) in enumerate(A_MSG)] + [(D + tb, n, s) for tb, n, s in B_MSG]
    MSGS = [(t, n, s, NICKC[hash(n) % len(NICKC)] if False else NICKC[sum(map(ord, n)) % len(NICKC)]) for t, n, s in MSGS]
    @functools.lru_cache(None)
    def msg_img(n, s, col):
        a = timg(n + ":", "m8", 28, col); words = s.split(" "); lines = []; cur = ""
        maxw = CHAT[2] - CHAT[0] - 60
        first_w = maxw - a.width - 12
        for w_ in words:
            test = (cur + " " + w_).strip()
            lim = first_w if not lines else maxw
            if timg(test, "m6", 28, (235, 235, 240)).width > lim and cur: lines.append(cur); cur = w_
            else: cur = test
        lines.append(cur)
        rows = [hjoin([a, timg(lines[0], "m6", 28, (235, 235, 240))], gap=12)] + [timg(l, "m6", 28, (235, 235, 240)) for l in lines[1:]]
        return stack([(r_, 0) for r_ in rows], gap=10)
    def chat_layer(img, t):
        shown = [m_ for m_ in MSGS if m_[0] <= t]; y = CHAT[3] - 24
        for tm, n, s, col in reversed(shown):
            im = msg_img(n, s, col); age = t - tm; k = ease_out(age / 0.16)
            y -= im.height; yy_ = y + (1 - k) * 26
            if yy_ < CHAT[1] + 100: break
            hl = "?" in s
            if hl: ImageDraw.Draw(img).rounded_rectangle([CHAT[0] + 14, yy_ - 8, CHAT[2] - 14, yy_ + im.height + 8], 10, fill=(48, 34, 72))
            paste_tl(img, im, CHAT[0] + 30, yy_, k)
            y -= 20
    def live_badges(img, t):
        d = ImageDraw.Draw(img); x0, y0 = CAM[0] + 28, CAM[1] + 26
        t1 = timg("В ЭФИРЕ", "m9", 28, (255, 255, 255)); d.rounded_rectangle([x0, y0, x0 + t1.width + 62, y0 + 50], 10, fill=(233, 25, 22))
        d.ellipse([x0 + 16, y0 + 17, x0 + 32, y0 + 33], fill=(255, 255, 255) if (t * 2) % 2 < 1.3 else (255, 160, 160)); paste_tl(img, t1, x0 + 44, y0 + 25 - t1.height / 2)
        v = f"{12480 + int(t * 173):,}".replace(",", " "); t2 = timg(v, "m8", 28, (255, 255, 255)); x1 = x0 + t1.width + 80
        d.rounded_rectangle([x1, y0, x1 + t2.width + 74, y0 + 50], 10, fill=(0, 0, 0)); d.ellipse([x1 + 14, y0 + 15, x1 + 46, y0 + 35], outline=(255, 255, 255), width=3)
        d.ellipse([x1 + 25, y0 + 20, x1 + 35, y0 + 30], fill=(255, 255, 255)); paste_tl(img, t2, x1 + 58, y0 + 25 - t2.height / 2)
    def alert(content):
        pad = 40; lab = timg("ЗАКРЕПЛЕНО", "m8", 24, (255, 255, 255))
        w = max(content.width, 640) + 2 * pad; h = content.height + 2 * pad + 20
        im = Image.new("RGBA", (w, h + 26), (0, 0, 0, 0)); g = np.zeros((h, w, 4), np.float32)
        gx = np.linspace(0, 1, w)[None, :, None]
        g[..., :3] = np.array([120, 50, 235]) * (1 - gx) + np.array([200, 60, 200]) * gx; g[..., 3] = 255
        gi = Image.fromarray(g.astype(np.uint8), "RGBA"); mk = Image.new("L", (w, h), 0); ImageDraw.Draw(mk).rounded_rectangle([0, 0, w - 1, h - 1], 26, fill=255)
        im.paste(gi, (0, 26), mk); ImageDraw.Draw(im).rounded_rectangle([0, 26, w - 1, h + 25], 26, outline=(255, 255, 255), width=4)
        ImageDraw.Draw(im).rounded_rectangle([30, 0, 30 + lab.width + 36, 48], 12, fill=(255, 255, 255))
        im.alpha_composite(timg("ЗАКРЕПЛЕНО", "m8", 24, (120, 50, 235)), (48, 24 - lab.height // 2))
        im.alpha_composite(content, ((w - content.width) // 2, 26 + (h - content.height) // 2 + 6)); return im
    AL = [(alert(stack([(timg("T2X2 · АНТОН", "m9", 84), 0), (timg("реакции, игры и общение с чатом", "m6", 40, (245, 235, 255)), 0)], gap=18, align="center")), 0.45, 2.00),
          (alert(hjoin([twitch_glyph(110, (255, 255, 255)), timg("twitch.tv/T2X2", "m9", 84)], gap=28)), 2.35, 3.85),
          (alert(hjoin([avatar_round(130, 6), stack([(timg("Больше нарезок на канале", "m8", 44), 0), (timg("«ДВОЙНАЯ РЕЗКА»", "m9", 70), 0)], gap=14)], gap=30)), 4.25, 6.00)]
    hearts = [(rng.uniform(CAM[0] + 60, CAM[2] - 60), rng.uniform(4.3, 5.9), rng.uniform(150, 330), rng.uniform(26, 50), rng.uniform(0, 6)) for _ in range(26)]
    def heart(d, x, y, s, col):
        d.ellipse([x - s, y - s * 0.6, x, y + s * 0.4], fill=col); d.ellipse([x, y - s * 0.6, x + s, y + s * 0.4], fill=col)
        d.polygon([(x - s * 0.97, y), (x + s * 0.97, y), (x, y + s * 1.1)], fill=col)
    def content(t):
        img = BASE.copy(); e = venv(t) if t < D else 0.0; tb = t - D
        hop_ = 0.0
        for _, t0, _ in AL:
            if 0 <= tb - t0 < 0.3: hop_ = math.sin(math.pi * (tb - t0) / 0.3)
        lay = Image.new("RGB", (W, H), 0); lm = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        pop = ease_out_back(t / 0.2, 1.5) if t < 0.2 else 1.0
        bob = math.sin(t * 2 * math.pi * 0.9) * 6
        draw_head(lm, 670, 585 + bob - 12 * e - 30 * hop_, 1.32 * pop * (1 + 0.06 * e + 0.04 * hop_), math.sin(t * 11) * 4 * e + math.sin(t * 3.7) * 2)
        a = np.array(lm, np.float32); al = a[..., 3:] / 255 * camMask
        out = np.array(img, np.float32) * (1 - al) + a[..., :3] * al
        img = Image.fromarray(out.astype(np.uint8))
        if tb > 4.25:
            d = ImageDraw.Draw(img)
            for x, t0, sp, s, ph in hearts:
                if tb >= t0:
                    u = tb - t0; y = CAM[3] - 40 - sp * u
                    if y > CAM[1] + 40: heart(d, x + 18 * math.sin(u * 4 + ph), y, s * (0.6 + 0.4 * clamp(u / 0.2)), (255, 60, 90))
        live_badges(img, t); chat_layer(img, t)
        for im, t0, t1 in AL:
            if t0 <= tb < t1 + 0.2:
                k = ease_out_back((tb - t0) / 0.32, 1.6); ko = 1 - ease_io((tb - t1) / 0.2) if tb >= t1 else 1
                s = max(0.05, k * (0.8 + 0.2 * ko)); a_ = ko
                im2 = im.resize((max(2, int(im.width * s)), max(2, int(im.height * s))), Image.BILINEAR) if abs(s - 1) > 0.002 else im
                paste_c(img, im2, 670, 780, a_)
        return img
    def frame(t):
        tb = t - D; img = content(t)
        if t < 0.18: return (np.array(img, np.float32) * ease_io(t / 0.18)).astype(np.uint8)
        z = 1.0
        if 0 <= tb < 0.25: z = 1 + 0.05 * (1 - ease_out(tb / 0.25))
        if tb >= 6.0: z = 1 + 0.35 * ease_io((tb - 6.0) / 0.6)
        if z > 1.0005:
            cx, cy = (670, 540) if tb >= 6.0 else (960, 540); w2, h2 = W / z, H / z
            img = img.crop((cx - w2 / 2, cy - h2 / 2, cx + w2 / 2, cy + h2 / 2)).resize((W, H), Image.BILINEAR)
        a = np.array(img, np.float32)
        if tb >= 6.15: a *= clamp(1 - (tb - 6.15) / 0.4)
        return a.astype(np.uint8)

# ================================================================== STYLE: tv
if STYLE == "tv":
    GREEN = (110, 255, 140); YEL = (255, 220, 60)
    def grad(c0, c1):
        r = np.clip(np.sqrt(((xx - 960) / 1100) ** 2 + ((yy - 540) / 700) ** 2), 0, 1)[..., None]
        return Image.fromarray((np.array(c0, np.float32) * (1 - r) + np.array(c1, np.float32) * r).astype(np.uint8))
    BG_A = grad((40, 70, 95), (8, 14, 22)); BG = [grad((95, 30, 140), (18, 6, 30)), grad((30, 50, 150), (6, 10, 32)), grad((60, 60, 60), (6, 6, 6))]
    def osd(img, s, x, y, sz=48, col=GREEN, anchor="l"):
        tri = s.startswith("▶ "); s = s[2:] if tri else s
        t = timg(s, "russo", sz, col); sh = timg(s, "russo", sz, (0, 0, 0)); tw_ = int(sz * 0.9) if tri else 0
        if anchor == "r": x -= t.width + tw_
        if tri:
            d = ImageDraw.Draw(img); h_ = t.height
            for off, c in ((4, (0, 0, 0)), (0, col)): d.polygon([(x + off, y + off), (x + off + h_ * 0.85, y + off + h_ / 2), (x + off, y + off + h_)], fill=c)
        paste_tl(img, sh, x + tw_ + 4, y + 4, 0.7); paste_tl(img, t, x + tw_, y)
    def tc(t): f = int(t * 25); return f"00:00:{f // 25:02d}:{f % 25:02d}"
    CH = [  # (start, end, lines, icon)
        (0.30, 2.30, [("T2X2 · АНТОН", 104, (255, 255, 255)), ("РЕАКЦИИ, ИГРЫ", 56, YEL), ("И ОБЩЕНИЕ С ЧАТОМ", 56, YEL)], None),
        (2.45, 4.25, [("TWITCH.TV/", 64, (255, 255, 255)), ("T2X2", 150, (255, 255, 255))], "tw"),
        (4.40, 6.20, [("БОЛЬШЕ НАРЕЗОК", 74, (255, 255, 255)), ("НА КАНАЛЕ", 50, YEL), ("«ДВОЙНАЯ РЕЗКА»", 74, (255, 255, 255))], "av")]
    STATIC = [(0.0, 0.30), (2.30, 2.45), (4.25, 4.40)]
    CPS = 34
    def typed(lines, n):
        out = []; left = n
        for s, sz, col in lines:
            k = max(0, min(len(s), left)); left -= len(s); out.append((s[:k], sz, col, k < len(s) and k >= 0 and left < 0 and k > 0 or (left < 0 and k == 0)))
        return out
    def channel(img, tb, i):
        t0, t1, lines, icon = CH[i]; u = tb - t0
        img.paste(BG[i])
        hx = 470; bob = math.sin(tb * 2 * math.pi * 0.9) * 6
        hopk = math.sin(math.pi * clamp(u / 0.3)) if u < 0.3 else 0
        draw_head(img, hx, 600 + bob - 30 * hopk, 1.32, math.sin(tb * 3.6) * 2.5)
        n = int(max(0, u - 0.12) * CPS); x0 = 860
        blocks = []; total = 0
        for s, sz, col in lines:
            k = max(0, min(len(s), n - total)); total += len(s)
            blocks.append((s, s[:k], sz, col))
        full = [timg(s, "russo", sz, col) for s, _, sz, col in blocks]
        icon_im = twitch_glyph(170) if icon == "tw" else (avatar_round(210, 7) if icon == "av" else None)
        tw_ = max(f.width for f in full); th_ = sum(f.height for f in full) + 26 * (len(full) - 1)
        if icon_im: x_text = x0 + icon_im.width + 40; paste_c(img, icon_im, x0 + icon_im.width / 2, 540, clamp(u / 0.1))
        else: x_text = x0
        y = 540 - th_ / 2; cursor_done = False
        for (s, part, sz, col), f in zip(blocks, full):
            if part:
                im = timg(part, "russo", sz, col); sh = timg(part, "russo", sz, (0, 0, 0))
                bl = f.height - im.height
                paste_tl(img, sh, x_text + 5, y + 5, 0.75); paste_tl(img, im, x_text, y)
            if not cursor_done and len(part) < len(s):
                cw = timg(part, "russo", sz, col).width if part else 0
                if (u * 3) % 1 < 0.6: ImageDraw.Draw(img).rectangle([x_text + cw + 10, y, x_text + cw + 10 + sz * 0.5, y + f.height], fill=GREEN)
                cursor_done = True
            y += f.height + 26
        osd(img, f"CH 0{i + 1}", 1840, 60, 56, anchor="r")
        osd(img, "▶ ВОСПР.", 80, 60, 44)
        osd(img, tc(D + tb), 1840, 960, 40, anchor="r")
        return u
    def content(t):
        tb = t - D
        if t < D:
            img = BG_A.copy(); e = venv(t)
            draw_head(img, 960, 560 - 10 * e + math.sin(t * 5.5) * 4, 1.38 * (1 + 0.07 * e), math.sin(t * 11) * 4 * e)
            osd(img, "КАНАЛ T2X2", 80, 60, 50); osd(img, "▶ ВОСПР.", 1840, 60, 44, anchor="r")
            osd(img, tc(t), 1840, 960, 40, anchor="r"); osd(img, "SP", 80, 960, 40)
            return img, e
        for s0, s1 in STATIC:
            if s0 <= tb < s1: return None, 0
        img = Image.new("RGB", (W, H))
        for i, (t0, t1, _, _) in enumerate(CH):
            if t0 <= tb < t1 + (0.6 if i == 2 else 0): channel(img, min(tb, 6.20), i); return img, 0.0
        return img, 0
    def static_frame(tb):
        n = rng.integers(0, 255, (H // 3, W // 3), dtype=np.uint8).repeat(3, 0).repeat(3, 1)
        a = np.stack([n, n, n], -1).astype(np.float32)
        band = ((yy[..., :1] + tb * 900) % 260 < 40); a = a * (1 - 0.3 * band[..., None]) if band.ndim == 2 else a * (1 - 0.3 * band)
        return a
    SCAN = np.where((np.arange(H) % 4) < 2, 1.0, 0.80).astype(np.float32)[:, None, None]
    def post(a, t, e):
        s = int(3 + 12 * e + (8 if rng.random() < 0.04 else 0))
        out = a.copy(); out[..., 0] = np.roll(a[..., 0], s, 1); out[..., 2] = np.roll(a[..., 2], -s, 1)
        yb = int((t * 260) % 1400) - 160
        if 0 < yb < H - 50:
            for y in range(yb, yb + 46, 2):
                out[y:y + 2] = np.roll(out[y:y + 2], int(rng.integers(-24, 25)), 1) * 1.15
        out *= SCAN; out *= VIGN
        out += rng.normal(0, 7, (H // 2, W // 2, 1)).repeat(2, 0).repeat(2, 1)
        return np.clip(out, 0, 255)
    def crt(a, kv, kh, bright=1.0):
        out = np.zeros_like(a); h2 = max(2, int(H * kv)); w2 = max(2, int(W * kh))
        im = Image.fromarray(np.clip(a * bright, 0, 255).astype(np.uint8)).resize((w2, h2), Image.BILINEAR)
        out[(H - h2) // 2:(H - h2) // 2 + h2, (W - w2) // 2:(W - w2) // 2 + w2] = np.array(im, np.float32); return out
    _cache = {}
    def frame(t):
        tb = t - D
        if tb >= 6.20:
            if "E" not in _cache: _cache["E"] = post(np.array(content(D + 6.199)[0], np.float32), D + 6.2, 0)
            E = _cache["E"]
            if tb < 6.40: k = ease_io((tb - 6.2) / 0.2); return crt(E, lerp(1, 0.004, k), 1, 1 + 1.5 * k).astype(np.uint8)
            if tb < 6.55: k = ease_io((tb - 6.4) / 0.15); return crt(np.full_like(E, 255), 0.004, lerp(1, 0.004, k)).astype(np.uint8)
            k = clamp((tb - 6.55) / 0.25); return (crt(np.full_like(E, 255), 0.006, 0.006) * (1 - k)).astype(np.uint8)
        img, e = content(t)
        a = static_frame(tb) if img is None else np.array(img, np.float32)
        a = post(a, t, e)
        if img is None and tb >= 0.3 - 1e-9: pass
        if img is None:
            im_ = Image.fromarray(a.astype(np.uint8)); idx = next(i for i, (s0, s1) in enumerate(STATIC) if s0 <= tb < s1)
            osd(im_, f"CH 0{idx + 1}", 1840, 60, 56, anchor="r"); a = np.array(im_, np.float32)
        if t < 0.30:
            kh = ease_out(t / 0.08); kv = ease_io((t - 0.06) / 0.24) if t > 0.06 else 0.004
            a = crt(a, max(0.004, kv), max(0.004, kh), 1 + 2.0 * (1 - kv))
        return a.astype(np.uint8)

# ------------------------------------------------------------------ stills
if os.environ.get("STILLS"):
    for ts in os.environ["STILLS"].split(","):
        tt = float(ts) if not ts.startswith("b") else D + float(ts[1:])
        Image.fromarray(np.asarray(frame(tt), np.uint8)).save(f"{OUT}_{ts}.png")
    sys.exit(0)

# ------------------------------------------------------------------ sound (all synthesized)
SR = 48000; N = int(T * SR); mix = np.zeros(N, np.float32); music = np.zeros(N, np.float32)
def place(buf, sig, t, g=1.0):
    i = int(t * SR)
    if i >= N: return
    j = min(N, i + len(sig)); buf[i:j] += g * sig[:j - i]
def onepole(x, a): return lfilter([a], [1, -(1 - a)], x).astype(np.float32)
def tt(d): return np.arange(int(d * SR)) / SR
def sweep_noise(dur, f0, f1, env):
    n = int(dur * SR); t = np.arange(n) / SR; x = rng.standard_normal(n).astype(np.float32)
    f = f0 * (f1 / f0) ** (t / dur); a = 1 - np.exp(-2 * np.pi * f / SR)
    lo = np.zeros(n, np.float32); lo2 = np.zeros(n, np.float32); s = s2 = 0.0
    for i in range(n): s += a[i] * (x[i] - s); s2 += a[i] * 0.35 * (x[i] - s2); lo[i] = s; lo2[i] = s2
    return (lo - lo2) * env(t / dur)
def boom(dur=1.1, f0=95, f1=36, g=1.0):
    t = tt(dur); ph = 2 * np.pi * np.cumsum(f1 + (f0 - f1) * np.exp(-t * 9)) / SR
    s = np.tanh(2.2 * np.sin(ph)) * np.exp(-t * 3.2); s[:200] += rng.standard_normal(200) * 0.6; return (s * g).astype(np.float32)
def kick(): t = tt(0.35); return (np.sin(2 * np.pi * np.cumsum(48 + 120 * np.exp(-t * 30)) / SR) * np.exp(-t * 9)).astype(np.float32)
def hat(dec=90): t = tt(0.06); x = rng.standard_normal(len(t)); return ((x - onepole(x, 0.5)) * np.exp(-t * dec)).astype(np.float32)
def clap():
    t = tt(0.25); x = rng.standard_normal(len(t)); x = onepole(x - onepole(x, 0.08), 0.6)
    env = np.exp(-t * 18) + sum(np.exp(-(t - d) * 120) * (t >= d) for d in (0.0, 0.012, 0.024)); return (x * env * 0.6).astype(np.float32)
def snare(): t = tt(0.3); x = rng.standard_normal(len(t)); return ((x - onepole(x, 0.1)) * np.exp(-t * 14) * 0.7 + np.sin(2 * np.pi * 185 * t) * np.exp(-t * 25) * 0.5).astype(np.float32)
def shing():
    t = tt(0.7); s = sum(np.sin(2 * np.pi * f * t + rng.uniform(0, 6)) * np.exp(-t * k) for f, k in ((2950, 6), (4420, 7), (5870, 8), (7310, 9), (9020, 11)))
    return (s * 0.22).astype(np.float32)
def blip(f0=900, f1=1500, d=0.07): t = tt(d); return (np.sin(2 * np.pi * np.cumsum(f0 + (f1 - f0) * t / d) / SR) * np.exp(-t * 50)).astype(np.float32)
def tone(f, d, dec=8, kind="sin"):
    t = tt(d); ph = 2 * np.pi * f * t
    w = {"sin": np.sin(ph), "sq": np.sign(np.sin(ph)), "saw": 2 * ((f * t) % 1) - 1, "tri": 2 * np.abs(2 * ((f * t) % 1) - 1) - 1}[kind]
    return (w * np.exp(-t * dec)).astype(np.float32)
whoosh_up = sweep_noise(0.34, 500, 7000, lambda u: u ** 2.2 * (1 - np.clip((u - 0.92) / 0.08, 0, 1)))
swish = sweep_noise(0.30, 5000, 700, lambda u: np.sin(np.pi * u) ** 1.5)

vv = vx if vsr == SR else np.interp(np.arange(int(len(vx) * SR / vsr)) * vsr / SR, np.arange(len(vx)), vx)
place(mix, vv.astype(np.float32), 0.12, 1.25)

def seq(m0, m1, bpm, fn):
    st = 60 / bpm / 4; i = 0; t = m0
    while t < m1: fn(i, t); i += 1; t += st

if STYLE == "rezka":
    place(mix, sweep_noise(0.16, 1500, 6000, lambda u: np.sin(np.pi * u)), 0.0, 0.5)
    place(mix, shing(), D, 1.0); place(mix, sweep_noise(0.16, 3000, 9000, lambda u: np.sin(np.pi * u)), D, 0.6)
    place(mix, boom(), D + 0.16, 1.0); place(mix, boom(0.6, 140, 40, 0.5), D + 0.45)
    for t0 in (1.70, 3.45, 4.95): place(mix, swish, D + t0 - 0.05, 0.7); place(mix, shing(), D + t0, 0.45)
    place(mix, shing(), D + 6.35, 0.9); place(mix, boom(0.9, 110, 35, 0.8), D + 6.47)
    cow = [554, 0, 554, 659, 0, 554, 494, 0, 554, 0, 554, 740, 0, 659, 554, 494]
    bass = [69.3, 69.3, 69.3, 61.7]
    def ph_(i, t):
        s = i % 16
        if s in (0, 6, 10): place(music, kick(), t, 0.9)
        if s in (4, 12): place(music, clap(), t, 0.7)
        if s % 2 == 0: place(music, hat(), t, 0.22)
        if cow[s]:
            f = cow[s]; c = tone(f, 0.16, 22, "sq") * 0.5 + tone(f * 1.48, 0.16, 26, "sq") * 0.35
            place(music, onepole(c - onepole(c, 0.05), 0.4), t, 0.22)
        if s in (0, 10):
            f = bass[(i // 16) % 4]; b = np.tanh(2.5 * tone(f, 0.9, 2.2)); place(music, b, t, 0.55)
    seq(D + 0.16, D + 6.47, 140, ph_)
    m0, m1 = D + 0.16, D + 6.47

if STYLE == "chat":
    for tm, *_ in MSGS: place(mix, blip(), tm, 0.22)
    place(mix, boom(0.7, 120, 45, 0.6), D)
    for _, t0, _ in AL:
        for k, f in enumerate((1046.5, 1318.5, 1568.0, 2093.0)): place(mix, tone(f, 0.6, 7, "tri"), D + t0 + k * 0.055, 0.22)
        place(mix, swish, D + t0 - 0.1, 0.4)
    chords = [(174.6, 220, 261.6, 329.6), (164.8, 196, 246.9, 293.7), (146.8, 174.6, 220, 261.6), (130.8, 164.8, 196, 246.9)]
    def lo(i, t):
        s = i % 16; sw = 0.03 if s % 2 else 0
        if s in (0, 10): place(music, kick(), t, 0.8)
        if s in (4, 12): place(music, snare(), t, 0.35)
        if s % 2 == 0 or s in (7, 15): place(music, hat(140), t + sw, 0.15)
        if s % 8 == 0:
            ch = chords[(i // 8) % 4]; tt_ = tt(1.3)
            pad = sum(np.sin(2 * np.pi * f * tt_) + 0.25 * np.sin(4 * np.pi * f * tt_) for f in ch) * np.exp(-tt_ * 1.6) * (1 + 0.15 * np.sin(2 * np.pi * 5 * tt_))
            place(music, pad.astype(np.float32), t, 0.09)
    seq(D, D + 6.2, 90, lo)
    crack = np.zeros(N, np.float32); idx = rng.integers(0, N, 400); crack[idx] = rng.uniform(-0.3, 0.3, 400); music += onepole(crack, 0.3)
    m0, m1 = D, D + 6.4

if STYLE == "tv":
    st = lambda d: (rng.standard_normal(int(d * SR)) * 0.35).astype(np.float32)
    place(mix, boom(0.5, 70, 30, 0.7), 0.0); place(mix, st(0.3) * np.linspace(1, 0, int(0.3 * SR)).astype(np.float32), 0.0, 0.6)
    hum = (np.sin(2 * np.pi * 60 * tt(D)) * 0.04 + np.sin(2 * np.pi * 7800 * tt(D)) * 0.006).astype(np.float32); place(mix, hum, 0.0)
    for s0, s1 in STATIC: place(mix, st(s1 - s0), D + s0, 0.7); place(mix, blip(2000, 600, 0.05), D + s1, 0.3)
    for t0, t1, lines, _ in CH:
        nch = sum(len(s) for s, *_ in lines)
        for k in range(nch): place(mix, tone(1250, 0.025, 60, "sq"), D + t0 + 0.12 + k / CPS, 0.05)
    t_ = tt(0.35); place(mix, (np.sin(2 * np.pi * np.cumsum(2400 * np.exp(-t_ * 9) + 80) / SR) * np.exp(-t_ * 4)).astype(np.float32), D + 6.2, 0.35)
    arp = [220, 261.6, 329.6, 440, 329.6, 261.6, 220, 261.6]; roots = [220, 174.6, 196, 164.8]
    def sw(i, t):
        s = i % 16; root = roots[(i // 16) % 4]; r = root / 220
        if s % 4 == 0: place(music, kick(), t, 0.8)
        if s in (4, 12): place(music, snare(), t, 0.45)
        if s % 2 == 1: place(music, hat(110), t, 0.12)
        a = tone(arp[s % 8] * r * 2, 0.14, 14, "saw"); place(music, onepole(a, 0.15), t, 0.2)
        if s % 2 == 0: place(music, onepole(tone(root / 4, 0.2, 6, "saw"), 0.05), t, 0.6)
    seq(D + 0.30, D + 6.2, 112, sw)
    m0, m1 = D + 0.3, D + 6.25

fade_in = np.clip((np.arange(N) / SR - m0) / 0.05, 0, 1); cut = np.clip((m1 - np.arange(N) / SR) / 0.05, 0, 1)
mix += 0.42 * music / max(1e-6, np.abs(music).max()) * 0.9 * fade_in * cut
mix /= max(1e-6, np.abs(mix).max()) / 0.89
with wave.open(OUT + ".wav", "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((np.stack([mix, mix], 1) * 32767).astype(np.int16).tobytes())

p = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                      "-i", OUT + ".wav", "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-pix_fmt", "yuv420p", "-af", "loudnorm=I=-14:TP=-1.0:LRA=9",
                      "-ar", "48000", "-c:a", "aac", "-b:a", "256k", "-shortest", "-movflags", "+faststart", OUT], stdin=subprocess.PIPE)
for i in range(NF):
    p.stdin.write(np.ascontiguousarray(frame(i / FPS), np.uint8).tobytes())
    if i % 60 == 0: print(f"{STYLE} frame {i}/{NF}", flush=True)
p.stdin.close(); p.wait(); os.remove(OUT + ".wav"); print("done", OUT)
