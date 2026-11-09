#!/usr/bin/env python3
"""ЦК-пресеты -> 3D LUT (.cube) для EffectCraft («Apply Color LUT») и любого редактора (CapCut, Premiere, DaVinci).

Почему LUT, а не Levels+Saturation (fit_cc.py): Magic Bullet Looks (Mojo, Colorista, 2-Strip, Haze)
работают избирательно — кожа тёплая, фон нейтральный/холодный; глобальная насыщенность этого не повторит.
Здесь подбирается гладкое цветовое преобразование (root-polynomial 3-й степени + ridge) по паре
«Оригинал → Пресет» из кадра урока и запекается в куб 33³. Резкость (S_Sharpen + BCC Unsharp) LUT
не переносит — она идёт отдельным Unsharp Mask (amount из cc_fit.json).

Запуск: python3 fit_lut.py <comparison.jpg> <out_dir>
  -> <out_dir>/<preset>.cube, <out_dir>/lut_fit.json (ошибки на отложенной половине кадра)
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fit_cc as F  # noqa: E402

N = 33
LAMBDA = 3e-3
BLUR = 5.0
# ridge по перекрёстной проверке половин кадра (см. lut_fit.json)
LAMBDAS = {"bright": 3e-3, "dusky": 0.3, "night": 3e-3, "raspberry_haze": 0.3, "two_sharp": 0.3}


def feats(x):
    x = np.clip(x, 1e-4, 1)
    r, g, b = x[..., 0], x[..., 1], x[..., 2]
    cbrt = np.cbrt
    cols = [r, g, b,
            np.sqrt(r * g), np.sqrt(g * b), np.sqrt(r * b),
            cbrt(r * g * g), cbrt(r * r * g), cbrt(g * b * b), cbrt(g * g * b), cbrt(r * b * b), cbrt(r * r * b),
            cbrt(r * g * b),
            np.ones_like(r)]
    return np.stack(cols, -1)


def fit(src, dst):
    A = feats(src.reshape(-1, 3))
    Y = dst.reshape(-1, 3)
    # ridge к тождественному преобразованию (устойчиво вне цветов кадра)
    I = np.zeros((A.shape[1], 3)); I[0, 0] = I[1, 1] = I[2, 2] = 1
    reg = LAMBDA * len(A) * np.eye(A.shape[1])
    return np.linalg.solve(A.T @ A + reg, A.T @ Y + reg @ I)


def apply(M, x):
    return np.clip(feats(x) @ M, 0, 1)


def write_cube(path, M, title):
    g = np.linspace(0, 1, N)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")  # .cube: R меняется быстрее всего
    rgb = np.stack([r, gg, b], -1).reshape(-1, 3)
    out = apply(M, rgb)
    with open(path, "w") as f:
        f.write(f'TITLE "{title}"\nLUT_3D_SIZE {N}\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n')
        for v in out:
            f.write(f"{v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n")


def main(src, outdir):
    os.makedirs(outdir, exist_ok=True)
    img = np.asarray(Image.open(src).convert("RGB")).astype(float) / 255
    orig = F.tile(img, "original")
    ob = F.gblur(orig, BLUR)
    report = {}
    for name in F.TILES:
        if name == "original":
            continue
        tgt = F.align(img, name, orig)[0]
        global LAMBDA
        LAMBDA = LAMBDAS.get(name, 3e-3)
        tb = F.gblur(tgt, BLUR)
        w = ob.shape[1] // 2
        Mh = fit(ob[:, :w], tb[:, :w])  # проверка: учим на левой половине, меряем на правой
        held = float(np.sqrt(np.mean((apply(Mh, ob[:, w:]) - tb[:, w:]) ** 2)))
        M = fit(ob, tb)
        full = float(np.sqrt(np.mean((apply(M, ob) - tb) ** 2)))
        ident = float(np.sqrt(np.mean((ob - tb) ** 2)))
        write_cube(os.path.join(outdir, f"{name}.cube"), M, f"{name} (fit from tutorial comparison)")
        report[name] = {"lambda": LAMBDA, "rmse_fit": round(full, 4), "rmse_heldout": round(held, 4), "rmse_no_grade": round(ident, 4)}
        print(name, report[name])
    json.dump(report, open(os.path.join(outdir, "lut_fit.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
