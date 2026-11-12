"""Channel intro for «Двойная резка / T2x2» (1920x1080, 60 fps, ~7.2 s) — original animation + synthesized sound.
Structure follows the owner's reference (head pop on black -> bass hit + circular reveal -> hue-shifting striped
background -> info cards typed word by word -> fade out), but every asset is our own: no third-party music/SFX.

usage: python3 make_intro.py head.png avatar.png fonts_dir out.mp4 [voice.wav]
  head.png   — T2x2 head cut-out with alpha (owner's photo, keyed)
  avatar.png — channel avatar (square)
  fonts_dir  — @fontsource/montserrat 'files' dir (needs montserrat-{cyrillic,latin}-{800,600}-italic.woff)
  voice.wav  — optional short funny phrase of the streamer (48k); the head "talks" to it on black before the reveal
Texts are in CARDS below. Re-run to change them."""
import sys, math, subprocess, wave, numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HEAD, AVATAR, FD, OUT = sys.argv[1:5]
VOICE = sys.argv[5] if len(sys.argv) > 5 else None
W, H, FPS = 1920, 1080, 60
if VOICE:
    with wave.open(VOICE) as vw:
        vsr = vw.getframerate(); vx = np.frombuffer(vw.readframes(vw.getnframes()), np.int16).astype(np.float32) / 32768
        vx = vx.reshape(-1, vw.getnchannels()).mean(1)
    VDUR = len(vx) / vsr
    D = 0.12 + VDUR + 0.25                              # phase A (voice on black) length
    hop = vsr // FPS; env = np.array([np.sqrt(np.mean(vx[i:i + hop] ** 2)) for i in range(0, len(vx), hop)])
    VENV = env / (env.max() + 1e-9)
else:
    D, VDUR, VENV = 0.30, 0.0, np.zeros(1)
T = D + 6.9                                             # base timeline starts at tb=0.30 when t=D
def tb_of(t): return 0.30 + (t - D)                     # base-timeline time
def t_of(tb): return D + (tb - 0.30)                    # absolute time of a base-timeline event
NF = int(T * FPS)

# ---------------------------------------------------------------- text / cards
def font(sz, w=800, latin=False):
    return ImageFont.truetype(f"{FD}/montserrat-{'latin' if latin else 'cyrillic'}-{w}-italic.woff", sz)
def ftext(d, xy, s, sz, fill, w=800, anchor="la"):
    """draw mixed Cyrillic/Latin text char by char with the right font file; returns width"""
    x, y = xy; tot = sum(d.textlength(c, font=font(sz, w, not ('Ѐ' <= c <= 'ӿ'))) for c in s)
    if anchor == "ra": x -= tot
    for c in s:
        f = font(sz, w, not ('Ѐ' <= c <= 'ӿ')); d.text((x, y), c, font=f, fill=fill); x += d.textlength(c, font=f)
    return tot

# card = list of lines: (text, size, color, weight); words typed starting at t_type, step per word
CARDS = [
    dict(t_in=1.10, t_out=3.10, side="right", t_type=1.30, step=0.12,
         lines=[("T2X2 · АНТОН", 66, (255, 255, 255), 800), ("РЕАКЦИИ, ИГРЫ", 50, (255, 255, 255), 800),
                ("И ОБЩЕНИЕ С ЧАТОМ", 50, (255, 255, 255), 800)]),
    dict(t_in=3.10, t_out=4.75, side="left", t_type=3.36, step=0.13, twitch=True,
         lines=[("twitch.tv/", 46, (235, 235, 245), 600), ("T2X2", 104, (255, 255, 255), 800)]),
    dict(t_in=4.75, t_out=7.2, side="right", t_type=5.00, step=0.12, avatar=True,
         lines=[("БОЛЬШЕ НАРЕЗОК", 62, (255, 255, 255), 800), ("НА КАНАЛЕ", 44, (200, 200, 210), 600),
                ("«ДВОЙНАЯ РЕЗКА»", 62, (255, 255, 255), 800)]),
]

head0 = Image.open(HEAD).convert("RGBA")
HEAD_H = 430
head0 = head0.resize((round(head0.width * HEAD_H / head0.height), HEAD_H), Image.LANCZOS)
av = Image.open(AVATAR).convert("RGB").resize((150, 150), Image.LANCZOS)
avm = Image.new("L", (150, 150), 0); ImageDraw.Draw(avm).ellipse([0, 0, 149, 149], fill=255)

def ease_out_back(x, s=1.9): x = min(max(x, 0), 1) - 1; return 1 + (s + 1) * x ** 3 + s * x ** 2
def ease_io(x): x = min(max(x, 0), 1); return x * x * (3 - 2 * x)
def lerp(a, b, k): return a + (b - a) * k

def head_state(t):
    """returns (cx, cy, scale, angle)"""
    bob = math.sin(t * 2 * math.pi * 0.9) * 6; rot = math.sin(t * 2 * math.pi * 0.6) * 2.5
    if t < 0.30: return 960, 540, lerp(0.05, 1.35, ease_out_back(t / 0.30, 1.2)), (1 - t / 0.3) * -12
    if t < 0.48: return 960, 540, lerp(1.35, 0.78, ease_io((t - 0.30) / 0.18)), 0
    if t < 1.10: return 960, 540 + bob, 0.78 + 0.02 * math.sin((t - 0.48) * 9), rot
    xs = [(1.10, 960, 0.78), (1.32, 640, 1.0), (3.10, 640, 1.0), (3.32, 1290, 1.0), (4.75, 1290, 1.0), (4.97, 640, 1.0)]
    x, s = xs[-1][1], xs[-1][2]
    for (t0, x0, s0), (t1, x1, s1) in zip(xs, xs[1:]):
        if t0 <= t < t1: k = ease_io((t - t0) / (t1 - t0)); x, s = lerp(x0, x1, k), lerp(s0, s1, k); break
    return x, 540 + bob, s, rot

# ---------------------------------------------------------------- background
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
th = math.radians(-28); U = xx * math.cos(th) + yy * math.sin(th); V = -xx * math.sin(th) + yy * math.cos(th)
vign = 1 - 0.38 * np.clip(((xx - W / 2) / (W * 0.62)) ** 2 + ((yy - H / 2) / (H * 0.62)) ** 2, 0, 1)
HUES = [(0.45, 272), (1.5, 318), (2.6, 352), (3.3, 382), (3.9, 412), (4.7, 475), (5.6, 520), (6.6, 550)]   # monotonic (hsv wraps %360)
def hue_at(t):
    if t <= HUES[0][0]: return HUES[0][1]
    for (t0, h0), (t1, h1) in zip(HUES, HUES[1:]):
        if t0 <= t < t1: return lerp(h0, h1, ease_io((t - t0) / (t1 - t0)))
    return HUES[-1][1]
def hsv(h, s, v):
    h = (h % 360) / 60; c = v * s; x = c * (1 - abs(h % 2 - 1)); m = v - c
    r, g, b = [(c, x, 0), (x, c, 0), (0, c, x), (0, x, c), (x, 0, c), (c, 0, x)][int(h) % 6]
    return np.array([r + m, g + m, b + m], np.float32) * 255
def background(t):
    ph = t * 0.9
    wave_ = U + 22 * np.sin(2 * math.pi * (V / 300 + ph * 0.5))
    lines = np.clip((np.sin(2 * math.pi * (wave_ / 46 - ph)) - 0.72) / 0.28, 0, 1)   # thin dark wavy stripes
    col = hsv(hue_at(t), 0.88, 0.62)
    img = col[None, None, :] * (1 - 0.30 * lines)[..., None] * vign[..., None]
    return img

# ---------------------------------------------------------------- one frame
def render_head(canvas, t):
    cx, cy, s, ang = head_state(t)
    hh = head0.resize((max(2, int(head0.width * s)), max(2, int(head0.height * s))), Image.BILINEAR).rotate(ang, expand=True, resample=Image.BICUBIC)
    sh = Image.new("RGBA", hh.size, (0, 0, 0, 0)); sh.putalpha(hh.split()[3].point(lambda a: a * 0.45)); sh = sh.filter(ImageFilter.GaussianBlur(14))
    canvas.alpha_composite(sh, (int(cx - hh.width / 2 + 14), int(cy - hh.height / 2 + 20)))
    canvas.alpha_composite(hh, (int(cx - hh.width / 2), int(cy - hh.height / 2)))
    return cx, s

def tw(d, s, sz, w): return sum(d.textlength(ch, font=font(sz, w, not ('\u0400' <= ch <= '\u04FF'))) for ch in s)
PADX, PADY, GAP = 44, 36, 0.40                        # padding inside the visible part of the card; line gap (x size)
def render_card(canvas, t, c, head_x, head_s):
    if not (c["t_in"] <= t < c["t_out"] + 0.18): return
    k_in = ease_io((t - c["t_in"]) / 0.25); k_out = 1 - ease_io((t - c["t_out"]) / 0.18) if t >= c["t_out"] else 1
    k = min(k_in, k_out)
    if k <= 0: return
    lay = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    # measure the content block
    icon_w = 112 if c.get("twitch") else (185 if c.get("avatar") else 0)
    text_w = max(tw(d, s, sz, w) for s, sz, _, w in c["lines"])
    cw = icon_w + text_w
    caps = [sz * 0.72 for _, sz, _, _ in c["lines"]]                  # cap height of each line
    th = sum(caps) + sum(sz * GAP for _, sz, _, _ in c["lines"][1:])
    ch = max(th, 150 if c.get("avatar") else (100 if c.get("twitch") else 0))
    box_h = int(ch + 2 * PADY)
    hw = head0.width * head_s / 2
    if c["side"] == "right":
        vis = head_x + hw * 0.92                                       # visible part starts at the head's edge
        x_back = head_x + hw * 0.35                                    # box tucks behind the head
        full = int(vis + PADX + cw + PADX - x_back); bw = int(full * k)
        x0, x1 = int(x_back), int(x_back + bw)
        cx0 = vis + PADX
    else:
        vis = head_x - hw * 0.92; x_back = head_x - hw * 0.35
        full = int(x_back - (vis - PADX - cw - PADX)); bw = int(full * k)
        x0, x1 = int(x_back - bw), int(x_back)
        cx0 = vis - PADX - cw
    y0 = int(540 - box_h / 2)
    d.rounded_rectangle([x0, y0, x1, y0 + box_h], radius=26, fill=(0, 0, 0, int(110 * k)))
    words = [w_ for s, *_ in c["lines"] for w_ in s.split(" ")]
    nshow = sum(1 for i in range(len(words)) if t >= c["t_type"] + i * c["step"])
    if nshow == 0: canvas.alpha_composite(lay); return
    x = cx0
    if c.get("avatar"):
        a_ = av.copy(); a_.putalpha(avm); ring = Image.new("RGBA", (162, 162), (0, 0, 0, 0)); ImageDraw.Draw(ring).ellipse([0, 0, 161, 161], fill=(255, 255, 255, 255))
        lay.alpha_composite(ring, (int(x) - 6, 540 - 81)); lay.alpha_composite(a_, (int(x), 540 - 75))
    if c.get("twitch"):
        g = (145, 70, 255, 255); bx, by = int(x), 540 - 50
        d.polygon([(bx, by + 14), (bx + 14, by), (bx + 84, by), (bx + 84, by + 58), (bx + 56, by + 86), (bx + 40, by + 86), (bx + 26, by + 100), (bx + 26, by + 86), (bx, by + 86)], fill=g)
        d.rectangle([bx + 34, by + 22, bx + 42, by + 50], fill=(255, 255, 255, 255)); d.rectangle([bx + 56, by + 22, bx + 64, by + 50], fill=(255, 255, 255, 255))
    tx = x + icon_w
    base = 540 - th / 2                                                # top of the text block (cap line of line 1)
    wi = 0
    for li, (s, sz, col, w) in enumerate(c["lines"]):
        base += caps[li] if li == 0 else sz * GAP + caps[li]
        xx_ = tx
        for word in s.split(" "):
            if wi < nshow:
                age = t - (c["t_type"] + wi * c["step"]); f = min(1, age / 0.08)
                for ch_ in word:
                    fo = font(sz, w, not ('\u0400' <= ch_ <= '\u04FF'))
                    d.text((xx_, base + int((1 - f) * 10)), ch_, font=fo, fill=col + (int(255 * f * k),), anchor="ls")
                    xx_ += d.textlength(ch_, font=fo)
                xx_ += d.textlength(" ", font=font(sz, w))
            else:
                xx_ += tw(d, word + " ", sz, w)
            wi += 1
    canvas.alpha_composite(lay)

def frame_voice(t):
    """phase A: black, head pops and bounces to the voice"""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    pop = ease_out_back(t / 0.15, 1.6) if t < 0.15 else 1.0
    i = int((t - 0.12) * FPS); e = VENV[i] if 0 <= i < len(VENV) else 0.0
    s_ = max(0.02, 1.35 * pop * (1 + 0.07 * e)); ang = math.sin(t * 11) * 4 * e
    hh = head0.resize((max(2, int(head0.width * s_)), max(2, int(head0.height * s_))), Image.BILINEAR).rotate(ang, expand=True, resample=Image.BICUBIC)
    img.alpha_composite(hh, (int(960 - hh.width / 2), int(540 - hh.height / 2 - 8 * e)))
    return np.array(img.convert("RGB"))

def frame(t_abs):
    if t_abs < D: return frame_voice(t_abs)
    t = tb_of(t_abs)
    bg = background(t)
    if t < 0.45: bg[:] = 0
    elif t < 0.80:                                                       # circular reveal
        r = ease_io((t - 0.45) / 0.35) * 1150; cx, cy, _, _ = head_state(t)
        m = (((xx - cx) ** 2 + (yy - cy) ** 2) <= r * r).astype(np.float32); bg *= m[..., None]
    img = Image.fromarray(bg.astype(np.uint8), "RGB").convert("RGBA")
    hx, _, hs, _ = head_state(t)
    for c in CARDS: render_card(img, t, c, hx, hs)                      # cards first: the head sits in front of them
    moving = any(t0 <= t <= t0 + 0.24 for t0 in (1.10, 3.10, 4.75))
    if moving:                                                           # motion blur: average sub-frames of the head
        acc = np.zeros((H, W, 4), np.float32)
        for k in range(5):
            l = Image.new("RGBA", (W, H), (0, 0, 0, 0)); render_head(l, t - k / (FPS * 5) * 1.6)
            acc += np.array(l, np.float32)
        img.alpha_composite(Image.fromarray((acc / 5).astype(np.uint8), "RGBA"))
    else:
        render_head(img, t)
    out = np.array(img.convert("RGB"), np.float32)
    if t >= 6.55: out *= max(0, 1 - (t - 6.55) / 0.30)
    return out.astype(np.uint8)

import os
if os.environ.get("STILLS"):                       # STILLS="0.2,0.6,2.5" -> png stills only (layout check)
    for ts in os.environ["STILLS"].split(","):
        Image.fromarray(frame(float(ts))).save(f"{OUT}_{ts}.png")
    sys.exit(0)

# ---------------------------------------------------------------- sound (all synthesized)
SR = 48000; N = int(T * SR); mix = np.zeros(N, np.float32); rng = np.random.default_rng(7)
def place(sig, t, gain=1.0):
    i = int(t * SR); j = min(N, i + len(sig)); mix[i:j] += gain * sig[: j - i]
def onepole(x, a):                                 # simple lowpass, a = 0..1 (higher = brighter)
    y = np.zeros_like(x); s = 0.0
    for i in range(len(x)): s += a * (x[i] - s); y[i] = s
    return y
def sweep_noise(dur, f0, f1, env):
    n = int(dur * SR); t = np.arange(n) / SR; x = rng.standard_normal(n).astype(np.float32)
    f = f0 * (f1 / f0) ** (t / dur); a = 1 - np.exp(-2 * np.pi * f / SR)           # moving one-pole band
    lo = np.zeros(n, np.float32); lo2 = np.zeros(n, np.float32); s = s2 = 0.0
    for i in range(n): s += a[i] * (x[i] - s); s2 += a[i] * 0.35 * (x[i] - s2); lo[i] = s; lo2[i] = s2
    return (lo - lo2) * env(t / dur)
whoosh_up = sweep_noise(0.34, 500, 7000, lambda u: u ** 2.2 * (1 - np.clip((u - 0.92) / 0.08, 0, 1)))
swish = sweep_noise(0.30, 5000, 700, lambda u: np.sin(np.pi * u) ** 1.5)
def boom(dur=1.1, f0=95, f1=36, gain=1.0):
    n = int(dur * SR); t = np.arange(n) / SR
    ph = 2 * np.pi * np.cumsum(f1 + (f0 - f1) * np.exp(-t * 9)) / SR
    s = np.tanh(2.2 * np.sin(ph)) * np.exp(-t * 3.2); s[: int(0.004 * SR)] += rng.standard_normal(int(0.004 * SR)) * 0.6
    return s.astype(np.float32) * gain
def tick():
    n = int(0.03 * SR); t = np.arange(n) / SR
    return ((np.sin(2 * np.pi * 2300 * t) * 0.6 + rng.standard_normal(n) * 0.4) * np.exp(-t * 180)).astype(np.float32)
if VOICE:
    vv = np.repeat(vx[:, None], 1, 1)[:, 0]
    if vsr != SR: vv = np.interp(np.arange(int(len(vv) * SR / vsr)) * vsr / SR, np.arange(len(vv)), vv)
    place(sweep_noise(0.16, 1500, 6000, lambda u: np.sin(np.pi * u)), 0.0, 0.5)   # small pop for the head
    place(vv.astype(np.float32), 0.12, 1.25)
place(whoosh_up, t_of(0.45) - 0.34, 0.9); place(boom(), t_of(0.45), 1.0)
for t0 in (1.08, 3.08, 4.73): place(swish, t_of(t0), 0.8); place(boom(0.5, 120, 50, 0.45), t_of(t0) + 0.12)
for c in CARDS:
    nw = sum(len(s.split(" ")) for s, *_ in c["lines"])
    for i in range(nw): place(tick(), t_of(c["t_type"] + i * c["step"]), 0.35)
# music bed: 128 BPM, A minor groove — kick, offbeat hats, saw bass with sidechain, plucks
bpm = 128; beat = 60 / bpm; m0, m1 = t_of(0.45), t_of(6.62)
def kick():
    n = int(0.35 * SR); t = np.arange(n) / SR; ph = 2 * np.pi * np.cumsum(48 + 110 * np.exp(-t * 30)) / SR
    return (np.sin(ph) * np.exp(-t * 9)).astype(np.float32)
def hat():
    n = int(0.05 * SR); t = np.arange(n) / SR; x = rng.standard_normal(n); x = x - onepole(x, 0.5)
    return (x * np.exp(-t * 90)).astype(np.float32)
bassline = [55.0, 55.0, 65.41, 55.0, 49.0, 49.0, 41.2, 49.0]                    # A1 A1 C2 A1 | G1 G1 E1 G1 (8ths)
music = np.zeros(N, np.float32); duck = np.ones(N, np.float32)
k_ = kick(); h_ = hat(); t = m0; i8 = 0
while t < m1:
    ib = int(t * SR)
    if i8 % 2 == 0:
        j = min(N, ib + len(k_)); music[ib:j] += 0.9 * k_[: j - ib]
        d = np.linspace(0.35, 1, int(0.18 * SR)); j = min(N, ib + len(d)); duck[ib:j] = np.minimum(duck[ib:j], d[: j - ib])
    else:
        j = min(N, ib + len(h_)); music[ib:j] += 0.25 * h_[: j - ib]
    f = bassline[i8 % 8]; n = int(beat / 2 * SR); tt = np.arange(n) / SR
    saw = 2 * ((tt * f) % 1) - 1; saw = onepole(saw.astype(np.float32), 0.06) * np.exp(-tt * 3)
    j = min(N, ib + n); music[ib:j] += 0.55 * saw[: j - ib]
    if i8 % 4 == 3:                                                                 # pluck on every 2nd beat offbeat
        pf = f * 8; n2 = int(0.22 * SR); t2 = np.arange(n2) / SR
        pl = (np.sign(np.sin(2 * np.pi * pf * t2)) * 0.5 + np.sin(2 * np.pi * pf * 2 * t2) * 0.3) * np.exp(-t2 * 18)
        j = min(N, ib + n2); music[ib:j] += 0.12 * onepole(pl.astype(np.float32), 0.25)[: j - ib]
    t += beat / 2; i8 += 1
music *= duck
fade_in = np.clip((np.arange(N) / SR - m0) / 0.05, 0, 1); cut = np.clip((m1 - np.arange(N) / SR) / 0.03, 0, 1)
mix += 0.42 * music * fade_in * cut
mix /= max(1e-6, np.abs(mix).max()) / 0.89
stereo = np.stack([mix, mix], 1)
with wave.open(OUT + ".wav", "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((stereo * 32767).astype(np.int16).tobytes())

# ---------------------------------------------------------------- encode
p = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                      "-i", OUT + ".wav", "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-pix_fmt", "yuv420p",
                      "-c:a", "aac", "-b:a", "256k", "-shortest", "-movflags", "+faststart", OUT], stdin=subprocess.PIPE)
for i in range(NF):
    p.stdin.write(frame(i / FPS).tobytes())
    if i % 60 == 0: print(f"frame {i}/{NF}", flush=True)
p.stdin.close(); p.wait(); print("done", OUT)
