#!/usr/bin/env python3
"""15-секундный эдит-демонстрация всех сконвертированных AE-эффектов в EffectCraft.

warp_v3.ffx / expo_shake.ffx — переходы на склейках (на обоих планах склейки),
cc_ball_action.aep — рассыпание фигуры на дропе, text.aep — 5 стилей текста.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
BEATS = json.load(open(os.path.join(HERE, "..", "beats.json")))
M0 = 55.5          # откуда берём трек (пауза перед дропом)
DUR = 15.0
W = H = 1080
FPS = 30
T = lambda mt: round(mt - M0, 4)   # время трека -> время эдита
DROP = T(BEATS[68])                # 2.53

steps = []
X = lambda c, **p: steps.append({"c": c, "p": p})
fx_count = {}  # layer save-name -> число эффектов


def shift_block(block, dt, layer_ref, fx_base=0, ls_shift=0.0):
    """сдвиг блока команд во времени + перенумерация effects/#n + подмена ссылки на слой."""
    out = []
    for st in block:
        p = json.loads(json.dumps(st["p"]))
        for k in ("layer",):
            if p.get(k) == "$cur":
                p[k] = layer_ref
        if "layers" in p:
            p["layers"] = [layer_ref if x == "$cur" else x for x in p["layers"]]
        if "path" in p and fx_base:
            p["path"] = re.sub(r"effects/#(\d+)", lambda m: f"effects/#{int(m.group(1)) + fx_base}", p["path"])
        if st["c"] == "prop.addKey":
            p["time"] = round(p["time"] + dt, 5)
        if st["c"] == "keys.setEase":
            p["time"] = round(p["time"] + dt - ls_shift, 5)
        if st["c"] == "keys.select":
            for k in p["keys"]:
                k["time"] = round(k["time"] + dt - ls_shift, 5)
        out.append({"c": st["c"], "p": p})
    return out


def n_effects(block):
    return sum(1 for s in block if s["c"] == "effect.apply")


def clip(name, a, b, src, rate=1.0, pos=0.5):
    """план из нарезки: слой, тайм-ремап, кроп 16:9 -> квадрат."""
    X("layer.addItem", item="cut.mp4", time=a, save=name)
    X("layer.timing", layers=[f"${name}"], start=a, **{"in": a}, out=b)
    X("layer.enableTimeRemap", layers=[f"${name}"], value=True)
    X("prop.addKey", layer=f"${name}", path="timeRemap", time=a, timeBase="comp", value=src)
    X("prop.addKey", layer=f"${name}", path="timeRemap", time=b, timeBase="comp", value=round(src + (b - a) * rate, 4))
    X("layer.setTransform", layers=[f"${name}"], prop="scale", value=[150, 150, 100])
    X("layer.setTransform", layers=[f"${name}"], prop="position", value=[round(540 + (0.5 - pos) * 840, 1), 540, 0])
    X("layer.setSwitch", layers=[f"${name}"], switch="motionBlur", value=True)
    X("layer.frameBlending", layers=[f"${name}"], mode="frameMix")
    fx_count[name] = 0
    return a


def preset(kind, layer, layer_start, t0):
    """применить сконвертированный .ffx к слою с началом в момент t0 (comp)."""
    blk = json.load(open(os.path.join(OUT, f"{kind}.steps.json")))["steps"]
    base = fx_count[layer]
    steps.extend(shift_block(blk, t0, f"${layer}", fx_base=base, ls_shift=layer_start))
    fx_count[layer] = base + n_effects(blk)


# ---------------- проект ----------------
X("file.newProject")
X("file.import", paths=["cut.mp4", "foot.mp4"])
X("comp.new", name="EDIT", width=W, height=H, frameRate=FPS, duration=DUR, background="#000000", open=True)
X("comp.settings", shutterAngle=360, motionBlurSamples=16)
X("comp.setSwitch", switch="motionBlur", value=True)

# --- 1. вступление (тишина перед дропом) ---
clip("s0", 0.0, DROP, 1.4, rate=0.75, pos=0.5)

# --- 2. дроп: cc_ball_action.aep (три слоя: фон / рассыпание / вырезанная фигура) ---
ball = json.load(open(os.path.join(OUT, "cc_ball_action.steps.json")))["steps"]
# берём команды после comp.new и сдвигаем на момент дропа
i0 = next(i for i, s in enumerate(ball) if s["c"] == "comp.new") + 1
bsteps = []
for st in ball[i0:]:
    p = json.loads(json.dumps(st["p"]))
    if st["c"] == "layer.addItem":
        p["time"] = round(p["time"] + DROP, 4)
    if st["c"] == "layer.timing":
        for k in ("start", "in", "out"):
            p[k] = round(p[k] + DROP, 4)
    if st["c"] == "prop.addKey" and p.get("timeBase") == "comp":
        p["time"] = round(p["time"] + DROP, 5)
    bsteps.append({"c": st["c"], "p": p})
steps.extend(bsteps)
BALL_END = round(DROP + 0.65, 4)
clip("s1", BALL_END, T(BEATS[70]), 22.15, pos=0.6)  # тот же план после рассыпания

# --- 3. планы по 2 бита; на склейках warp_v3 / expo_shake по очереди ---
plan = [  # (start_beat, end_beat, src, pos)
    (70, 72, 33.3, 0.45),   # лицо, жалюзи
    (72, 74, 40.2, 0.55),   # взгляд вниз
    (74, 76, 57.3, 0.78),   # профиль во дворе
    (76, 78, 68.45, 0.35),  # лицо в поту
    (78, 80, 69.4, 0.6),    # люк на крыше
    (80, 82, 74.4, 0.75),   # за сеткой
    (82, 84, 79.6, 0.5),    # улыбка
]
names = ["s1"]
starts = {"s1": BALL_END}
for k, (a, b, src, pos) in enumerate(plan):
    nm = f"p{k}"
    a_t = T(BEATS[a]); b_t = T(BEATS[b]) if k < len(plan) - 1 else T(BEATS[b])
    clip(nm, a_t, b_t, src, pos=pos)
    names.append(nm); starts[nm] = a_t
clip("last", T(BEATS[84]), DUR, 87.2, rate=0.5, pos=0.5)
names.append("last"); starts["last"] = T(BEATS[84])

# переходы: warp — середина (ключ 0.283 с) на склейке; expo — пик (0.34 с) на склейке
for j in range(len(names) - 1):
    out_l, in_l = names[j], names[j + 1]
    cut = starts[in_l]
    if j % 2 == 0:
        t0 = round(cut - 0.2833, 4); kind = "warp_v3"
    else:
        t0 = round(cut - 0.3403, 4); kind = "expo_shake"
    preset(kind, out_l, starts[out_l], t0)
    preset(kind, in_l, starts[in_l], t0)

# --- 4. мягкий общий грейд (под текстом) ---
X("layer.newAdjustment", name="GRADE", save="grade")
X("layer.timing", layers=["$grade"], start=0, **{"in": 0}, out=DUR)
X("effect.apply", effect="Lumetri Color", layers=["$grade"])
for k, v in {"basicCorrection/whiteBalance/temperature": 35, "basicCorrection/tone/contrast": 30,
             "basicCorrection/tone/blacks": -25, "basicCorrection/tone/highlights": -15,
             "creative/adjustments/sharpen": 30, "vignette/vignetteAmount": -1.5}.items():
    X("prop.set", layer="$grade", path=f"effects/#1/{k}", value=v)

# --- 5. text.aep: 5 стилей с нашими надписями ---
txt = json.load(open(os.path.join(OUT, "text.steps.json")))["steps"]
blocks, cur = {}, None
for st in txt:
    if st["c"] == "layer.newText":
        cur = []; blocks_order = blocks.setdefault("_order", [])
    if cur is not None:
        cur.append(st)
    if st["c"] == "layer.rename":
        blocks[st["p"]["name"]] = cur
        blocks["_order"].append(st["p"]["name"])
        cur = blocks[st["p"]["name"]]
SHOW = [  # (имя стиля в .aep, текст, начало, конец)
    ("текст", "MICHAEL\nSCOFIELD", 0.35, DROP - 0.05),
    ("ryani (ryani.ae)", "FOX RIVER", T(BEATS[72]) + 0.1, T(BEATS[74]) - 0.05),
    ("champ (polopqtbhu6)", "ONE WAY OUT", T(BEATS[76]) + 0.1, T(BEATS[78]) - 0.05),
    ("velep (velep.ae)", "THE PLAN", T(BEATS[80]) + 0.1, T(BEATS[82]) - 0.05),
    ("lemonchik (lme.aep)", "SCOFIELD", T(BEATS[84]) + 0.05, DUR - 0.15),
]
for name, text, a, b in SHOW:
    blk = blocks[name]
    # исходный старт слоя в .aep
    tim = next(s for s in blk if s["c"] == "layer.timing")
    old_start, old_in, old_out = tim["p"]["start"], tim["p"]["in"], tim["p"]["out"]
    dt = a - old_in
    for st in blk:
        p = json.loads(json.dumps(st["p"]))
        c = st["c"]
        if c == "layer.newText":
            p["text"] = text; p["size"] = 46
        if c == "layer.timing":
            p.update(start=round(old_start + dt, 4), **{"in": round(a, 4)}, out=round(b, 4))
        if c == "prop.addKey" and p.get("timeBase") == "comp":
            # трекинг 0->10 растягиваем на время показа
            if "tracking" in p.get("path", ""):
                p["time"] = round(a if p["value"] == 0 else b, 4)
            else:
                p["time"] = round(p["time"] + dt, 5)
        steps.append({"c": c, "p": p})

# --- 6. затухание в конце ---
X("layer.newSolid", name="FADE", color="#000000", width=W, height=H, save="fade")
X("layer.timing", layers=["$fade"], start=0, **{"in": DUR - 0.8}, out=DUR)
X("prop.addKey", layer="$fade", path="transform/opacity", time=DUR - 0.8, timeBase="comp", value=0)
X("prop.addKey", layer="$fade", path="transform/opacity", time=DUR, timeBase="comp", value=100)

json.dump({"steps": steps, "music_offset": M0, "duration": DUR}, open(sys.argv[1], "w"), ensure_ascii=False)
print(len(steps), "steps; drop at", DROP, "; cuts:", {k: v for k, v in starts.items()})
