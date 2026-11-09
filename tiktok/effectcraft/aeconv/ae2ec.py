#!/usr/bin/env python3
"""Конвертер эффектов After Effects (.ffx / .aep) в команды EffectCraft.

Стандартные эффекты AE переносятся 1:1 (значения, ключи с bezier-ease, выражения).
Платные плагины (Sapphire, BCC, Deep Glow, Crate's Godrays) в EffectCraft не загружаются
(веб-сборка не поддерживает плагины), поэтому они собираются из встроенных эффектов
с переносом значений из исходного файла; S_Shake просчитывается в ключи по кадрам.

Использование как библиотеки:
    steps = convert_effects(effects, layer="$cur", t0=0.0, comp_w=1080, comp_h=1080)
"""
import json
import math
import os
import random
import re

TICKS = 30720.0
HERE = os.path.dirname(os.path.abspath(__file__))
EC = json.load(open(os.path.join(HERE, "ec_effects.json")))["effects"]

NATIVE = {
    "ADBE Tile": "Motion Tile", "ADBE WRPMESH": "Warp", "ADBE Exposure2": "Exposure",
    "ADBE Turbulent Displace": "Turbulent Displace", "ADBE Bevel Alpha": "Bevel Alpha",
    "ADBE Glo2": "Glow", "ADBE Drop Shadow": "Drop Shadow", "ADBE Gaussian Blur 2": "Gaussian Blur",
    "ADBE HUE SATURATION": "Hue/Saturation", "CC Ball Action": "CC Ball Action",
    "ADBE Tritone": "Tritone", "CS BlockLoad": "CC Block Load", "ADBE Geometry2": "Transform",
    "ADBE Motion Blur": "Directional Blur", "ADBE Radial Blur": "Radial Blur",
}
# AE-имя параметра -> EC-имя, где они расходятся
RENAME = {"Channels:": "Channels", "Channel Range": None}


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def ec_param(ec_name, ae_name):
    ps = EC[ec_name]["params"]
    n = norm(RENAME.get(ae_name, ae_name))
    for p in ps:
        if norm(p["name"]) == n:
            return p
    for p in ps:  # Exposure: "master/exposure" и т.п.
        if norm(p["id"].split("/")[-1]) == n:
            return p
    return None


def conv_value(p, v, comp):
    t = p["type"]
    if v is None:
        return None
    if t == "enum":
        return max(0, int(round(v[0])) - 1)  # AE popup 1-based
    if t == "bool":
        return bool(round(v[0]))
    if t == "color":  # AE: [A, R, G, B] 0..255
        a, r, g, b = (v + [255] * 4)[:4]
        return [round(r / 255, 4), round(g / 255, 4), round(b / 255, 4), 1]
    if t == "vec2":
        x, y = v[0], v[1]
        if abs(x) > 2 or abs(y) > 2:  # пиксели -> доли кадра
            x, y = x / comp[0], y / comp[1]
        return [round(x, 5), round(y, 5)]
    return v[0]


# ---------- bezier-интерполяция ключей AE (для просчёта кривых) ----------
def eval_keys(keys, t):
    """keys: [{t, v, ein:[speed, infl], eout:[speed, infl]}] в секундах."""
    if t <= keys[0]["t"]:
        return keys[0]["v"]
    if t >= keys[-1]["t"]:
        return keys[-1]["v"]
    for a, b in zip(keys, keys[1:]):
        if a["t"] <= t <= b["t"]:
            dt = b["t"] - a["t"]
            p0 = (a["t"], a["v"])
            p1 = (a["t"] + a["eout"][1] * dt, a["v"] + a["eout"][0] * a["eout"][1] * dt)
            p2 = (b["t"] - b["ein"][1] * dt, b["v"] - b["ein"][0] * b["ein"][1] * dt)
            p3 = (b["t"], b["v"])
            lo, hi = 0.0, 1.0
            for _ in range(50):
                u = (lo + hi) / 2
                x = (1 - u) ** 3 * p0[0] + 3 * (1 - u) ** 2 * u * p1[0] + 3 * (1 - u) * u * u * p2[0] + u ** 3 * p3[0]
                lo, hi = (u, hi) if x < t else (lo, u)
            u = (lo + hi) / 2
            return (1 - u) ** 3 * p0[1] + 3 * (1 - u) ** 2 * u * p1[1] + 3 * (1 - u) * u * u * p2[1] + u ** 3 * p3[1]
    return keys[-1]["v"]


def keys_sec(p, t_base):
    """AE-ключи параметра -> секунды (t_base: тик, принятый за 0)."""
    out = []
    for k in p.get("keys", []):
        out.append({"t": (k["tick"] - t_base) / TICKS, "v": k.get("value", 0.0),
                    "ein": k.get("ease_in", [0, 0.1667]), "eout": k.get("ease_out", [0, 0.1667]),
                    "interp": k.get("interp", [2, 2])})
    return out


class Out:
    origin = None  # (x, y) позиция текстового слоя: точки эффектов -> координаты слоя

    layer_size = (1280, 720)

    def pt(self, v):
        """доля кадра AE -> пиксели слоя EffectCraft."""
        W, H = self.comp
        if self.origin is not None:  # текстовый слой: начало координат в точке текста
            return [round(v[0] * W - self.origin[0], 2), round(v[1] * H - self.origin[1], 2)]
        lw, lh = self.layer_size
        return [round(v[0] * lw, 2), round(v[1] * lh, 2)]

    def __init__(self, layer, t0, comp, layer_start=0.0):
        self.layer, self.t0, self.comp, self.steps, self.n = layer, t0, comp, [], 0
        self.ls = layer_start
        self.notes = []

    def X(self, c, **p):
        self.steps.append({"c": c, "p": p})

    def add(self, ec_name):
        self.X("effect.apply", effect=ec_name, layers=[self.layer])
        self.n += 1
        return f"effects/#{self.n}"

    def set(self, path, v):
        self.X("prop.set", layer=self.layer, path=path, value=v)

    def keys(self, path, ks, scale=1.0, offset=0.0, conv=None):
        for k in ks:
            v = conv(k["v"]) if conv else k["v"] * scale + offset
            self.X("prop.addKey", layer=self.layer, path=path, time=round(self.t0 + k["t"], 5), timeBase="comp", value=v)
        for k in ks:
            if k["interp"][0] == 3 or k["interp"][1] == 3:
                self.X("keys.select", keys=[{"layer": self.layer, "path": path, "time": round(self.t0 + k["t"] - self.ls, 5)}])
                self.X("keys.interpolation", interpolation="hold")
                continue
            for side, e, code in (("in", k["ein"], k["interp"][0]), ("out", k["eout"], k["interp"][1])):
                if code == 2:  # bezier: переносим скорость и влияние как есть
                    self.X("keys.setEase", layer=self.layer, path=path, time=round(self.t0 + k["t"] - self.ls, 5),
                           side=side, speed=round(e[0] * scale, 5), influence=round(max(0.1, e[1] * 100), 3))


def pmap(e):
    """плоский словарь параметров эффекта: имя -> параметр (с учётом вложенных групп)."""
    out = {}

    def walk(ps):
        for p in ps:
            if "children" in p:
                walk(p["children"])
            elif p.get("name") and not p["name"].startswith("-_0_/-"):
                out.setdefault(p["name"], p)
    walk(e["params"] if "params" in e else e["children"])
    return out


def val(pm, name, default=0.0, i=0):
    p = pm.get(name)
    if not p or not p.get("value"):
        return default
    return p["value"][i]


def first_tick(effects):
    ts = []

    def walk(ps):
        for p in ps:
            if "children" in p:
                walk(p["children"])
            for k in p.get("keys", []):
                ts.append(k["tick"])
    for e in effects:
        walk(e.get("params") or e.get("children") or [])
    return min(ts) if ts else 0


# ---------------- нативные эффекты ----------------
def native(o, e, ec_name, t_base):
    path = o.add(ec_name)
    pm_all = pmap(e)
    for name, p in pm_all.items():
        q = ec_param(ec_name, name)
        if not q:
            continue
        sub = f"{path}/{q['id']}"
        scale = 1.0
        if ec_name == "Drop Shadow" and q["id"] == "opacity":
            scale = 100 / 255
        offset = 0.0
        if ec_name == "CC Block Load" and q["id"] == "completion":
            # шкала прогрессивной загрузки в EffectCraft сдвинута: первые блоки появляются ~40%
            scale, offset = 0.6, 80.0
        if ec_name == "Turbulent Displace" and q["id"] == "amount":
            # в AE смещение растёт с Size, в EffectCraft — нет: Amount_ec = Amount_ae * Size/100
            scale = min(1.0, val(pm_all, "Size", 100) / 100)
        if p.get("keys"):
            ks = keys_sec(p, t_base)
            if q["type"] in ("scalar",):
                o.keys(sub, ks, scale=scale, offset=offset)
            else:
                o.keys(sub, ks, conv=lambda v, q=q: conv_value(q, [v], o.comp))
        elif p.get("value") is not None and q["type"] != "layer":
            v = conv_value(q, p["value"], o.comp)
            if q["type"] == "vec2":
                v = o.pt(v)
            if q["type"] == "scalar":
                v = v * scale + offset
            o.set(sub, v)
        if p.get("expr"):
            o.X("prop.setExpression", layer=o.layer, path=sub, expression=p["expr"])
    return path


# ---------------- эмуляции платных плагинов ----------------
def s_shake(o, e, t_base, fps=60):
    """Sapphire S_Shake -> Motion Tile (wrap) + Transform (ключи по кадрам, моушен-блюр)."""
    pm = pmap(e)
    amp_p = pm.get("Amplitude")
    amp_keys = keys_sec(amp_p, t_base) if amp_p and amp_p.get("keys") else None
    amp_static = val(pm, "Amplitude", 1.0)
    F = val(pm, "Frequency", 8.0)
    W, H = o.comp
    sx = W / 1920.0
    comp_ = {
        "x": (val(pm, "X Rand Amp", 0) * sx, val(pm, "X Rand Freq", 1)),
        "y": (val(pm, "Y Rand Amp", 0) * sx, val(pm, "Y Rand Freq", 1)),
        "z": (val(pm, "Z Rand Amp", 0), val(pm, "Z Rand Freq", 1)),
        "tilt": (val(pm, "Tilt Rand Amp", 0), val(pm, "Tilt Rand Freq", 1)),
    }
    tilt_wave = (val(pm, "Tilt Wave Amp", 0), val(pm, "Tilt Wave Freq", 0.5))
    still, twitch, drift = val(pm, "Stillness", 0), val(pm, "Twitch Frequency", 1), val(pm, "Drift", 0)
    mb = val(pm, "Mo Blur Length", 0)
    rnd = random.Random(int(val(pm, "Seed", 0)) + 7)
    lat = {k: [rnd.uniform(-1, 1) for _ in range(512)] for k in list(comp_) + ["tw"]}

    def noise(k, x):
        i = int(math.floor(x)); f = x - i; u = f * f * (3 - 2 * f)
        a, b = lat[k][i % 512], lat[k][(i + 1) % 512]
        return a + (b - a) * u

    t_end = amp_keys[-1]["t"] if amp_keys else 1.0
    tile = o.add("Motion Tile")
    o.set(f"{tile}/tileCenter", o.pt([0.5, 0.5]))
    o.set(f"{tile}/outputWidth", 300); o.set(f"{tile}/outputHeight", 300); o.set(f"{tile}/mirrorEdges", True)
    tr = o.add("Transform")
    o.set(f"{tr}/useCompositionShutterAngle", False)
    o.set(f"{tr}/shutterAngle", round(min(360, 180 * mb * 2), 1))
    n = int(round(t_end * fps)) + 1
    for i in range(n):
        t = i / fps
        a = eval_keys(amp_keys, t) if amp_keys else amp_static
        gate = (1 - still) + still * (drift + (1 - drift) * max(0.0, noise("tw", t * twitch * F / 4) * 0.5 + 0.5))
        A = a * gate
        dx = A * comp_["x"][0] * noise("x", t * F * comp_["x"][1])
        dy = A * comp_["y"][0] * noise("y", t * F * comp_["y"][1])
        dz = A * comp_["z"][0] * noise("z", t * F * comp_["z"][1])
        rot = A * (comp_["tilt"][0] * noise("tilt", t * F * comp_["tilt"][1])
                   + tilt_wave[0] * math.sin(2 * math.pi * tilt_wave[1] * F * t))
        tt = round(o.t0 + t, 5)
        o.X("prop.addKey", layer=o.layer, path=f"{tr}/position", time=tt, timeBase="comp",
            value=[round(o.layer_size[0] / 2 + dx * o.layer_size[0] / W, 2), round(o.layer_size[1] / 2 + dy * o.layer_size[1] / H, 2)])
        o.X("prop.addKey", layer=o.layer, path=f"{tr}/scaleHeight", time=tt, timeBase="comp", value=round(100 + dz, 3))
        o.X("prop.addKey", layer=o.layer, path=f"{tr}/rotation", time=tt, timeBase="comp", value=round(rot, 3))
    o.notes.append("S_Shake (Sapphire) → Motion Tile + Transform: тряска просчитана по кадрам из кривой амплитуды файла")


def bcc_lens_blur(o, e, t_base):
    pm = pmap(e)
    W = o.comp[0]
    k = W / 1080.0  # Iris Scale -> радиус в px (1 ед. = 1px на 1080)
    path = o.add("Camera Lens Blur")
    shape = int(val(pm, "Iris Shape", 5))
    o.set(f"{path}/irisProperties/irisShape", {1: 8, 2: 0, 3: 1, 4: 2, 5: 3, 6: 5}.get(shape, 3))
    o.set(f"{path}/repeatEdge", True)
    gamma = val(pm, "Gamma", 100)
    thr = val(pm, "Highlight Threshold", 100)
    if gamma > 100:
        o.set(f"{path}/highlight/specularBrightness", round(min(100, (gamma - 100) / 6), 2))
        o.set(f"{path}/highlight/specularThreshold", round(255 * thr / 100, 1))
    p = pm.get("Iris Scale")
    if p and p.get("keys"):
        o.keys(f"{path}/blurRadius", keys_sec(p, t_base), scale=k)
    else:
        o.set(f"{path}/blurRadius", round(val(pm, "Iris Scale", 0) * k, 3))
    o.notes.append("BCC Lens Blur (Boris) → Camera Lens Blur (Iris Scale→Blur Radius, форма диафрагмы, блики)")


def deep_glow(o, e, t_base):
    pm = pmap(e)
    R = val(pm, "Radius", 100); X = val(pm, "Exposure", 1)
    tint = pm.get("Color")
    col = conv_value({"type": "color"}, tint["value"], o.comp) if tint and val(pm, "Tint", 0) else None
    for frac, w in ((1 / 16, 1.6), (1 / 4, 1.0), (1.0, 0.6)):  # «глубокое» свечение = сумма радиусов
        g = o.add("Glow")
        o.set(f"{g}/threshold", 40); o.set(f"{g}/radius", round(max(2, R * frac), 2))
        o.set(f"{g}/intensity", round(max(0.05, X * w), 3))
        if col:
            o.set(f"{g}/colors", 1); o.set(f"{g}/colorA", col); o.set(f"{g}/colorB", col)
    o.notes.append("Deep Glow (Plugin Everything) → 3×Glow с радиусами R/16, R/4, R и экспозицией из файла")


def godrays(o, e, t_base):
    pm = pmap(e)
    g = o.add("CC Light Rays")
    o.set(f"{g}/center", o.pt([0.5, 0.5]))
    o.set(f"{g}/intensity", round(val(pm, "Exposure", 1) * 100, 2))
    o.set(f"{g}/radius", round(min(200, val(pm, "Length", 100) / 5), 2))
    o.set(f"{g}/transferMode", 0)
    o.notes.append("Crate's Godrays → CC Light Rays (Length, Exposure)")


def bcc_rays(o, e, t_base):
    pm = pmap(e)
    g = o.add("CC Light Rays")
    src = pm.get("Light Source")
    if src and src.get("value"):
        o.set(f"{g}/center", o.pt(conv_value({"type": "vec2"}, src["value"], o.comp)))
    o.set(f"{g}/intensity", round(val(pm, "Intensity", 100), 2))
    o.set(f"{g}/radius", 40)
    o.notes.append("BCC Rays Puffy → CC Light Rays (Light Source, Intensity)")


def s_emboss(o, e, t_base):
    pm = pmap(e)
    b = o.add("Bevel Alpha")
    lc = pm.get("Light Color")
    if lc and lc.get("value"):
        o.set(f"{b}/lightColor", conv_value({"type": "color"}, lc["value"], o.comp))
    o.set(f"{b}/edgeThickness", round(val(pm, "Bumps Scale", 1) * 2, 3))
    o.set(f"{b}/lightIntensity", round(min(1, val(pm, "Brightness", 1) * 0.5), 3))
    d = pm.get("Light Dir XY")
    if d and d.get("value"):
        ang = math.degrees(math.atan2(d["value"][1] - 0.5, d["value"][0] - 0.5))
        o.set(f"{b}/lightAngle", round(ang, 2))
    o.notes.append("S_Emboss (Sapphire) → Bevel Alpha (цвет/направление света, Bumps Scale)")


def s_warpchroma(o, e, t_base):
    pm = pmap(e)
    z0, z1 = val(pm, "From Z Dist", 1), val(pm, "To Z Dist", 0.9)
    amt = val(pm, "Warp Amount", 0.2)
    c = o.add("VR Chromatic Aberrations")
    s = (z0 - z1) * 200
    o.set(f"{c}/aberrationRed", round(s, 2)); o.set(f"{c}/aberrationGreen", 0); o.set(f"{c}/aberrationBlue", round(-s, 2))
    o.set(f"{c}/falloff", 0)
    r = o.add("Radial Blur")
    o.set(f"{r}/type", 1); o.set(f"{r}/amount", round(amt * 10, 2))
    o.notes.append("S_WarpChroma (Sapphire) → VR Chromatic Aberrations + Radial Blur (Zoom)")


def s_scanlines(o, e, t_base):
    pm = pmap(e)
    g = o.add("Grid")
    o.set(f"{g}/sizeFrom", 2); o.set(f"{g}/width", 4000)
    o.set(f"{g}/height", round(max(2, val(pm, "Frequency", 0) and o.comp[1] / val(pm, "Frequency", 1) or 4), 2))
    o.set(f"{g}/border", 1.5); o.set(f"{g}/color", [0, 0, 0, 1]); o.set(f"{g}/opacity", 35); o.set(f"{g}/blendingMode", 3)
    o.notes.append("S_ScanLines (Sapphire) → Grid (горизонтальные линии, Multiply)")


def bcc_halftone(o, e, t_base):
    pm = pmap(e)
    b = o.add("CC Ball Action")
    o.set(f"{b}/gridSpacing", round(max(2, val(pm, "Cell Size", 8)), 2)); o.set(f"{b}/ballSize", 70)
    o.notes.append("BCC Halftone → CC Ball Action (сетка точек)")


PAID = {"S_Shake": s_shake, "BCC6LensBlur": bcc_lens_blur, "PEDG": deep_glow, "PEDG2": deep_glow,
        "PEGR": godrays, "BCC3Rays Puffy": bcc_rays, "S_Emboss": s_emboss, "S_WarpChroma": s_warpchroma,
        "S_ScanLines": s_scanlines, "BCC Halftone": bcc_halftone}
def roto(o, e, t_base):
    """Roto Brush: мазки AE рисовались на исходном клипе (его нет в .aep), поэтому
    мазки задаются для подставленного футажа (--roto) и маска считается ротобрашем EffectCraft."""
    if not getattr(o, "roto_strokes", None):
        o.notes.append("Roto Brush: мазки не заданы (--roto) — эффект пропущен"); return
    o.add("Roto Brush & Refine Edge")
    for kind, pts, r in o.roto_strokes:
        o.X("roto.stroke", layer=o.layer, kind=kind, points=pts, frame=0, radius=r)
    o.X("roto.propagate", layer=o.layer, direction="forward", wait=True)
    o.notes.append("Roto Brush & Refine Edge → Roto Brush EffectCraft (классическая модель, мазки по фигуре на подставленном клипе)")


PAID["ADBE Samurai"] = roto
SKIP = {}


def match_of(e):
    if e.get("match"):
        return e["match"]
    ps = e.get("params") or []
    return ps[0]["match"].rsplit("-", 1)[0] if ps else e.get("effect")


def normalize(effects):
    """формат extract_ffx (anim/d) -> формат aep_extract (keys/value/ease)."""
    def fix(ps):
        for p in ps:
            if "children" in p:
                fix(p["children"])
            a = p.pop("anim", None)
            if a:
                p["keys"] = [{"tick": k["t"], "interp": [k["in"], k["out"]], "value": k["d"][0],
                              "ease_in": [k["d"][1], k["d"][2]], "ease_out": [k["d"][3], k["d"][4]]}
                             for k in a["keys"] if len(k["d"]) >= 5]
    for e in effects:
        fix(e.get("params") or e.get("children") or [])
    return effects


def convert_effects(effects, layer="$cur", t0=0.0, comp=(1080, 1080), t_base=None, layer_start=0.0, origin=None, roto_strokes=None, layer_size=(1280, 720)):
    effects = normalize(effects)
    o = Out(layer, t0, comp, layer_start)
    o.origin = origin
    o.roto_strokes = roto_strokes
    o.layer_size = layer_size
    tb = first_tick(effects) if t_base is None else t_base
    for e in effects:
        m = match_of(e)
        if m in SKIP:
            o.notes.append(SKIP[m]); continue
        if m in PAID:
            PAID[m](o, e, tb)
        elif m in NATIVE:
            native(o, e, NATIVE[m], tb)
            o.notes.append(f"{e.get('name') or m} → {NATIVE[m]} (1:1)")
        else:
            o.notes.append(f"{e.get('name') or m}: нет соответствия, пропущен")
    return o
