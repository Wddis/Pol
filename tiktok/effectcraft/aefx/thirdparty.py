"""Сторонние плагины AE (Sapphire, BCC, Deep Glow, Magic Bullet…) в EffectCraft.

Сам плагин в EffectCraft 0.9.2 запустить нельзя: веб-сборка не грузит плагины
(`effect.plugins.load` → «WebAssembly effect plug-ins are not available in this build»).
Поэтому перенос устроен так:

  1. КАЖДЫЙ параметр плагина переносится на слой контролом «Эффект › Параметр»
     (Slider/Color/Point/Angle/Checkbox Control): значение, все ключи с ease и выражение — 1:1.
     Ничего не запекается и не выбрасывается; параметры остаются живыми на сайте.
  2. Картинку дают встроенные эффекты EffectCraft, чьи параметры заданы формулами-выражениями
     от этих контролов. Формулы — в рецептах ниже.

Что рецепт использовал — DERIVED (эмуляция, не побитовая копия плагина). Что не использовал, но оно
отличается от умолчания плагина — STORED (на сайте есть, на картинку не влияет) и попадает в отчёт как потеря.
"""
import os
import sys

from . import ir as IR
from .convert import Target, recipe

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "aeconv"))


def inert(c, why, *names):
    for n in names:
        p = c.P(n)
        if p is not None:
            c.mark(p, "INERT", why)


def static(c, name, default=0.0, i=0):
    """значение параметра-переключателя на момент конвертации (списки/флажки, выбирающие ветку рецепта)."""
    p = c.P(name)
    if p is None:
        return default
    if IR.is_animated(p) or p.get("expr"):
        c.rig(p)
        c.mark(p, "LOSS", "переключатель анимирован: рецепт выбирает ветку по первому значению", force=True)
        return p["anim"]["keys"][0]["value"][i] if IR.is_animated(p) else (p.get("value") or [default])[i]
    c.mark(p, "DERIVED", "выбор ветки рецепта")
    return (p.get("value") or [default])[i]


MIX = "(1 - ({Mix with Original}) / 100)"


def _raw(c, name, default=0.0):
    p = c.P(name)
    return default if p is None or IR.is_animated(p) else (p.get("value") or [default])[0]


def bcc_branches(c, e):
    """Общие блоки BCC, выключенные в файле, на картинку не влияют — их параметры не считаются потерей."""
    off = []
    if c.P("Enable Beat Reactor") is not None and _raw(c, "Enable Beat Reactor") == 0:
        off.append(("Beat Reactor", "Beat Reactor выключен"))
    pc = c.P("PixelChooser") or c.P("Legacy PixelChooser")
    if pc is not None and not IR.is_animated(pc) and (pc.get("options") or ["Off"])[int((pc.get("value") or [1])[0]) - 1] == "Off":
        off.append(("PixelChooser / Mocha", "PixelChooser выключен"))
        off.append(("PixelChooser", "PixelChooser выключен"))
    for p in e["params"]:
        g = p.get("group") or []
        for name, why in off:
            if g and g[0] == name and p["kind"] not in IR.STRUCTURAL and not IR.is_animated(p) and not p.get("expr"):
                c.mark(p, "INERT", why)


# ------------------------------------------------------------------ Sapphire
@recipe("S_Shake")
def s_shake(c, e):
    """тряска камеры: Motion Tile (зеркальные края) + Transform, шум — noise() EffectCraft по времени слоя."""
    tile = Target(c, "Motion Tile")
    tile.const("outputWidth", 300), tile.const("outputHeight", 300), tile.const("mirrorEdges", True)
    tr = Target(c, "Transform")
    tr.const("useCompositionShutterAngle", False)
    tr.expr("shutterAngle", "Math.min(360, 360 * {Mo Blur Length})")
    pre = ("var t = time - inPoint, F = {Frequency}, s = {Seed};"
           " var gate = (1 - {Stillness}) + {Stillness} * ({Drift} + (1 - {Drift}) *"
           " Math.max(0, noise([t * {Twitch Frequency} * F / 4, s + 50.5]) * 0.5 + 0.5));"
           " var A = {Amplitude} * gate; ")
    tr.expr("position", pre + "[{W} / 2 + A * {X Rand Amp} * {W} / 1920 * noise([t * F * {X Rand Freq}, s + 11.3]),"
                              " {H} / 2 + A * {Y Rand Amp} * {W} / 1920 * noise([t * F * {Y Rand Freq}, s + 23.7])]")
    tr.expr("scaleHeight", pre + "100 + A * {Z Rand Amp} * noise([t * F * {Z Rand Freq}, s + 37.1])")
    tr.expr("rotation", pre + "A * ({Tilt Rand Amp} * noise([t * F * {Tilt Rand Freq}, s + 41.9])"
                              " + {Tilt Wave Amp} * Math.sin(2 * Math.PI * {Tilt Wave Freq} * F * t))")
    c.ledger.note("S_Shake → Motion Tile + Transform; шум Sapphire заменён noise() EffectCraft (рисунок тряски другой, "
                  "амплитуды/частоты/огибающая — из параметров файла, вживую)")


@recipe("S_Sharpen")
def s_sharpen(c, e):
    """многополосная резкость: 4 Unsharp Mask по полосам деталей (tiny/small/medium/large)."""
    for band, mult in (("Tiny", 0.5), ("Small", 1.0), ("Medium", 2.0), ("Large", 4.0)):
        u = Target(c, "Unsharp Mask")
        u.expr("radius", "Math.max(0.1, {Small Detail Size} * %g * thisComp.height / 1080)" % mult)
        u.expr("amount", "Math.max(0, 100 * {Sharpen Amp} * ({Scale %s Details} - 1) / 4)" % band)
    inert(c, "вспомогательный вид/наложение", "Show Small Detail Size")
    c.ledger.note("S_Sharpen → 4×Unsharp Mask (полосы 0.5/1/2/4 × Small Detail Size). Полосы с Scale < 1 (смягчение) "
                  "Unsharp Mask выразить не может — усиление обрезается нулём")


@recipe("S_Glow")
def s_glow(c, e):
    g = Target(c, "Glow")
    g.expr("threshold", "clamp({Threshold} * 100, 0, 100)")
    g.expr("radius", "{Glow Width} * {Width X} * thisComp.height / 1080 * 0.5")
    g.expr("intensity", "{Brightness}")
    g.const("glowOperation", {1: 2, 2: 1, 3: 3, 4: 11, 5: 4}.get(int(static(c, "Combine", 3)), 3))
    g.const("colors", 1), g.expr("colorA", "{Color}"), g.expr("colorB", "[0, 0, 0, 1]")
    inert(c, "вспомогательный вид/наложение", "Show Glow Width", "Show")
    c.ledger.note("S_Glow → Glow (порог, ширина, яркость, цвет, режим наложения)")


@recipe("S_Emboss")
def s_emboss(c, e):
    b = Target(c, "Bevel Alpha")
    b.expr("lightColor", "{Light Color}")
    b.expr("edgeThickness", "{Bumps Scale} * 2")
    b.expr("lightIntensity", "Math.min(1, {Brightness} * 0.5)")
    b.expr("lightAngle", "var d = {Light Dir XY}; radiansToDegrees(Math.atan2(d[1] / {H} - 0.5, d[0] / {W} - 0.5))")
    c.ledger.note("S_Emboss → Bevel Alpha (цвет и направление света, Bumps Scale)")


@recipe("S_WarpChroma")
def s_warpchroma(c, e):
    a = Target(c, "VR Chromatic Aberrations")
    a.expr("aberrationRed", "({From Z Dist} - {To Z Dist}) * 200")
    a.const("aberrationGreen", 0)
    a.expr("aberrationBlue", "-({From Z Dist} - {To Z Dist}) * 200")
    a.const("falloff", 0)
    r = Target(c, "Radial Blur")
    r.const("type", 1)
    r.expr("amount", "{Warp Amount} * 10")
    c.ledger.note("S_WarpChroma → VR Chromatic Aberrations + Radial Blur (Zoom)")


@recipe("S_ScanLines")
def s_scanlines(c, e):
    g = Target(c, "Grid")
    g.const("sizeFrom", 2), g.const("width", 4000)
    g.expr("height", "var f = {Frequency}; Math.max(2, f ? thisComp.height / f : 4)")
    g.const("border", 1.5), g.const("color", [0, 0, 0, 1]), g.const("opacity", 35), g.const("blendingMode", 3)
    c.ledger.note("S_ScanLines → Grid (горизонтальные линии, Multiply)")


# ------------------------------------------------------------------ Boris FX (BCC)
@recipe("BCC3Unsharp Mask", "BCC Unsharp Mask")
def bcc_unsharp(c, e):
    u = Target(c, "Unsharp Mask")
    u.expr("amount", "{Amount} * " + MIX)
    u.expr("radius", "Math.max(0.1, {Radius})")
    u.expr("threshold", "{Threshold}")
    bcc_branches(c, e)
    c.ledger.note("BCC Unsharp Mask → Unsharp Mask (Amount, Radius, Threshold; Mix with Original — через Amount)")


def _film_glow(c, e, rx, ry):
    g = Target(c, "Glow")
    g.expr("threshold", "{Glow Threshold}")
    g.expr("radius", "{Glow Radius} * ({%s} + {%s}) / 200 * thisComp.height / 1080" % (rx, ry))
    g.expr("intensity", "{Glow Intensity} / 100 * " + MIX)
    g.const("glowOperation", 3)
    g.const("colors", 1), g.expr("colorA", "{Glow Color}"), g.expr("colorB", "[0, 0, 0, 1]")
    inert(c, "настройка вычислений, на результат не влияет", "GPU Rendering")
    bcc_branches(c, e)
    c.ledger.note("BCC Film Glow → Glow (порог, радиус, интенсивность, цвет; Screen)")


@recipe("BCC_FILMGLOW")
def bcc_film_glow(c, e):
    _film_glow(c, e, "Glow Radius X", "Glow Radius Y")


@recipe("BCC Fast Film Glow")
def bcc_fast_film_glow(c, e):
    _film_glow(c, e, "X Radius", "Y Radius")


@recipe("BCC6LensBlur")
def bcc_lens_blur(c, e):
    b = Target(c, "Camera Lens Blur")
    b.expr("blurRadius", "{Iris Scale} * thisComp.width / 1080")
    b.const("irisProperties/irisShape", {1: 8, 2: 0, 3: 1, 4: 2, 5: 3, 6: 5}.get(int(static(c, "Iris Shape", 5)), 3))
    b.const("repeatEdge", True)
    b.expr("highlight/specularBrightness", "var g = {Gamma}; g > 100 ? Math.min(100, (g - 100) / 6) : 0")
    b.expr("highlight/specularThreshold", "255 * {Highlight Threshold} / 100")
    c.ledger.note("BCC Lens Blur → Camera Lens Blur (Iris Scale → радиус, форма диафрагмы, блики)")


@recipe("BCC3Rays Puffy")
def bcc_rays(c, e):
    g = Target(c, "CC Light Rays")
    g.expr("center", "{Light Source}")
    g.expr("intensity", "{Intensity}")
    g.const("radius", 40)
    c.ledger.note("BCC Rays Puffy → CC Light Rays (Light Source, Intensity)")


@recipe("BCC Halftone")
def bcc_halftone(c, e):
    b = Target(c, "CC Ball Action")
    b.expr("gridSpacing", "Math.max(2, {Cell Size})")
    b.const("ballSize", 70)
    c.ledger.note("BCC Halftone → CC Ball Action (сетка точек)")


# ------------------------------------------------------------------ Plugin Everything / Crate's
@recipe("PEDG", "PEDG2")
def deep_glow(c, e):
    tint = static(c, "Tint", 0) > 0
    for frac, w in ((16, 1.6), (4, 1.0), (1, 0.6)):  # «глубокое» свечение = сумма радиусов
        g = Target(c, "Glow")
        g.const("threshold", 40)
        g.expr("radius", "Math.max(2, {Radius} / %d)" % frac)
        g.expr("intensity", "Math.max(0.05, {Exposure} * %g)" % w)
        if tint:
            g.const("colors", 1), g.expr("colorA", "{Color}"), g.expr("colorB", "{Color}")
    c.ledger.note("Deep Glow → 3×Glow с радиусами R/16, R/4, R")


@recipe("PEGR")
def godrays(c, e):
    g = Target(c, "CC Light Rays")
    g.expr("intensity", "{Exposure} * 100")
    g.expr("radius", "Math.min(200, {Length} / 5)")
    g.const("transferMode", 0)
    c.ledger.note("Crate's Godrays → CC Light Rays (Length, Exposure)")


# ------------------------------------------------------------------ Adobe: Roto Brush (свой движок в EffectCraft)
@recipe("ADBE Samurai")
def roto(c, e):
    """мазки AE рисовались на исходном клипе (в .aep его нет) — задаются для подставленного футажа (--roto)."""
    if not c.roto_strokes:
        c.ledger.add("Roto Brush: мазки и маска", "LOSS",
                     "мазки привязаны к пикселям исходного клипа; задайте --roto strokes.json для своего клипа")
        return
    tg = Target(c, "Roto Brush & Refine Edge")
    c.transfer_matching(tg, e)
    for kind, pts, r in c.roto_strokes:
        c.X("roto.stroke", layer=c.layer, kind=kind, points=pts, frame=0, radius=r)
    c.X("roto.propagate", layer=c.layer, direction="forward", wait=True)
    c.ledger.note("Roto Brush → Roto Brush EffectCraft: параметры матта перенесены, мазки заданы для подставленного клипа")


# ------------------------------------------------------------------ Red Giant: Magic Bullet Looks
@recipe("MB LookSuite3", "MB LookSuite2", "MB Looks")
def looks(c, e):
    """Look хранится в arb-блоке MBL2 (цепочка инструментов). Формат Red Giant не документирован:
    переносятся только инструменты с однозначно известным смыслом значений, остальные — LOSS поимённо."""
    from mblooks import decode_blob, TOOLS
    p = c.P("Look")
    blob = bytes.fromhex(p.get("arb", "")) if p is not None else b""
    if blob[:4] != b"MBL2":
        if p is not None:
            c.ledger.add(p, "LOSS", "блок Look не в формате MBL2")
        return
    look = decode_blob(blob)
    k = "({Strength}) / 100"
    done = 0
    for i, t in enumerate(look["tools"], 1):
        label = f"Look › {i}. {TOOLS.get(t['id'], t['id']).split(' (')[0]}"
        v = t["values"]
        if t["id"] == "expo":
            x = Target(c, "Exposure")
            x.expr("master/exposure", f"{v[0]:g} * {k}")
            c.ledger.add(label, "DERIVED", f"экспозиция {v[0]:+g} стопа", "Exposure")
            done += 1
        elif t["id"] == "husa":
            h = Target(c, "Hue/Saturation")
            h.expr("hue", f"{v[0]:g} * {k}")
            h.expr("saturation", f"clamp(({v[1]:g} - 100) * {k}, -100, 100)")
            c.ledger.add(label, "DERIVED", f"тон {v[0]:+g}°, насыщенность {v[1]:g}%", "Hue/Saturation")
            done += 1
        else:
            c.ledger.add(label, "LOSS", f"инструмент Looks «{t['id']}» без аналога/расшифровки; значения: "
                         + ", ".join(f"{x:g}" for x in v[:12]) + ("…" if len(v) > 12 else ""))
    c.ledger.add(p, "DECODED" if done == len(look["tools"]) else "LOSS",
                 f"MBL2: инструментов {len(look['tools'])}, перенесено {done}")
    inert(c, "кнопка редактора Looks", "Edit Look...")
    c.ledger.note("Magic Bullet Looks: калиброванная по кадру замена всего Look — effectcraft/cc/cc_steps.py "
                  "(Unsharp + Channel Mixer + Levels); это подгонка под картинку, а не перенос параметров")
