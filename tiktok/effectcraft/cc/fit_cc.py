#!/usr/bin/env python3
"""Калибровка готовых ЦК (Bright / Dusky / Night / Raspberry haze / Two sharp) под EffectCraft.

Источник истины — кадр сравнения из урока (knowledge/cc_presets/comparison_from_tutorial.jpg):
6 плиток одного кадра: Оригинал + 5 пресетов, применённых в настоящем AE с плагинами
(Sapphire S_Sharpen, BCC Unsharp Mask, Magic Bullet Looks), которых в EffectCraft нет.

Модель EffectCraft (только встроенные эффекты, порядок на adjustment-слое):
  1. Unsharp Mask  (amount, radius)        — «шарпы» (S_Sharpen + BCC Unsharp)
  2. Levels         (по каналам R,G,B: inBlack, inWhite, gamma, outBlack, outWhite) — цвет/тон Looks
  3. Hue/Saturation (master saturation)     — насыщенность Looks
Подбор: Нелдер–Мид (numpy), цвет — по слегка размытым плиткам, резкость — по энергии высоких частот.

Запуск: python3 fit_cc.py <comparison.jpg> <out.json>
"""
import json
import sys

import numpy as np
from PIL import Image

# плитки на кадре 854x480 (найдены по профилю яркости), подписи внизу обрезаем
COLS = {0: (99, 282), 1: (333, 514), 2: (569, 752)}
ROWS = {0: (45, 210), 1: (263, 428)}
TILES = {"original": (0, 0), "bright": (0, 1), "dusky": (0, 2),
         "night": (1, 0), "raspberry_haze": (1, 1), "two_sharp": (1, 2)}
PAD, LABEL = 8, 48
LUMA = np.array([0.2126, 0.7152, 0.0722])


def gblur(a, s):
    if s <= 0:
        return a
    r = int(3 * s) + 1
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / s) ** 2)
    k /= k.sum()
    pad = np.pad(a, ((r, r), (r, r), (0, 0)), mode="edge")
    t = sum(k[i] * pad[i:i + a.shape[0], r:-r or None] for i in range(2 * r + 1))
    t = np.pad(t, ((0, 0), (r, r), (0, 0)), mode="edge")
    return sum(k[i] * t[:, i:i + a.shape[1]] for i in range(2 * r + 1))


def tile(img, name, dy=0, dx=0):
    r, c = TILES[name]
    y0, y1 = ROWS[r]
    x0, x1 = COLS[c]
    h, w = 165 - PAD - LABEL, 181 - 2 * PAD  # фиксированный размер (плитки отличаются на 1–2 px)
    return img[y0 + PAD + dy:y0 + PAD + dy + h, x0 + PAD + dx:x0 + PAD + dx + w]


def levels(x, p):
    out = np.empty_like(x)
    for ch in range(3):
        ib, iw, g, ob, ow = p[5 * ch:5 * ch + 5]
        v = np.clip((x[..., ch] - ib) / max(iw - ib, 1e-3), 0, 1) ** (1 / max(g, 0.05))
        out[..., ch] = ob + (ow - ob) * v
    return out


def saturate(x, s):
    L = (x @ LUMA)[..., None]
    return L + (x - L) * (1 + s / 100)


def model(x, p):
    return np.clip(saturate(levels(x, p[:15]), p[15]), 0, 1)


def nelder_mead(f, x0, step, iters=4000):
    n = len(x0)
    pts = [np.array(x0, float)] + [np.array(x0, float) + np.eye(n)[i] * step[i] for i in range(n)]
    vals = [f(p) for p in pts]
    for _ in range(iters):
        o = np.argsort(vals)
        pts = [pts[i] for i in o]
        vals = [vals[i] for i in o]
        c = np.mean(pts[:-1], 0)
        xr = c + (c - pts[-1]); fr = f(xr)
        if fr < vals[0]:
            xe = c + 2 * (c - pts[-1]); fe = f(xe)
            pts[-1], vals[-1] = (xe, fe) if fe < fr else (xr, fr)
        elif fr < vals[-2]:
            pts[-1], vals[-1] = xr, fr
        else:
            xc = c + 0.5 * (pts[-1] - c); fc = f(xc)
            if fc < vals[-1]:
                pts[-1], vals[-1] = xc, fc
            else:
                pts = [pts[0]] + [pts[0] + 0.5 * (q - pts[0]) for q in pts[1:]]
                vals = [vals[0]] + [f(q) for q in pts[1:]]
        if np.std(vals) < 1e-9:
            break
    return pts[int(np.argmin(vals))], min(vals)


def align(img, name, ref):
    best = None
    for dy in range(-4, 5):
        for dx in range(-4, 5):
            t = tile(img, name, dy, dx)
            if t.shape != ref.shape:
                continue
            a, b = t @ LUMA, ref @ LUMA
            ga, gb = np.diff(a, axis=1), np.diff(b, axis=1)
            cc = np.corrcoef(ga.ravel(), gb.ravel())[0, 1]
            if best is None or cc > best[0]:
                best = (cc, dy, dx)
    return tile(img, name, best[1], best[2]), best


def hf(x):
    L = x @ LUMA
    return float(np.mean(np.abs(L - gblur(L[..., None], 1.5)[..., 0])))


def main(src, out):
    img = np.asarray(Image.open(src).convert("RGB")).astype(float) / 255
    orig = tile(img, "original")
    ob = gblur(orig, 1.5)
    res = {}
    for name in TILES:
        if name == "original":
            continue
        tgt, al = align(img, name, orig)
        tb = gblur(tgt, 1.5)
        x0 = [0, 1, 1, 0, 1] * 3 + [0]
        loss = lambda p: float(np.mean((model(ob, p) - tb) ** 2)) + 1e-4 * sum(
            max(0, p[5 * c + 1] - p[5 * c] - 1.5) for c in range(3))
        p, err = nelder_mead(loss, x0, [0.05, 0.05, 0.2, 0.05, 0.05] * 3 + [10])
        # резкость: amount unsharp (радиус ~1.5px на плитке 170px ≈ 9px на 1080) по энергии ВЧ
        graded = model(orig, p)
        target_hf = hf(tgt)
        best = (1e9, 0)
        for amt in np.arange(0, 3.01, 0.05):
            sh = np.clip(graded + amt * (graded - gblur(graded, 1.5)), 0, 1)
            d = abs(hf(sh) - target_hf)
            if d < best[0]:
                best = (d, amt)
        rmse = float(np.sqrt(np.mean((np.clip(graded + best[1] * (graded - gblur(graded, 1.5)), 0, 1) - tgt) ** 2)))
        res[name] = {
            "levels": {ch: dict(zip(["inBlack", "inWhite", "gamma", "outBlack", "outWhite"],
                                    [round(float(v), 3) for v in p[5 * i:5 * i + 5]]))
                       for i, ch in enumerate(["red", "green", "blue"])},
            "saturation": round(float(p[15]), 1),
            "unsharp_amount_pct": int(round(best[1] * 100)),
            "unsharp_radius_px_at_1080": 9.0,
            "fit": {"rmse": round(rmse, 4), "align": [round(al[0], 3), al[1], al[2]],
                    "mean_rgb_target": [round(float(v), 3) for v in tgt.reshape(-1, 3).mean(0)],
                    "mean_rgb_fit": [round(float(v), 3) for v in model(orig, p).reshape(-1, 3).mean(0)]},
        }
        print(name, res[name]["saturation"], res[name]["unsharp_amount_pct"], res[name]["fit"])
    res["_original_mean_rgb"] = [round(float(v), 3) for v in orig.reshape(-1, 3).mean(0)]
    json.dump(res, open(out, "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
