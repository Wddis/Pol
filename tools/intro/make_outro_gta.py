"""Outro «ПРОДОЛЖЕНИЕ В ОПИСАНИИ» in GTA 6 colours (sunset purple/pink/orange, palms, sun) with the T2x2 head and an
original synthwave melody (generated here — no third-party music, no copyright claims). 1920x1080, 60 fps, ~8 s.
usage: python3 make_outro_gta.py HEAD.png FONTS_DIR out.mp4 [--dur 8]
Appended to the END of a main video (series parts: links to the other parts are in the description)."""
import sys, math, subprocess, argparse, os, wave
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ap = argparse.ArgumentParser(); ap.add_argument("head"); ap.add_argument("fonts"); ap.add_argument("out")
ap.add_argument("--dur", type=float, default=8.0); x = ap.parse_args()
W, H, FPS, D = 1920, 1080, 60, x.dur
N = int(D * FPS)
F = lambda w, s: ImageFont.truetype(os.path.join(x.fonts, f"montserrat-cyrillic-{w}-italic.woff"), s)
ease = lambda t: 0 if t <= 0 else 1 if t >= 1 else 1 - (1 - t) ** 3

# ---------- static background (drawn 8 % larger for a slow zoom) ----------
BW, BH = int(W * 1.08), int(H * 1.08); hz = int(BH * 0.64)            # horizon
y = np.linspace(0, 1, hz)[:, None]
top, mid, low = np.array([24, 8, 58]), np.array([168, 30, 140]), np.array([255, 150, 70])
sky = np.where(y < 0.6, top + (mid - top) * (y / 0.6), mid + (low - mid) * ((y - 0.6) / 0.4))
img = np.zeros((BH, BW, 3)); img[:hz] = sky[:, None, :]
sea_y = np.linspace(0, 1, BH - hz)[:, None]
img[hz:] = (np.array([70, 20, 90]) * (1 - sea_y) + np.array([12, 4, 30]) * sea_y)[:, None, :]
bg = Image.fromarray(img.clip(0, 255).astype(np.uint8))
# sun with synthwave stripes
sun = Image.new("RGBA", (BW, BH)); d = ImageDraw.Draw(sun); R = 300; cx, cy = BW // 2, hz - 40
for r in range(R, 0, -2):
    k = r / R; c = (255, int(90 + 140 * (1 - k)), int(60 + 80 * (1 - k)), 255)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)
for i in range(7):
    yy = cy + 20 + i * 34; d.rectangle([cx - R, yy, cx + R, yy + 6 + i * 2], fill=(0, 0, 0, 0))
sun = sun.crop((0, 0, BW, hz)); sun_full = Image.new("RGBA", (BW, BH)); sun_full.paste(sun, (0, 0))
glow = sun_full.filter(ImageFilter.GaussianBlur(60))
bg = Image.alpha_composite(bg.convert("RGBA"), glow); bg = Image.alpha_composite(bg, sun_full)
# sun reflection on the sea
d = ImageDraw.Draw(bg)
for i in range(18):
    yy = hz + 14 + i * 22; w = int(R * (1 - i / 22) * 1.1)
    d.rectangle([cx - w, yy, cx + w, yy + 4], fill=(255, 150, 120, int(150 * (1 - i / 18))))
# palms (silhouettes)
def palm(dr, x0, base, h, lean, s):
    pts = [(x0 + lean * (t / 20) ** 1.6 * h * 0.25, base - h * t / 20) for t in range(21)]
    for i in range(20): dr.line([pts[i], pts[i + 1]], fill=(10, 2, 22, 255), width=int(s * (26 - i * 0.8)))
    tx, ty = pts[-1]
    for a in (-170, -140, -110, -70, -40, -10, 15, 200, 160):          # fronds: tapered drooping leaves
        ang = math.radians(a); L = h * 0.45; up, lo = [], []
        for k in np.linspace(0, 1, 16):
            px = tx + math.cos(ang) * L * k; py = ty + math.sin(ang) * L * k * 0.5 + L * 0.45 * k * k
            wdt = s * 34 * math.sin(math.pi * min(1, k * 1.1 + 0.05))
            nx, ny = -math.sin(ang), math.cos(ang)
            up.append((px + nx * wdt, py + ny * wdt * 0.6)); lo.append((px - nx * wdt, py - ny * wdt * 0.6))
        dr.polygon(up + lo[::-1], fill=(10, 2, 22, 255))
    dr.ellipse([tx - 22 * s, ty - 18 * s, tx + 22 * s, ty + 18 * s], fill=(10, 2, 22, 255))
pl = Image.new("RGBA", (BW, BH)); dp = ImageDraw.Draw(pl)
palm(dp, 120, BH, 820, 1.0, 1.0); palm(dp, 330, BH, 600, 0.6, 0.8)
palm(dp, BW - 150, BH, 860, -1.0, 1.05); palm(dp, BW - 380, BH, 560, -0.7, 0.75)
bg = Image.alpha_composite(bg, pl).convert("RGB")
# ---------- head with a pink/orange rim glow ----------
head = Image.open(x.head).convert("RGBA"); hh = 760; head = head.resize((int(head.width * hh / head.height), hh), Image.LANCZOS)
a = head.split()[3]
rim = Image.new("RGBA", head.size, (255, 80, 200, 0)); rim.putalpha(a.filter(ImageFilter.GaussianBlur(18)).point(lambda v: min(255, v * 2)))
hpad = Image.new("RGBA", (head.width + 120, head.height + 120)); hpad.alpha_composite(rim.resize(head.size), (60, 60))
hpad = hpad.filter(ImageFilter.GaussianBlur(14)); hpad.alpha_composite(head, (60, 60))
# ---------- text ----------
def txt(s, size, fill, stroke=(40, 0, 60)):
    f = F(900, size); w = int(ImageDraw.Draw(Image.new("L", (1, 1))).textlength(s, font=f)) + 40
    im = Image.new("RGBA", (w, int(size * 1.4))); dr = ImageDraw.Draw(im)
    sh = Image.new("RGBA", im.size); ImageDraw.Draw(sh).text((24, 8), s, font=f, fill=(0, 0, 0, 170)); sh = sh.filter(ImageFilter.GaussianBlur(6))
    dr.text((20, 0), s, font=f, fill=fill, stroke_width=4, stroke_fill=stroke)
    return Image.alpha_composite(sh, im)
def gradient_text(s, size):
    t = txt(s, size, (255, 255, 255, 255)); g = np.linspace(0, 1, t.height)[:, None]
    col = (np.array([255, 120, 220]) * (1 - g) + np.array([255, 190, 90]) * g)
    arr = np.array(t).astype(float); m = (arr[..., :3].mean(-1) > 200) & (arr[..., 3] > 200)
    arr[m, :3] = np.broadcast_to(col[:, None, :], arr.shape[:2] + (3,))[m]
    return Image.fromarray(arr.astype(np.uint8))
T1 = txt("ПРОДОЛЖЕНИЕ", 132, (255, 255, 255, 255)); T2 = gradient_text("В ОПИСАНИИ", 132)
T3 = txt("ДВОЙНАЯ РЕЗКА", 40, (255, 230, 250, 255))

# ---------- music: original synthwave (Am - F - C - G), soft pads + arp + bass, fades ----------
SR = 48000; t = np.arange(int(SR * D)) / SR; mus = np.zeros_like(t)
nf = lambda n: 440 * 2 ** ((n - 69) / 12)
chords = [(57, 60, 64), (53, 57, 60), (48, 52, 55), (55, 59, 62)]   # Am F C G
bar = D / 4
def env(t0, dur, a=0.02, r=0.3):
    e = np.zeros_like(t); i0, i1 = int(t0 * SR), min(len(t), int((t0 + dur + r) * SR)); tt = t[i0:i1] - t0
    e[i0:i1] = np.minimum(1, tt / a) * np.where(tt < dur, 1, np.exp(-(tt - dur) / (r / 4))); return e
def saw(f): return sum(np.sin(2 * np.pi * f * k * t) / k for k in range(1, 9))
for b, ch in enumerate(chords):
    for n in ch:
        for det in (-0.12, 0.12):
            mus += 0.06 * saw(nf(n + 12) * (1 + det / 100)) * env(b * bar, bar, a=0.4, r=0.8)
    mus += 0.22 * np.sin(2 * np.pi * nf(ch[0] - 12) * t) * env(b * bar, bar * 0.9, a=0.01, r=0.2)   # bass
    arp = [ch[0] + 24, ch[1] + 24, ch[2] + 24, ch[1] + 24]
    step = bar / 8
    for k in range(8):
        n = arp[k % 4]; e = env(b * bar + k * step, step * 0.5, a=0.005, r=0.25)
        mus += 0.09 * (np.sin(2 * np.pi * nf(n) * t) + 0.3 * np.sin(2 * np.pi * nf(n) * 2 * t)) * e
# simple stereo echo + gentle low-pass
L = mus.copy(); Rr = mus.copy(); dl = int(SR * 0.28)
L[dl:] += 0.35 * mus[:-dl]; Rr[int(dl * 1.5):] += 0.35 * mus[:-int(dl * 1.5)]
k = np.ones(6) / 6; L = np.convolve(L, k, "same"); Rr = np.convolve(Rr, k, "same")
fade = np.minimum(1, t / 0.6) * np.clip((D - t) / 1.6, 0, 1)
st = np.stack([L, Rr], 1) * fade[:, None]; st = st / np.abs(st).max() * 10 ** (-3 / 20)
wav = x.out + ".wav"; wf = wave.open(wav, "wb"); wf.setnchannels(2); wf.setsampwidth(2); wf.setframerate(SR)
wf.writeframes((st * 32767).astype(np.int16).tobytes()); wf.close()

# ---------- frames ----------
p = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                      "-i", wav, "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                      "-ar", "48000", "-shortest", "-movflags", "+faststart", x.out], stdin=subprocess.PIPE)
for i in range(N):
    s = i / FPS
    z = 1.0 + 0.08 * (1 - s / D)                                     # slow zoom-out
    cw, chh = int(W * z * 1.08 / 1.08 / 1.0), 0
    sw, sh = int(BW / z * (1 / 1.08) * 1.08), 0
    cwid = int(BW / (z * 1.0)); chei = int(BH / (z * 1.0))
    cwid = min(BW, int(W * 1.08 / z)); chei = min(BH, int(H * 1.08 / z))
    fr = bg.crop(((BW - cwid) // 2, (BH - chei) // 2, (BW - cwid) // 2 + cwid, (BH - chei) // 2 + chei)).resize((W, H), Image.BILINEAR).convert("RGBA")
    # head: slides up 0.2–1.4 s, then floats
    k = ease((s - 0.2) / 1.2); bob = 8 * math.sin(s * 2.2)
    hx = W - hpad.width - 90; hy = int(H - hpad.height + 60 + (1 - k) * 700 + bob)
    if k > 0:
        hl = hpad.copy(); hl.putalpha(hl.split()[3].point(lambda v: int(v * min(1, k * 1.5)))); fr.alpha_composite(hl, (hx, hy))
    # text: fade + rise 1.2–2.2 s, slight pulse
    for T, y0, t0 in ((T1, 300, 1.2), (T2, 460, 1.5), (T3, 660, 2.0)):
        kt = ease((s - t0) / 1.0)
        if kt <= 0: continue
        sc = 1 + (0.015 * math.sin(s * 3) if T is T2 else 0)
        Ti = T if sc == 1 else T.resize((int(T.width * sc), int(T.height * sc)), Image.BILINEAR)
        Ti = Ti.copy(); Ti.putalpha(Ti.split()[3].point(lambda v: int(v * kt)))
        fr.alpha_composite(Ti, (110, int(y0 + (1 - kt) * 40)))
    out = np.asarray(fr.convert("RGB")).astype(np.float32)
    out *= min(1, s / 0.5) * min(1, max(0, (D - s) / 1.2))           # fade in / fade out to black
    p.stdin.write(out.astype(np.uint8).tobytes())
p.stdin.close(); p.wait(); os.remove(wav)
print("done", x.out)
