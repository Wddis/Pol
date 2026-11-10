#!/usr/bin/env python3
"""Эдит «SITR» (Prison Break, 60 fps) — строго по уроку (knowledge/tutorial_ae/INSTRUCTION.md).

    python3 build_sitr.py steps.json

Только склейки на удары бита (кик/снейр трека «song for edit», ~99 BPM), на каждом клипе анимации урока:
  * Twixtor: скорость 200% → 30% → 200% (резкое падение в слоумо, плавный разгон к склейке) — time remap
    по кадрам + Pixel Motion (аналог интерполяции Twixtor);
  * Scale: 0.68 → 1.0 → 0.68 (F9 easy ease), 0.68 = кадр «впритык» без пустых краёв;
  * Exposure: 0.4 → −1 (F9); Opacity: 0 → 100 за 7 кадров в начале, так же в конце;
  * Twitch: 40 → 0 за 6 кадров в начале, 0 → 40 за 6 кадров в конце (тряска позиция/поворот/масштаб);
  * Motion blur (вместо RSMB).
ЦК — Raspberry haze, ТОЛЬКО через cc/apply_cc.py, на слой прекомпоза всех клипов (как adjustment у автора),
прекомпоз 1080×1080 на 100% — те же условия, что у эталона.
Аутро: «EDITED BY SITR» — Fade Up по буквам, Bevel Alpha + Glow (Deep Glow) + Drop Shadow, тряска как у картинки.
"""
import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "cc"))
from apply_cc import cc  # noqa: E402

FPS = 60
W = H = 1080
SRC_W, SRC_H = 1280, 720
FILL = 100 * H / SRC_H            # 150 % — кадр 16:9 закрывает квадрат по высоте
MARGIN = 1.08                     # запас, чтобы тряска Twitch не открывала края
CLIP = "clip.mp4"
MUSIC = "song.mp3"
END = 19.15                       # длина трека 19.17 c -> 1149 кадров


def fr(t):
    return round(t * FPS) / FPS


# склейки — на удары трека (кик / снейр), снятые librosa (onset + низ/середина спектра)
CUTS = [0, 0.511, 1.115, 1.718, 3.529, 4.736, 5.339, 5.943, 6.546, 8.357, 9.563, 10.167,
        10.771, 11.374, 13.184, 14.090, 14.995, 15.598, 16.202, 18.012, END]
CUTS = [fr(t) for t in CUTS]

# (исходник, с — начало плана в нарезке; pos — центр героя по горизонтали 0..1)
SHOTS = [
    (2.00, 0.47),   # костюм, ночь
    (15.0, 0.50),   # крупный план
    (26.6, 0.47),   # зелёный крупный
    (9.50, 0.47),   # с пистолетом
    (19.7, 0.60),   # двор, идёт
    (24.8, 0.36),   # взгляд вниз
    (29.0, 0.55),   # двор
    (33.5, 0.40),   # жалюзи
    (50.3, 0.55),   # коридор
    (57.6, 0.72),   # профиль у сетки
    (60.8, 0.45),   # профиль в темноте
    (62.6, 0.58),   # снизу
    (68.5, 0.45),   # тёмный крупный
    (71.87, 0.42),  # оглядывается у стены
    (76.5, 0.55),   # за сеткой
    (79.6, 0.50),   # улыбка
    (41.0, 0.40),   # серая футболка
    (44.0, 0.36),   # за решёткой
    (46.2, 0.60),    # с сокамерником
    (63.4, 0.55),   # финал — профиль в темноте
]
assert len(SHOTS) == len(CUTS) - 1
SCENES = [0, 1.267, 7.567, 9.267, 12.633, 14.4, 17.833, 19.567, 21.267, 24.7, 26.4, 28.167, 31.567,
          33.267, 34.833, 36.7, 40.1, 43.567, 46.1, 48.633, 52.9, 57.233, 60.667, 62.367, 65.867,
          67.5, 68.4, 69.3, 71.833, 73.6, 74.333, 77.8, 79.5, 81.233, 84.667, 86.367, 87.2, 88.033]


def bezier_ease(x, infl_out=1 / 3, infl_in=1 / 3):
    """значение AE-ключа между двумя ключами со скоростью 0 (F9: влияние 33.33 %)."""
    lo, hi = 0.0, 1.0
    for _ in range(50):
        s = (lo + hi) / 2
        bx = 3 * s * (1 - s) ** 2 * infl_out + 3 * s * s * (1 - s) * (1 - infl_in) + s ** 3
        lo, hi = (s, hi) if bx < x else (lo, s)
    s = (lo + hi) / 2
    return 3 * s * s * (1 - s) + s ** 3


def speed(u):
    """Twixtor Speed, % — 200 → 30 → 200; график автора: резкое падение в начале, плавный разгон к концу."""
    if u < 0.5:
        return 30 + 170 * bezier_ease(1 - u / 0.5, 0.75, 0.15)   # быстро падает, долго держит слоумо
    return 30 + 170 * bezier_ease((u - 0.5) / 0.5, 0.6, 0.3)      # мягко разгоняется в склейку


def zoom(u):
    """BCC Scale 0.68 → 1 → 0.68 (F9)."""
    return 0.68 + 0.32 * bezier_ease(1 - abs(2 * u - 1))


steps = []


def X(c, **p):
    steps.append({"c": c, "p": p})


def key(path, t, v, layer="$cur"):
    X("prop.addKey", layer=layer, path=path, time=round(t, 5), timeBase="comp", value=v)


def ease(path, t_local, side, influence=33.33, speed_=0, layer="$cur"):
    X("keys.setEase", layer=layer, path=path, time=round(t_local, 5), side=side, influence=influence, speed=speed_)


def twitch_amount(t, a, b):
    """Twitch: 40 в начале клипа → 0 через 6 кадров; так же в конце (0 → 40)."""
    n = 6 / FPS
    if t < a + n:
        return 40 * (1 - (t - a) / n)
    if t > b - n:
        return 40 * (1 - (b - t) / n)
    return 0.0


def twitch_offset(rng, amt):
    """эмуляция Twitch (Red Giant): случайный сдвиг/поворот/масштаб на каждом кадре, сила ∝ Amount."""
    k = amt / 40
    return (rng.uniform(-1, 1) * 28 * k, rng.uniform(-1, 1) * 20 * k,
            rng.uniform(-1, 1) * 1.5 * k, rng.uniform(0, 1) * 5 * k)


# ---------------- проект ----------------
X("file.newProject")
X("file.import", paths=[CLIP, MUSIC])
# прекомпоз клипов (у автора — клипы под adjustment-слоем ЦК)
X("comp.new", name="CLIPS", width=W, height=H, frameRate=FPS, duration=END, background="#000000",
  shutterAngle=180, motionBlurSamples=16, open=True)
X("comp.setSwitch", switch="motionBlur", value=True)
X("comp.setSwitch", switch="frameBlending", value=True)

plan = []
rng = random.Random(7)
for i, ((src, pos), a, b) in enumerate(zip(SHOTS, CUTS, CUTS[1:])):
    d = b - a
    n = round(d * FPS)
    # source time: интеграл скорости по кадрам (60 fps)
    times = [a + k / FPS for k in range(n + 1)]
    s, srcs = src, [src]
    for k in range(n):
        u0, u1 = k / n, (k + 1) / n
        s += (speed(u0) + 4 * speed((u0 + u1) / 2) + speed(u1)) / 6 / 100 / FPS
        srcs.append(s)
    scene_end = min(x for x in SCENES if x > src + 1e-3)
    assert srcs[-1] < scene_end - 0.02, (i, src, srcs[-1], scene_end)
    half = W / 2 / (FILL * MARGIN / 100)   # видимая полуширина исходника при 0.68
    ax = min(max(pos * SRC_W, half), SRC_W - half)
    plan.append(dict(i=i, src=src, a=a, b=b, src_end=round(srcs[-1], 3), anchor_x=round(ax, 1)))

    X("layer.addItem", item=CLIP, time=round(a, 5), save="cur")
    X("layer.rename", layer="$cur", name=f"clip{i:02d}")
    X("layer.timing", layers=["$cur"], start=round(a, 5), **{"in": round(a, 5)}, out=round(b, 5))
    X("layer.setSwitch", layers=["$cur"], switch="motionBlur", value=True)
    X("layer.frameBlending", layers=["$cur"], mode="pixelMotion")
    X("layer.enableTimeRemap", layers=["$cur"], value=True)
    for t, v in zip(times, srcs):
        key("timeRemap", t, round(v, 5))
    # масштаб (зум урока) + тряска Twitch — по кадрам
    for k, t in enumerate(times):
        u = k / n
        amt = twitch_amount(t, a, b)
        dx, dy, rot, dsc = twitch_offset(rng, amt) if amt > 0 else (0, 0, 0, 0)
        sc = FILL * MARGIN * zoom(u) / 0.68 * (1 + dsc / 100)
        key("transform/scale", t, [round(sc, 3), round(sc, 3), 100])
        # герой по центру: сдвиг кадра на (центр исходника − точка героя) × масштаб
        px = W / 2 + (SRC_W / 2 - ax) * sc / 100 + dx
        key("transform/position", t, [round(px, 2), round(H / 2 + dy, 2), 0])
        if k <= 7 or k >= n - 7:
            key("transform/rotation", t, round(rot, 3))
    # Exposure 0.4 → −1 (F9)
    X("effect.apply", effect="Exposure", layers=["$cur"])
    key("effects/#1/master/exposure", a, 0.4)
    key("effects/#1/master/exposure", b, -1.0)
    ease("effects/#1/master/exposure", 0, "out")
    ease("effects/#1/master/exposure", d, "in")
    # Opacity: 0 в начале, 100 через 7 кадров; так же в конце
    key("transform/opacity", a, 0)
    key("transform/opacity", a + 7 / FPS, 100)
    key("transform/opacity", b - 7 / FPS, 100)
    key("transform/opacity", b, 0)

# ---------------- основная композиция ----------------
X("comp.new", name="EDIT", width=W, height=H, frameRate=FPS, duration=END, background="#000000",
  shutterAngle=180, motionBlurSamples=16, open=True)
X("comp.setSwitch", switch="motionBlur", value=True)
X("layer.addItem", item=MUSIC, time=0)
X("layer.addItem", item="CLIPS", time=0, save="clips")
# ЦК — Raspberry haze, замороженный эталон (ничего не менять)
steps += cc("raspberry_haze", layer="$clips")

# ---------------- аутро: EDITED BY SITR ----------------
T_IN = CUTS[-3]          # 16.20 c — кик
T_OUT = END


def outro_text(text, size, tracking, y, save, delay):
    X("layer.newText", text=text, font="Inter", style="Bold", size=size, fill="#ffffff", tracking=tracking,
      position=[W / 2, y], justify="center", save=save)
    lay = "$" + save
    X("layer.timing", layers=[lay], start=0, **{"in": round(T_IN, 5)}, out=round(T_OUT, 5))
    # Fade Up (пресет AE): буквы проявляются и поднимаются снизу, 0 % → 100 %
    X("layer.addTextAnimator", layer=lay, properties=["opacity", "position"])
    base = "text/animators/animator"
    X("prop.set", layer=lay, path=f"{base}/properties/opacity", value=0)
    X("prop.set", layer=lay, path=f"{base}/properties/position", value=[0, size * 0.6])
    key(f"{base}/selectors/rangeSelector/start", T_IN + delay, 0, layer=lay)
    key(f"{base}/selectors/rangeSelector/start", T_IN + delay + 1.0, 100, layer=lay)
    ease(f"{base}/selectors/rangeSelector/start", T_IN + delay, "out", layer=lay)
    ease(f"{base}/selectors/rangeSelector/start", T_IN + delay + 1.0, "in", layer=lay)
    # Bevel Alpha + Deep Glow (2× Glow) + Drop Shadow
    X("effect.apply", effect="Bevel Alpha", layers=[lay])
    X("prop.set", layer=lay, path="effects/#1/edgeThickness", value=2)
    X("prop.set", layer=lay, path="effects/#1/lightIntensity", value=0.5)
    X("effect.apply", effect="Glow", layers=[lay])
    for k, v in {"threshold": 40, "radius": 14, "intensity": 1.2}.items():
        X("prop.set", layer=lay, path=f"effects/#2/{k}", value=v)
    X("effect.apply", effect="Glow", layers=[lay])
    for k, v in {"threshold": 40, "radius": 70, "intensity": 0.8}.items():
        X("prop.set", layer=lay, path=f"effects/#3/{k}", value=v)
    X("effect.apply", effect="Drop Shadow", layers=[lay])
    for k, v in {"opacity": 70, "distance": 6, "softness": 12}.items():
        X("prop.set", layer=lay, path=f"effects/#4/{k}", value=v)
    # Twitch с картинки на тексте (трясётся вместе с клипом) + затухание в конце
    trng = random.Random(11)
    a_cl = [(a, b) for a, b in zip(CUTS, CUTS[1:]) if b > T_IN]
    t = T_IN
    while t <= T_OUT + 1e-6:
        seg = next(((a, b) for a, b in a_cl if a - 1e-6 <= t <= b + 1e-6), None)
        amt = twitch_amount(t, *seg) if seg else 0
        dx, dy, rot, _ = twitch_offset(trng, amt) if amt > 0 else (0, 0, 0, 0)
        near = amt > 0 or any(abs(t - x) < 8 / FPS for x in CUTS)
        if near:
            key("transform/position", t, [round(W / 2 + dx * 0.6, 2), round(y + dy * 0.6, 2), 0], layer=lay)
        t = round(t + 1 / FPS, 6)
    key("transform/opacity", T_OUT - 0.5, 100, layer=lay)
    key("transform/opacity", T_OUT, 0, layer=lay)


outro_text("EDITED BY", 42, 380, H / 2 - 78, "t1", 0.0)
outro_text("SITR", 150, 260, H / 2 + 40, "t2", 0.25)

out = sys.argv[1]
json.dump({"steps": steps, "plan": plan, "end": END, "fps": FPS}, open(out, "w"), indent=0)
print(f"{len(steps)} steps, {len(plan)} clips, {round(END * FPS)} frames -> {out}")
for p in plan:
    print(p)
