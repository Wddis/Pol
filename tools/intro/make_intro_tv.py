"""Channel intro «Ретро-ТВ» for «Двойная резка / T2x2» — 1920x1080 @60, exactly ~10 s, original animation + synthesized sound.
Chosen by the owner 12.11.2026 (from 3 variants of make_intro_alt.py), v2: smoother animation, CRT effects, a short
phrase of the streamer on every «channel» (each with a soft echo that fades — made by make_voice.py).

Timeline: TV turns on, head says the greeting -> static «CH 01» -> T2X2 · АНТОН (+ phrase 1) -> static -> twitch
(+ phrase 2) -> static -> more clips on «Двойная резка» (+ phrase 3) -> TV turns off.
Effects: CRT barrel curvature + rounded screen, bloom, scanlines + flicker, chromatic aberration (pulses on voice),
VHS tracking band, vertical roll after channel switch, glass glare sweep, slow background zoom.
Animation: head slides in on every channel and «talks» to the phrase, lines slide in and are typed, icons pop.

usage: python3 make_intro_tv.py head.png avatar.png fonts_dir greeting.wav ph1.wav ph2.wav ph3.wav out.mp4   (ph = "-" -> channel without a phrase)
Owner 12.11: CH 02 (twitch) — без фразы.
fonts_dir: russo-one (cyrillic + latin) woff.  STILLS="1.0,4.2" -> png stills only (absolute seconds)."""
import sys, os, math, wave, subprocess, functools, numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from scipy.signal import lfilter

HEAD, AVATAR, FD, GREET, P1, P2, P3, OUT = sys.argv[1:9]
W, H, FPS = 1920, 1080, 60
rng = np.random.default_rng(11)

def load(p):
    with wave.open(p) as w:
        sr = w.getframerate(); x = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
        x = x.reshape(-1, w.getnchannels()).mean(1)
    if sr != 48000: x = np.interp(np.arange(int(len(x) * 48000 / sr)) * sr / 48000, np.arange(len(x)), x).astype(np.float32)
    hop = 48000 // FPS; e = np.array([np.sqrt(np.mean(x[i:i + hop] ** 2)) for i in range(0, len(x), hop)]); e /= e.max() + 1e-9
    return x, e

GREET_SPEECH = 1.95                                   # speech part of the greeting (rest = echo tail)
D = 0.12 + GREET_SPEECH + 0.30                        # phase A: TV on + greeting
STATIC = [(0.00, 0.22), (2.42, 2.54), (4.44, 4.56)]   # base-timeline (tb = t - D)
CH_T = [(0.22, 2.42), (2.54, 4.44), (4.56, 6.95)]
OFF = 6.95; TB = 7.63; T = D + TB; NF = int(round(T * FPS))
VOICES = [(0.12, *load(GREET))] + [(t0, *load(p_)) for t0, p_ in ((D + 0.36, P1), (D + 2.66, P2), (D + 4.74, P3)) if p_ != "-"]   # (abs start, x, env); "-" = no phrase
def venv(t):
    v = 0.0
    for t0, _, e in VOICES:
        i = int((t - t0) * FPS)
        if 0 <= i < len(e): v = max(v, float(e[i]))
    return v

def clamp(x): return min(max(x, 0.0), 1.0)
def ease_io(x): x = clamp(x); return x * x * (3 - 2 * x)
def ease_out(x): x = clamp(x); return 1 - (1 - x) ** 3
def ease_out_back(x, s=1.7): x = clamp(x) - 1; return 1 + (s + 1) * x ** 3 + s * x ** 2
def lerp(a, b, k): return a + (b - a) * k

@functools.lru_cache(None)
def F(sz, lat): return ImageFont.truetype(f"{FD}/russo-one-{'latin' if lat else 'cyrillic'}-400-normal.woff", sz)
def islat(c): return not ("Ѐ" <= c <= "ӿ")
_D = ImageDraw.Draw(Image.new("L", (1, 1)))
@functools.lru_cache(None)
def timg(s, sz, fill=(255, 255, 255)):
    w = int(sum(_D.textlength(c, font=F(sz, islat(c))) for c in s)) + 8
    im = Image.new("RGBA", (max(w, 2), int(sz * 1.5)), (0, 0, 0, 0)); d = ImageDraw.Draw(im); x = 4
    for c in s:
        f = F(sz, islat(c)); d.text((x, sz * 1.1), c, font=f, fill=fill + (255,), anchor="ls"); x += d.textlength(c, font=f)
    return im
@functools.lru_cache(None)
def cap_box(sz):
    bb = timg("ЁЙTWQ", sz).getbbox(); return bb[1], bb[3]   # common vertical box for a size (lines don't jump while typing)
def with_alpha(im, a):
    if a >= 0.999: return im
    im = im.copy(); im.putalpha(im.getchannel("A").point(lambda v: int(v * a))); return im
def paste_tl(c, im, x, y, a=1.0):
    if a > 0.003: im = with_alpha(im, a); c.paste(im, (int(round(x)), int(round(y))), im)
def paste_c(c, im, cx, cy, a=1.0): paste_tl(c, im, cx - im.width / 2, cy - im.height / 2, a)

head0 = Image.open(HEAD).convert("RGBA")
@functools.lru_cache(256)
def head_img(sq, aq):
    h = 430 * sq / 100; im = head0.resize((max(2, round(head0.width * h / head0.height)), max(2, round(h))), Image.BILINEAR)
    return im.rotate(aq / 10, expand=True, resample=Image.BICUBIC) if aq else im
@functools.lru_cache(64)
def head_shadow(sq):
    im = head_img(sq, 0); pad = 40; big = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    sh = Image.new("RGBA", im.size, (0, 0, 0, 0)); sh.putalpha(im.getchannel("A").point(lambda a: a * 0.55)); big.alpha_composite(sh, (pad, pad))
    return big.filter(ImageFilter.GaussianBlur(18))
def draw_head(c, cx, cy, s, ang=0.0, a=1.0):
    sq = int(round(s * 100)); paste_c(c, head_shadow(sq), cx + 16, cy + 22, a); paste_c(c, head_img(sq, int(round(ang * 10))), cx, cy, a)

AV = Image.open(AVATAR).convert("RGB")
@functools.lru_cache(4)
def avatar_round(d, ring=7):
    a = AV.resize((d, d), Image.LANCZOS).convert("RGBA"); m = Image.new("L", (d, d), 0); ImageDraw.Draw(m).ellipse([0, 0, d - 1, d - 1], fill=255); a.putalpha(m)
    out = Image.new("RGBA", (d + 2 * ring, d + 2 * ring), (0, 0, 0, 0)); ImageDraw.Draw(out).ellipse([0, 0, d + 2 * ring - 1, d + 2 * ring - 1], fill=(255, 255, 255, 255))
    out.alpha_composite(a, (ring, ring)); return out
@functools.lru_cache(4)
def twitch_glyph(hgt, col=(145, 70, 255)):
    s = hgt / 100; im = Image.new("RGBA", (int(86 * s) + 2, int(101 * s) + 2), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    P = lambda pts: [(x * s, y * s) for x, y in pts]
    d.polygon(P([(0, 14), (14, 0), (84, 0), (84, 58), (56, 86), (40, 86), (26, 100), (26, 86), (0, 86)]), fill=col + (255,))
    d.rectangle(P([(34, 22), (42, 50)]), fill=(255, 255, 255, 255)); d.rectangle(P([(56, 22), (64, 50)]), fill=(255, 255, 255, 255))
    return im

# ------------------------------------------------------------------ backgrounds / CRT maps
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
def grad(c0, c1):
    r = np.clip(np.sqrt(((xx - 960) / 1100) ** 2 + ((yy - 540) / 700) ** 2), 0, 1)[..., None]
    return Image.fromarray((np.array(c0, np.float32) * (1 - r) + np.array(c1, np.float32) * r).astype(np.uint8))
BG_A = grad((40, 70, 95), (8, 14, 22)); BG = [grad((100, 32, 150), (18, 6, 30)), grad((32, 54, 160), (6, 10, 32)), grad((70, 70, 74), (6, 6, 8))]
GREEN = (110, 255, 140); YEL = (255, 220, 60)
u = (xx - 960) / 960; v = (yy - 540) / 540; r2 = u * u + v * v
K = 0.055; SC = 0.965                                                  # barrel curvature
su = u * (1 + K * r2) * SC; sv = v * (1 + K * r2) * SC
MX = np.clip((su * 960 + 960).round(), 0, W - 1).astype(np.int32); MY = np.clip((sv * 540 + 540).round(), 0, H - 1).astype(np.int32)
edge = np.minimum.reduce([(1 - np.abs(su)) * 960, (1 - np.abs(sv)) * 540])               # px to screen edge
cx_, cy_ = np.maximum(np.abs(su) * 960 - (960 - 60), 0), np.maximum(np.abs(sv) * 540 - (540 - 60), 0)
corner = 60 - np.sqrt(cx_ ** 2 + cy_ ** 2)                                                 # rounded corners r=60
SCREEN = np.clip(np.minimum(edge, corner) / 6, 0, 1)[..., None].astype(np.float32)
VIGN = (1 - 0.45 * np.clip(r2 / 2, 0, 1) ** 1.2)[..., None].astype(np.float32)
SCAN = np.where((np.arange(H) % 4) < 2, 1.0, 0.82).astype(np.float32)[:, None, None]

def osd(img, s, x, y, sz=48, col=GREEN, anchor="l", a=1.0):
    tri = s.startswith("▶ "); s = s[2:] if tri else s
    t = timg(s, sz, col); sh = timg(s, sz, (0, 0, 0)); bb = t.getbbox(); tw_ = int(sz * 0.9) if tri else 0
    if anchor == "r": x -= bb[2] + tw_
    if tri and a > 0.5:
        d = ImageDraw.Draw(img); h_ = bb[3] - bb[1]; yt = y + bb[1]
        for off, c in ((4, (0, 0, 0)), (0, col)): d.polygon([(x + off, yt + off), (x + off + h_ * 0.85, yt + off + h_ / 2), (x + off, yt + off + h_)], fill=c)
    paste_tl(img, sh, x + tw_ + 4, y + 4, 0.7 * a); paste_tl(img, t, x + tw_, y, a)
def tc(t): f = int(t * 25); return f"00:00:{f // 25:02d}:{f % 25:02d}"

CH = [[("T2X2 · АНТОН", 108, (255, 255, 255)), ("РЕАКЦИИ, ИГРЫ", 58, YEL), ("И ОБЩЕНИЕ С ЧАТОМ", 58, YEL)],
      [("TWITCH.TV/", 66, (255, 255, 255)), ("T2X2", 156, (255, 255, 255))],
      [("БОЛЬШЕ НАРЕЗОК", 76, (255, 255, 255)), ("НА КАНАЛЕ", 52, YEL), ("«ДВОЙНАЯ РЕЗКА»", 76, (255, 255, 255))]]
ICON = [None, "tw", "av"]; CPS = 32; LINE_GAP = 22

def scene_channel(i, tb):
    t0, t1 = CH_T[i]; u_ = tb - t0
    img = BG[i].copy()
    z = 1 + 0.035 * clamp(u_ / (t1 - t0))                                       # slow push-in of the background
    if z > 1.001: w2, h2 = W / z, H / z; img = img.crop((960 - w2 / 2, 540 - h2 / 2, 960 + w2 / 2, 540 + h2 / 2)).resize((W, H), Image.BILINEAR)
    e = venv(D + tb)
    k = ease_out(u_ / 0.42); hx = lerp(-260, 470, k)
    bob = math.sin(tb * 2 * math.pi * 0.85) * 6; breath = 1 + 0.015 * math.sin(tb * 2 * math.pi * 0.5)
    draw_head(img, hx, 600 + bob - 14 * e, 1.32 * breath * (1 + 0.06 * e), lerp(-14, 0, k) + math.sin(tb * 3.3) * 2.2 + math.sin(tb * 11) * 3 * e)
    lines = CH[i]; full = [timg(s, sz, col) for s, sz, col in lines]
    boxes = [cap_box(sz) for _, sz, _ in lines]; hs = [b - a for a, b in boxes]
    icon = twitch_glyph(176) if ICON[i] == "tw" else (avatar_round(212) if ICON[i] == "av" else None)
    x_text = 860 + (icon.width + 42 if icon else 0)
    if icon:
        ki = ease_out_back((u_ - 0.05) / 0.38, 1.8)
        if ki > 0.01:
            ic = icon.resize((max(2, int(icon.width * ki)), max(2, int(icon.height * ki))), Image.BILINEAR)
            paste_c(img, ic, 860 + icon.width / 2, 540, clamp(u_ / 0.12))
    th = sum(hs) + LINE_GAP * (len(lines) - 1); y = 540 - th / 2
    n = int(max(0, u_ - 0.18) * CPS); used = 0; cursor = False
    for li, ((s, sz, col), f, (a_, b_)) in enumerate(zip(lines, full, boxes)):
        k_ = max(0, min(len(s), n - used)); used += len(s)
        ls = ease_out((u_ - 0.18 - (used - len(s)) / CPS) / 0.22)                 # each line slides/fades in as typing reaches it
        if k_ > 0:
            part = timg(s[:k_], sz, col); sh = timg(s[:k_], sz, (0, 0, 0))
            dx = (1 - ls) * 46
            paste_tl(img, sh, x_text + dx + 5, y - a_ + 5, 0.75 * ls); paste_tl(img, part, x_text + dx, y - a_, ls)
        if not cursor and k_ < len(s) and (k_ > 0 or li == 0 or n >= used - len(s)):
            cw = (timg(s[:k_], sz, col).getbbox() or (0, 0, 0, 0))[2] if k_ else 0
            if (u_ * 3.2) % 1 < 0.62: ImageDraw.Draw(img).rectangle([x_text + cw + 10, y, x_text + cw + 10 + sz * 0.48, y + (b_ - a_)], fill=GREEN)
            cursor = True
        y += (b_ - a_) + LINE_GAP
    osd(img, f"CH 0{i + 1}", 1820, 70, 56, anchor="r")
    osd(img, "▶ ВОСПР.", 100, 70, 44, a=1.0 if (tb * 1.4) % 1 < 0.7 else 0.25)
    osd(img, tc(D + tb), 1820, 950, 40, anchor="r")
    gl = (u_ - 0.5) / 0.9                                                       # glass glare sweep, once per channel
    return img, e, gl

def scene_a(t):
    img = BG_A.copy(); e = venv(t)
    draw_head(img, 960, 560 - 12 * e + math.sin(t * 5.5) * 4, 1.40 * (1 + 0.07 * e), math.sin(t * 11) * 4 * e)
    osd(img, "КАНАЛ T2X2", 100, 70, 50); osd(img, "▶ ВОСПР.", 1820, 70, 44, anchor="r", a=1.0 if (t * 1.4) % 1 < 0.7 else 0.25)
    osd(img, tc(t), 1820, 950, 40, anchor="r"); osd(img, "SP", 100, 950, 40)
    return img, e, -1

def static_img(tb, ch):
    n = rng.integers(0, 255, (H // 3, W // 3), dtype=np.uint8).repeat(3, 0).repeat(3, 1).astype(np.float32)
    band = ((yy + tb * 1400) % 300 < 46); n = n * (1 - 0.35 * band)
    a = np.repeat(n[..., None], 3, 2); a[..., 2] *= 1.05
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)); osd(img, f"CH 0{ch + 1}", 1820, 70, 56, anchor="r"); return img

def crt(a, t, e, glare=-1.0, roll=0.0):
    """a: float HxWx3 content -> CRT look"""
    s = int(2 + 10 * e + (6 if rng.random() < 0.05 else 0))
    if s: b = a.copy(); b[..., 0] = np.roll(a[..., 0], s, 1); b[..., 2] = np.roll(a[..., 2], -s, 1); a = b
    if roll: a = np.roll(a, int(roll), 0)
    yb = int((t * 240) % 1500) - 200                                          # VHS tracking band
    if 0 < yb < H - 60:
        for y in range(yb, yb + 50, 2): a[y:y + 2] = np.roll(a[y:y + 2], int(rng.integers(-22, 23)), 1) * 1.12
    if 0 <= glare <= 1:
        gx = lerp(-700, 2600, ease_io(glare)); band = np.exp(-((xx + 0.55 * yy - gx) / 120) ** 2)[..., None]
        a = a + 38 * band
    small = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).resize((W // 6, H // 6), Image.BILINEAR).filter(ImageFilter.GaussianBlur(5))
    bloom = np.array(small.resize((W, H), Image.BILINEAR), np.float32)
    a = a + 0.32 * np.clip(bloom - 70, 0, None)
    a = a * SCAN * VIGN * (1 + 0.025 * math.sin(t * 2 * math.pi * 13))
    a = a + rng.normal(0, 6, (H // 2, W // 2, 1)).repeat(2, 0).repeat(2, 1)
    a = a[MY, MX] * SCREEN
    return np.clip(a, 0, 255)

def squash(a, kv, kh, bright=1.0):
    out = np.zeros_like(a); h2 = max(2, int(H * kv)); w2 = max(2, int(W * kh))
    im = Image.fromarray(np.clip(a * bright, 0, 255).astype(np.uint8)).resize((w2, h2), Image.BILINEAR)
    out[(H - h2) // 2:(H - h2) // 2 + h2, (W - w2) // 2:(W - w2) // 2 + w2] = np.array(im, np.float32); return out

_cache = {}
def frame(t):
    tb = t - D
    if tb >= OFF:                                                                 # TV off
        if "E" not in _cache: img, e, _ = scene_channel(2, OFF - 0.001); _cache["E"] = crt(np.array(img, np.float32), D + OFF, 0)
        E = _cache["E"]; u_ = tb - OFF
        if u_ < 0.20: k = ease_io(u_ / 0.20); return squash(E, lerp(1, 0.004, k), lerp(1, 0.9, k), 1 + 1.6 * k)
        if u_ < 0.36: k = ease_io((u_ - 0.20) / 0.16); return squash(np.full_like(E, 255), 0.004, lerp(0.9, 0.004, k))
        return squash(np.full_like(E, 255), 0.006, 0.006) * clamp(1 - (u_ - 0.36) / 0.25)
    if t < D: img, e, gl = scene_a(t); roll = 0
    else:
        for i, (s0, s1) in enumerate(STATIC):
            if s0 <= tb < s1: return crt(np.array(static_img(tb, i), np.float32), t, 0.4)
        i = next(j for j, (c0, c1) in enumerate(CH_T) if c0 <= tb < c1 + 0.001)
        img, e, gl = scene_channel(i, tb)
        u_ = tb - CH_T[i][0]; roll = -(1 - ease_out(u_ / 0.22)) * 380 if u_ < 0.22 else 0      # vertical hold slips back into place
    a = crt(np.array(img, np.float32), t, e, gl, roll)
    if t < 0.30:                                                                  # TV on
        kh = ease_out(t / 0.08); kv = ease_io((t - 0.06) / 0.24) if t > 0.06 else 0.004
        a = squash(a, max(0.004, kv), max(0.004, kh), 1 + 2.0 * (1 - kv))
    return a

if os.environ.get("STILLS"):
    for ts in os.environ["STILLS"].split(","):
        Image.fromarray(frame(float(ts)).astype(np.uint8)).save(f"{OUT}_{ts}.png")
    sys.exit(0)

# ------------------------------------------------------------------ sound (synthesized + the streamer's phrases)
SR = 48000; N = int(T * SR); mix = np.zeros(N, np.float32); music = np.zeros(N, np.float32)
def place(buf, sig, t, g=1.0):
    i = int(t * SR)
    if i < N: j = min(N, i + len(sig)); buf[i:j] += g * sig[:j - i]
def onepole(x, a): return lfilter([a], [1, -(1 - a)], x).astype(np.float32)
def tt(d): return np.arange(int(d * SR)) / SR
def boom(dur, f0, f1, g):
    t = tt(dur); s = np.tanh(2.2 * np.sin(2 * np.pi * np.cumsum(f1 + (f0 - f1) * np.exp(-t * 9)) / SR)) * np.exp(-t * 3.2); return (s * g).astype(np.float32)
def kick(): t = tt(0.35); return (np.sin(2 * np.pi * np.cumsum(48 + 120 * np.exp(-t * 30)) / SR) * np.exp(-t * 9)).astype(np.float32)
def hat(dec=110): t = tt(0.06); x = rng.standard_normal(len(t)); return ((x - onepole(x, 0.5)) * np.exp(-t * dec)).astype(np.float32)
def snare(): t = tt(0.3); x = rng.standard_normal(len(t)); return ((x - onepole(x, 0.1)) * np.exp(-t * 14) * 0.7 + np.sin(2 * np.pi * 185 * t) * np.exp(-t * 25) * 0.5).astype(np.float32)
def tone(f, d, dec=8, kind="sin"):
    t = tt(d); ph = 2 * np.pi * f * t
    w = {"sin": np.sin(ph), "sq": np.sign(np.sin(ph)), "saw": 2 * ((f * t) % 1) - 1}[kind]; return (w * np.exp(-t * dec)).astype(np.float32)
def blip(f0, f1, d): t = tt(d); return (np.sin(2 * np.pi * np.cumsum(f0 + (f1 - f0) * t / d) / SR) * np.exp(-t * 50)).astype(np.float32)
def static_snd(d):
    x = rng.standard_normal(int(d * SR)).astype(np.float32) * 0.35; x = x - onepole(x, 0.02)
    f = np.minimum(1, np.minimum(np.arange(len(x)), len(x) - np.arange(len(x))) / (0.015 * SR)); return x * f
# voices: greeting tail fades before the first channel phrase; phrases keep their own echo fade
for k, (t0, x, _) in enumerate(VOICES):
    x = x.copy()
    if k == 0:
        tl = np.arange(len(x)) / SR + t0; x *= np.where(tl < D, 1.0, np.cos(np.clip((tl - D) / 0.45, 0, 1) * np.pi / 2) ** 2)
    place(mix, x, t0, 1.2)
place(mix, boom(0.5, 70, 30, 0.7), 0.0); place(mix, static_snd(0.3) * np.linspace(1, 0, int(0.3 * SR)).astype(np.float32), 0.0, 0.5)
place(mix, (np.sin(2 * np.pi * 60 * tt(D)) * 0.035).astype(np.float32), 0.0)
for s0, s1 in STATIC: place(mix, static_snd(s1 - s0), D + s0, 0.55); place(mix, blip(2000, 600, 0.05), D + s1, 0.25); place(mix, boom(0.35, 160, 60, 0.35), D + s1)
for (c0, _), lines in zip(CH_T, CH):
    nch = sum(len(s) for s, *_ in lines)
    for k in range(nch): place(mix, tone(1250, 0.022, 70, "sq"), D + c0 + 0.18 + k / CPS, 0.035)
t_ = tt(0.4); place(mix, (np.sin(2 * np.pi * np.cumsum(2400 * np.exp(-t_ * 9) + 80) / SR) * np.exp(-t_ * 4)).astype(np.float32), D + OFF, 0.35)
arp = [220, 261.6, 329.6, 440, 329.6, 261.6, 220, 261.6]; roots = [220, 174.6, 196, 164.8]
st = 60 / 112 / 4; i = 0; t = D + 0.22
while t < D + OFF:
    s = i % 16; r = roots[(i // 16) % 4] / 220
    if s % 4 == 0: place(music, kick(), t, 0.8)
    if s in (4, 12): place(music, snare(), t, 0.45)
    if s % 2 == 1: place(music, hat(), t, 0.12)
    place(music, onepole(tone(arp[s % 8] * r * 2, 0.14, 14, "saw"), 0.15), t, 0.2)
    if s % 2 == 0: place(music, onepole(tone(roots[(i // 16) % 4] / 4, 0.2, 6, "saw"), 0.05), t, 0.6)
    i += 1; t += st
duck = np.ones(N, np.float32)                                                   # music steps back under the phrases
for t0, x, _ in VOICES[1:]:
    a = int(t0 * SR); b = min(N, a + len(x)); duck[a:b] = 0.5
duck = onepole(duck, 0.0008)
tl = np.arange(N) / SR; m0, m1 = D + 0.22, D + OFF + 0.05
music *= np.clip((tl - m0) / 0.05, 0, 1) * np.clip((m1 - tl) / 0.06, 0, 1)
mix += 0.40 * music / max(1e-6, np.abs(music).max()) * 0.9 * duck
mix /= max(1e-6, np.abs(mix).max()) / 0.89
with wave.open(OUT + ".wav", "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((np.stack([mix, mix], 1) * 32767).astype(np.int16).tobytes())

p = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                      "-i", OUT + ".wav", "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-pix_fmt", "yuv420p", "-af", "loudnorm=I=-14:TP=-1.0:LRA=9",
                      "-ar", "48000", "-c:a", "aac", "-b:a", "256k", "-shortest", "-movflags", "+faststart", OUT], stdin=subprocess.PIPE)
for k in range(NF):
    p.stdin.write(np.ascontiguousarray(frame(k / FPS), np.uint8).tobytes())
    if k % 60 == 0: print(f"tv frame {k}/{NF}", flush=True)
p.stdin.close(); p.wait(); os.remove(OUT + ".wav"); print("done", OUT)
