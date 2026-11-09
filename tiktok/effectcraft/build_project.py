#!/usr/bin/env python3
"""Генерирует проект эдита для EffectCraft (браузерный AE) в виде списка команд.

Выход: steps.json — последовательность команд window.effectcraft.execute(...).
Монтаж привязан к битам трека (beats.json), стиль — по референсу:
длинные планы с медленным наездом, проявление субтитров словами с glow,
плавные переходы с моушен-блюром (zoom / whip), «световые» склейки (exposure dip),
velocity (time remap с ease), тёплый контрастный грейд Lumetri + bloom + зерно.
"""
import json
import sys

FPS = 30
F = 1 / FPS
W = H = 1080
BASE_SCALE = 150.0  # 1280x720 -> высота 1080
EXTRA_W = 1280 * BASE_SCALE / 100 - W  # 840px запаса по ширине для кропа

beats = json.load(open(sys.argv[1]))
OUT = sys.argv[2]
B = lambda i: beats[i]

DROP_AT = B(68)
BREAK_AT = 55.0
OUTRO_AT = B(86)
END_AT = 74.5

# (from, to, src, rate, pos, transition_in, velocity, dark)
# transition_in — переход В этот план: cut | dip | zoom | whipL | whipR | defocus
S = []


def seg(a, b, src, pos=0.5, rate=1.0, tr="cut", vel=False, dark=False):
    S.append(dict(a=a, b=b, src=src, pos=pos, rate=rate, tr=tr, vel=vel, dark=dark))


# --- вступление (тихая часть трека) ---
seg(0, 5.56, 1.3, 0.5, 0.8, "defocus", dark=True)
seg(5.56, 8.34, 14.5, 0.5, 0.8, "dip")
seg(8.34, B(0), 17.85, 0.4, 0.62, "cut", dark=True)
# --- бит вошёл: длинные планы по 4 бита ---
seg(B(0), B(4), 7.6, 0.35, 0.6, "zoom")
seg(B(4), B(8), 9.4, 0.5, 1.0, "cut")
seg(B(8), B(12), 12.65, 0.65, 0.63, "dip")
seg(B(12), B(16), 19.6, 0.62, 0.6, "cut")
seg(B(16), B(20), 21.4, 0.6, 1.0, "whipL")
seg(B(20), B(24), 24.72, 0.42, 0.6, "cut")
seg(B(24), B(28), 26.42, 0.42, 0.6, "dip")
seg(B(28), B(32), 28.3, 0.55, 1.0, "cut")
# --- разгон: по 2 бита ---
two = [(31.6, .45, "zoom"), (33.3, .45, "cut"), (34.9, .55, "dip"), (36.8, .6, "cut"),
       (38.5, .6, "whipR"), (40.2, .55, "cut"), (41.9, .55, "zoom"), (43.6, .5, "cut"),
       (46.2, .6, "dip"), (47.2, .6, "cut"), (48.7, .5, "whipL"), (50.5, .5, "cut"),
       (53.0, .6, "zoom"), (54.8, .6, "cut"), (57.3, .78, "dip")]
for k, (src, pos, tr) in enumerate(two):
    seg(B(32 + 2 * k), B(34 + 2 * k), src, pos, 1.0, tr)
seg(B(62), BREAK_AT, 59.0, 0.78, 1.0, "cut")
# --- пауза в треке: слоумо в темноте ---
seg(BREAK_AT, DROP_AT, 62.4, 0.72, 0.5, "dip", dark=True)
# --- дроп: каждый бит, плавные переходы с моушен-блюром + velocity ---
drop = [(60.8, .3, "zoom"), (65.9, .5, "whipL"), (67.5, .6, "cut"), (68.45, .35, "zoom"),
        (69.4, .65, "whipR"), (70.8, .65, "cut"), (71.9, .45, "dip"), (72.8, .45, "zoom"),
        (73.6, .5, "whipL"), (74.4, .75, "cut"), (76.2, .75, "zoom"), (77.9, .4, "whipR"),
        (79.6, .5, "dip"), (81.6, .45, "zoom"), (84.7, .6, "whipL"), (86.4, .6, "cut")]
for k, (src, pos, tr) in enumerate(drop):
    seg(B(68 + k), B(69 + k), src, pos, 1.0, tr, vel=(k % 2 == 0))
seg(B(84), OUTRO_AT, 87.2, 0.5, 0.5, "zoom")

# какой переход у плана на выходе (= переход следующего на вход)
for i, s in enumerate(S):
    s["tr_out"] = S[i + 1]["tr"] if i + 1 < len(S) else "cut"

CAPTIONS = [
    (0.8, 5.2, "MICHAEL SCOFIELD", "#ffffff", "#fff1d6"),
    (5.9, 8.2, "I'M GONNA GET YOU OUT", "#ffe14a", "#ffd000"),
    (8.6, 11.0, "FOX RIVER", "#ff5a4f", "#ff1a1a"),
    (BREAK_AT + 0.2, DROP_AT - 0.05, "ONE WAY OUT", "#ff5a4f", "#ff1a1a"),
]
OUTRO_TEXT = sys.argv[3] if len(sys.argv) > 3 else "SCOFIELD"

steps = []


def rgba(h):
    h = h.lstrip("#")
    return [round(int(h[i:i + 2], 16) / 255, 3) for i in (0, 2, 4)] + [1]



def X(c, **p):
    steps.append({"c": c, "p": p})


def key(path, t, v, layer="$cur"):
    X("prop.addKey", layer=layer, path=path, time=round(t, 4), timeBase="comp", value=v)


def ease(path, t_local, side, influence, speed=0, layer="$cur"):
    X("keys.setEase", layer=layer, path=path, time=round(t_local, 4), side=side,
      influence=influence, speed=speed)


X("file.newProject")
X("file.import", paths=["cut.mp4", "music.m4a"])
X("comp.new", name="EDIT", width=W, height=H, frameRate=FPS, duration=END_AT,
  background="#000000", open=True)
X("comp.settings", shutterAngle=360, motionBlurSamples=16)
X("comp.setSwitch", switch="motionBlur", value=True)
X("comp.setSwitch", switch="frameBlending", value=True)

# музыка (в проекте; в финальный файл подмешивается без перекодирования качества)
X("layer.addItem", item="music.m4a", time=0, save="music")

ZOOM = 1.65
WHIP = 1150

for i, s in enumerate(S):
    a, b, d = s["a"], s["b"], s["b"] - s["a"]
    x0 = W / 2 + (0.5 - s["pos"]) * EXTRA_W
    X("layer.addItem", item="cut.mp4", time=round(a, 4), save="cur")
    X("layer.rename", layer="$cur", name=f"seg{i:02d}")
    X("layer.timing", layers=["$cur"], start=round(a, 4), **{"in": round(a, 4)}, out=round(b, 4))
    X("layer.setSwitch", layers=["$cur"], switch="motionBlur", value=True)
    X("layer.frameBlending", layers=["$cur"], mode="frameMix")
    X("layer.enableTimeRemap", layers=["$cur"], value=True)
    # --- время: slow-mo / velocity ---
    span = d * s["rate"]
    if s["vel"]:
        span = d * 1.25
        key("timeRemap", a, s["src"])
        key("timeRemap", b, s["src"] + span)
        ease("timeRemap", 0, "out", 90, 0.15)  # старт в слоумо, разгон в склейку
    else:
        key("timeRemap", a, s["src"])
        key("timeRemap", b, s["src"] + span)

    # --- масштаб: медленный наезд + zoom-переходы ---
    push = lambda t: BASE_SCALE * (1 + 0.07 * (t - a) / d)
    sc = []
    if s["tr"] == "zoom":
        sc += [(a, BASE_SCALE * ZOOM, ("out", 0, 0)), (a + 7 * F, push(a + 7 * F), ("in", 85, 0))]
    else:
        sc += [(a, push(a), None)]
    if s["tr_out"] == "zoom":
        sc += [(b - 5 * F, push(b - 5 * F), ("out", 75, 0)), (b - F, push(b) * ZOOM, None)]
    else:
        sc += [(b - F, push(b), None)]
    for t, v, _ in sc:
        key("transform/scale", t, [round(v, 2), round(v, 2), 100])
    for t, v, e in sc:
        if e and e[1]:
            ease("transform/scale", t - a, e[0], e[1], e[2])

    # --- позиция: whip pan ---
    whip_in = s["tr"] in ("whipL", "whipR")
    whip_out = s["tr_out"] in ("whipL", "whipR")
    fx = 0
    if whip_in or whip_out:
        X("effect.apply", effect="Motion Tile", layers=["$cur"])
        fx += 1
        X("prop.set", layer="$cur", path=f"effects/#{fx}/outputWidth", value=400)
        X("prop.set", layer="$cur", path=f"effects/#{fx}/mirrorEdges", value=True)
        ps = []
        if whip_in:
            sgn = 1 if s["tr"] == "whipL" else -1
            ps += [(a, x0 + sgn * WHIP, None), (a + 6 * F, x0, ("in", 88))]
        else:
            ps += [(a, x0, None)]
        if whip_out:
            sgn = -1 if s["tr_out"] == "whipL" else 1
            ps += [(b - 4 * F, x0, ("out", 80)), (b - F, x0 + sgn * WHIP, None)]
        for t, v, _ in ps:
            key("transform/position", t, [round(v, 1), H / 2, 0])
        for t, v, e in ps:
            if e:
                ease("transform/position", t - a, e[0], e[1])
    else:
        X("prop.set", layer="$cur", path="transform/position", value=[round(x0, 1), H / 2, 0])

    # --- световая склейка (exposure dip) / тёмные планы / дефокус ---
    dip_in = s["tr"] in ("dip", "defocus")
    dip_out = s["tr_out"] == "dip"
    if dip_in or dip_out or s["dark"]:
        X("effect.apply", effect="Exposure", layers=["$cur"])
        fx += 1
        p = f"effects/#{fx}/master/exposure"
        base = -0.3 if s["dark"] else 0.0
        ks = []
        if s["tr"] == "dip":
            ks += [(a, base + 2.3), (a + 8 * F, base)]
        elif s["tr"] == "defocus":
            ks += [(a, -4.0), (a + 1.2, base)]
        else:
            ks += [(a, base)]
        if dip_out:
            ks += [(b - 5 * F, base), (b - F, base + 2.3)]
        for t, v in ks:
            key(p, t, v)
    if s["tr"] == "defocus":
        X("effect.apply", effect="Gaussian Blur", layers=["$cur"])
        fx += 1
        p = f"effects/#{fx}/blurriness"
        key(p, a, 45)
        key(p, a + 1.4, 0)
        ease(p, 1.4, "in", 80)
        X("prop.set", layer="$cur", path=f"effects/#{fx}/repeatEdge", value=True)

# --- общий цветокор: adjustment layer над видео ---
X("layer.newAdjustment", name="GRADE", save="grade")
X("layer.timing", layers=["$grade"], start=0, **{"in": 0}, out=OUTRO_AT)
X("effect.apply", effect="Lumetri Color", layers=["$grade"])
LUM = {
    "basicCorrection/whiteBalance/temperature": 55,
    "basicCorrection/whiteBalance/tint": 14,
    "basicCorrection/tone/exposure": 0.1,
    "basicCorrection/tone/contrast": 30,
    "basicCorrection/tone/highlights": -20,
    "basicCorrection/tone/shadows": -10,
    "basicCorrection/tone/whites": 5,
    "basicCorrection/tone/blacks": -25,
    "basicCorrection/saturation": 95,
    "creative/adjustments/sharpen": 40,
    "creative/adjustments/shadowTint": [0.47, 0.5, 0.53, 1],
    "creative/adjustments/highlightTint": [0.62, 0.52, 0.4, 1],
    "vignette/vignetteAmount": -1.6,
    "vignette/vignetteMidpoint": 40,
    "vignette/vignetteFeather": 75,
}
for k, v in LUM.items():
    X("prop.set", layer="$grade", path=f"effects/#1/{k}", value=v)
# тёплый фильтр + тил/оранж кривыми (как в референсе: тёплая кожа, глубокие тени)
X("effect.apply", effect="Photo Filter", layers=["$grade"])
X("prop.set", layer="$grade", path="effects/#2/density", value=30)
X("effect.apply", effect="Curves", layers=["$grade"])
X("prop.set", layer="$grade", path="effects/#3/rgb", value="0,0 0.25,0.2 0.75,0.8 1,1")
X("prop.set", layer="$grade", path="effects/#3/red", value="0,0 0.5,0.55 1,1")
X("prop.set", layer="$grade", path="effects/#3/blue", value="0,0.02 0.5,0.44 1,0.86")
X("effect.apply", effect="Glow", layers=["$grade"])
for k, v in {"threshold": 86, "radius": 30, "intensity": 0.3}.items():
    X("prop.set", layer="$grade", path=f"effects/#4/{k}", value=v)
X("effect.apply", effect="Add Grain", layers=["$grade"])
for k, v in {"preset": 1, "tweaking/intensity": 0.4, "tweaking/size": 1.1}.items():
    X("prop.set", layer="$grade", path=f"effects/#5/{k}", value=v)

# --- субтитры: слова проявляются из блюра, с цветным свечением ---
for (a, b, text, fill, glow) in CAPTIONS:
    X("layer.newText", text=text, font="Inter", style="Bold", size=34, fill=fill,
      tracking=40, position=[W / 2, H / 2 + 12], justify="center", save="cur")
    X("layer.timing", layers=["$cur"], start=0, **{"in": round(a, 4)}, out=round(b, 4))
    X("layer.addTextAnimator", layer="$cur", properties=["opacity", "blur"])
    base = "text/animators/animator"
    X("prop.set", layer="$cur", path=f"{base}/properties/opacity", value=0)
    X("prop.set", layer="$cur", path=f"{base}/properties/blur", value=[14, 14])
    X("prop.set", layer="$cur", path=f"{base}/selectors/rangeSelector/advanced/basedOn", value=2)
    n = len(text.split())
    rev = min(0.28 * n + 0.2, (b - a) * 0.55)
    key(f"{base}/selectors/rangeSelector/start", a, 0)
    key(f"{base}/selectors/rangeSelector/start", a + rev, 100)
    X("effect.apply", effect="Glow", layers=["$cur"])
    for k, v in {"threshold": 15, "radius": 22, "intensity": 2.2, "colors": 1,
                 "colorA": rgba(glow), "colorB": rgba(glow)}.items():
        X("prop.set", layer="$cur", path=f"effects/#1/{k}", value=v)
    X("effect.apply", effect="Gaussian Blur", layers=["$cur"])
    key("effects/#2/blurriness", b - 0.3, 0)
    key("effects/#2/blurriness", b, 18)
    key("transform/opacity", b - 0.3, 100)
    key("transform/opacity", b, 0)

# --- финальный титр: буквы загораются в случайном порядке, тёплый glow ---
X("layer.newSolid", name="OUTRO BG", color="#000000", width=W, height=H, save="bg")
X("layer.timing", layers=["$bg"], start=0, **{"in": OUTRO_AT}, out=END_AT)
X("layer.newText", text=OUTRO_TEXT, font="Noto Serif", style="Regular", size=92,
  fill="#ffffff", tracking=260, vScale=140, position=[W / 2, H / 2 + 30], justify="center",
  save="cur")
X("layer.timing", layers=["$cur"], start=0, **{"in": OUTRO_AT}, out=END_AT)
X("layer.addTextAnimator", layer="$cur", properties=["opacity", "blur"])
base = "text/animators/animator"
X("prop.set", layer="$cur", path=f"{base}/properties/opacity", value=0)
X("prop.set", layer="$cur", path=f"{base}/properties/blur", value=[6, 6])
X("prop.set", layer="$cur", path=f"{base}/selectors/rangeSelector/advanced/randomize", value=True)
X("prop.set", layer="$cur", path=f"{base}/selectors/rangeSelector/advanced/randomSeed", value=7)
key(f"{base}/selectors/rangeSelector/start", OUTRO_AT + 0.1, 0)
key(f"{base}/selectors/rangeSelector/start", OUTRO_AT + 1.4, 100)
X("effect.apply", effect="Glow", layers=["$cur"])
X("prop.set", layer="$cur", path="effects/#1/colors", value=1)
X("prop.set", layer="$cur", path="effects/#1/radius", value=30)
X("prop.set", layer="$cur", path="effects/#1/threshold", value=10)
# glow сначала белый и мерцает, потом тёплый
gi = [(0.0, 0.4), (0.15, 2.2), (0.3, 0.6), (0.45, 2.4), (0.6, 1.0), (0.8, 2.0), (1.5, 1.6)]
for t, v in gi:
    key("effects/#1/intensity", OUTRO_AT + t, v)
key("effects/#1/colorA", OUTRO_AT + 1.0, [1, 1, 1, 1])
key("effects/#1/colorA", OUTRO_AT + 1.8, [1, 0.82, 0.35, 1])
key("effects/#1/colorB", OUTRO_AT + 1.0, [1, 1, 1, 1])
key("effects/#1/colorB", OUTRO_AT + 1.8, [1, 0.7, 0.2, 1])
key("transform/opacity", END_AT - 0.6, 100)
key("transform/opacity", END_AT - 0.05, 0)

json.dump({"steps": steps, "segments": S, "end": END_AT}, open(OUT, "w"), indent=0)
print(f"{len(steps)} steps, {len(S)} segments -> {OUT}")
