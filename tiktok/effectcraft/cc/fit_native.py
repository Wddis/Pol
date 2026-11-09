#!/usr/bin/env python3
"""ЦК-пресеты -> встроенные эффекты EffectCraft (Channel Mixer (насыщенность) -> Levels по каналам [-> Unsharp Mask]).

«Apply Color LUT» и Lumetri Input LUT в EffectCraft 0.9.2 (headless) .cube не применяют (проверено:
даже LUT-инверсия не меняет кадр), поэтому цвет пресетов собирается из эффектов с известной формулой:
  Channel Mixer: out_c = Σ k_cj·x_j/100 + const_c/100   (перекрёстные каналы: насыщенность, тинт, «Mojo»)
  Levels (R,G,B): ob + (ow-ob)·clip((x-ib)/(iw-ib))^(1/γ)
Подбор — scipy.least_squares с soft_l1 (устойчиво к неидеальному совмещению плиток).
Резкость берётся из cc_fit.json (unsharp_amount_pct).

Запуск: python3 fit_native.py <comparison.jpg> <cc_fit.json> <out cc_presets.json>
"""
import json
import os
import sys

import numpy as np
from PIL import Image
from scipy.optimize import least_squares

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fit_cc as F  # noqa: E402

BLUR = 5.0
CH = ["red", "green", "blue"]
MIX_IDS = ["rr", "rg", "rb", "rc", "gr", "gg", "gb", "gc", "br", "bg", "bb", "bc"]


def mixer(x, m):
    M = np.array(m).reshape(3, 4) / 100
    return x @ M[:, :3].T + M[:, 3]


def levels(x, lv):
    out = np.empty_like(x)
    for c in range(3):
        ib, iw, g, ob, ow = lv[5 * c:5 * c + 5]
        v = np.clip((x[..., c] - ib) / max(iw - ib, 1e-3), 0, 1) ** (1 / max(g, 0.05))
        out[..., c] = ob + (ow - ob) * v
    return out


LUMA = np.array([0.2126, 0.7152, 0.0722])


def sat_matrix(s):
    """Channel Mixer как насыщенность с сохранением яркости: M = s·I + (1-s)·[luma; luma; luma] (в %)."""
    M = s * np.eye(3) + (1 - s) * np.tile(LUMA, (3, 1))
    return [int(round(v)) for v in np.concatenate([M * 100, np.zeros((3, 1))], 1).ravel()]


def model(x, p):
    # p[0] = насыщенность (1 = без изменений); p[1:] = Levels R,G,B
    M = s_mat(p[0])
    return np.clip(levels(np.clip(x @ M.T, 0, 1), p[1:]), 0, 1)


def s_mat(s):
    return s * np.eye(3) + (1 - s) * np.tile(LUMA, (3, 1))


def main(src, ccfit, out):
    img = np.asarray(Image.open(src).convert("RGB")).astype(float) / 255
    orig = F.tile(img, "original")
    ob = F.gblur(orig, BLUR)
    sharp = json.load(open(ccfit))
    res = {}
    x0 = [1.0] + [0, 1, 1, 0, 1] * 3
    lo = [0.0] + [-0.3, 0.5, 0.3, -0.3, 0.5] * 3
    hi = [2.0] + [0.5, 1.5, 3.0, 0.5, 1.3] * 3
    for name in F.TILES:
        if name == "original":
            continue
        tb = F.gblur(F.align(img, name, orig)[0], BLUR)
        A, B = ob.reshape(-1, 3), tb.reshape(-1, 3)
        r = least_squares(lambda p: (model(A, p) - B).ravel(), x0,
                          bounds=(lo, hi), loss="soft_l1", f_scale=0.05)
        p = r.x.copy()
        rmse = float(np.sqrt(np.mean((model(A, p) - B) ** 2)))
        res[name] = {
            "saturation": round(float(p[0]), 3),
            "channel_mixer": dict(zip(MIX_IDS, sat_matrix(p[0]))),
            "levels": {c: dict(zip(["inBlack", "inWhite", "gamma", "outBlack", "outWhite"],
                                   [round(float(v), 3) for v in p[1 + 5 * i:6 + 5 * i]])) for i, c in enumerate(CH)},
            "unsharp": {"amount": sharp[name]["unsharp_amount_pct"], "radius_at_1080": round(1.5 / 165 * 1080, 1)},
            "rmse": round(rmse, 4),
            "rmse_no_grade": round(float(np.sqrt(np.mean((A - B) ** 2))), 4),
        }
        print(name, res[name]["rmse"], res[name]["rmse_no_grade"], res[name]["saturation"], res[name]["levels"])
    json.dump(res, open(out, "w"), indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main(*sys.argv[1:4])
