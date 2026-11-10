"""Съёмка с хоста -> пакет ЦК для движка + измеренное совпадение.

Порядок: для каждого эффекта (и для всего стека) по паре hald / hald_shuffled определяется, зависит ли
результат только от цвета пикселя. Если да — эффект снимается точной таблицей (LUT 128³). Если нет — это
пространственный эффект: подбирается модель (линейный фильтр или свечение) на fit.png.
Итог всегда измеряется на test.png, которого подбор не видел: вердикт ставит число, а не обещание.
"""
import json
import os

import numpy as np
from PIL import Image

from . import charts, engine

SIGMAS = [0.5, 1, 2, 4, 8, 16, 32, 64]
POINTWISE_TOL = 1.5      # уровней из 255: расхождение hald / hald_shuffled, при котором эффект ещё «только цвет»
IDENTICAL_TOL = 1        # макс. отличие в уровнях, при котором результат называется идентичным


def _pack(lut):
    """полная таблица снята с 8-битного рендера -> хранится в 8 битах; интерполируемые — в 16."""
    return np.round(lut * 255).astype(np.uint8) if lut.shape[0] == 256 else np.round(lut * 65535).astype(np.uint16)


def _save(meta, out):
    with open(os.path.join(out, "package.json"), "w", encoding="utf8") as fh:
        json.dump(meta, fh, ensure_ascii=False, indent=1)


def load(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float64) / 255.0


def diff(a, b):
    d = np.abs(a - b) * 255.0
    mse = float(np.mean((a - b) ** 2))
    return {"max": round(float(d.max()), 2), "mean": round(float(d.mean()), 3), "p99": round(float(np.percentile(d, 99)), 2),
            "psnr": round(10 * np.log10(1.0 / mse), 2) if mse > 0 else 99.0}


def pointwise(cap, tag):
    """(таблица 64³ | None, расхождение): один и тот же цвет обязан дать один результат в любом месте кадра."""
    a = load(cap(f"hald__{tag}.png")).reshape(-1, 3)
    b = load(cap(f"hald_shuffled__{tag}.png")).reshape(-1, 3)
    n = charts.size_to_n(len(a))
    back = np.empty_like(b)
    back[charts.permutation(n)] = b
    dev = float(np.abs(a - back).max() * 255.0)
    lut = (a if n == 256 else (a + back) / 2).reshape(n, n, n, 3) if dev <= POINTWISE_TOL else None
    return lut, round(dev, 2)


def fit_linear(x, y):
    ok = ((y > 1 / 255) & (y < 254 / 255)).all(axis=2)            # обрезанные пиксели подбору мешают
    basis = [(x - engine.gauss(x, s))[ok].ravel() for s in SIGMAS]
    A = np.stack(basis, axis=1)
    g, *_ = np.linalg.lstsq(A, (y - x)[ok].ravel(), rcond=None)
    return {"type": "linear", "sigmas": SIGMAS, "gains": [round(float(v), 5) for v in g]}


def fit_glow(x, y):
    best = None
    for mode in ("add", "screen"):
        for thr in (0.0, 0.2, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9):
            src = np.clip((x - thr) / (1 - thr), 0, 1)
            for s in SIGMAS[2:]:
                B = engine.gauss(src, s)
                T = (y - x) if mode == "add" else (y - x) / np.maximum(1 - x, 1e-3)
                gain = [float(np.sum(B[..., c] * T[..., c]) / max(1e-9, np.sum(B[..., c] ** 2))) for c in range(3)]
                op = {"type": "glow", "mode": mode, "threshold": thr, "sigma": s, "gain": [round(max(0.0, v), 5) for v in gain]}
                err = float(np.mean((engine.apply_op(x, op, {}) - y) ** 2))
                if best is None or err < best[0]:
                    best = (err, op)
    return best[1]


def fit_spatial(cap, tag, pre):
    """пространственный эффект: лучшая из моделей на fit.png; pre — цветовая часть эффекта, если она есть."""
    x, y = load(cap("fit.png", chart=True)), load(cap(f"fit__{tag}.png"))
    cands = []
    for op in (fit_linear(x, y), fit_glow(x, y)):
        cands.append((float(np.mean((engine.apply_op(x, op, {}) - y) ** 2)), op))
    err, op = min(cands, key=lambda c: c[0])
    op["ref_height"] = None
    op["fit"] = diff(engine.apply_op(x, op, {}), y)
    return op


def _merge_luts(ops, out):
    """соседние полные таблицы склеиваются в одну: lut2[lut1[цвет]] — точно, без потери."""
    res = []
    for op in ops:
        prev = res[-1] if res else None
        if prev and prev["type"] == "lut" and op["type"] == "lut" and prev.get("size") == 256 and op.get("size") == 256:
            a = np.load(os.path.join(out, prev["lut"]))["lut"]
            b = np.load(os.path.join(out, op["lut"]))["lut"]
            m = b[a[..., 2], a[..., 1], a[..., 0]]
            os.remove(os.path.join(out, op["lut"]))
            np.savez_compressed(os.path.join(out, prev["lut"]), lut=m)
            prev["covers"] += " + " + op["covers"]
        else:
            res.append(op)
    return res


def compile_capture(folder, out):
    with open(os.path.join(folder, "manifest.json"), encoding="utf8") as fh:
        man = json.load(fh)

    def cap(name, chart=False):
        return os.path.join(folder, "charts" if chart else "", name)
    os.makedirs(out, exist_ok=True)
    ops, report = [], []
    lut, dev = pointwise(cap, "full")
    if lut is not None:
        np.savez_compressed(os.path.join(out, "full.npz"), lut=_pack(lut))
        ops.append({"type": "lut", "lut": "full.npz", "size": lut.shape[0], "covers": "весь стек"})
        report.append({"effect": "весь стек", "kind": "цвет", "deviation": dev})
    else:
        report.append({"effect": "весь стек", "kind": "есть пространственные эффекты", "deviation": dev})
        for e in man["effects"]:
            tag = f"fx{e['index']:02d}"
            lut, dev = pointwise(cap, tag)
            if lut is not None:
                ident = float(np.abs(lut.reshape(-1, 3) - charts.hald(lut.shape[0]).reshape(-1, 3) / 255.0).max() * 255)
                if ident <= 0.5:
                    report.append({"effect": e["name"], "kind": "ничего не меняет", "deviation": dev})
                    continue
                np.savez_compressed(os.path.join(out, tag + ".npz"), lut=_pack(lut))
                ops.append({"type": "lut", "lut": tag + ".npz", "size": lut.shape[0], "covers": e["name"]})
                report.append({"effect": e["name"], "kind": "цвет", "deviation": dev})
            else:
                op = fit_spatial(cap, tag, None)
                op["covers"] = e["name"]
                ops.append(op)
                report.append({"effect": e["name"], "kind": "пространственный → " + op["type"], "deviation": dev, "fit": op["fit"]})
    ops = _merge_luts(ops, out)
    meta = {"look": man["look"], "host": man["host"], "ops": ops, "effects": report}
    # масштаб радиусов: абсолютные пиксели или доля высоты кадра — выбирается по test.png, а не допущением
    fit_h = load(cap("fit.png", chart=True)).shape[0]
    x, y = load(cap("test.png", chart=True)), load(cap("test__full.png"))
    best = None
    for ref in (None, fit_h):
        for op in ops:
            if op["type"] != "lut":
                op["ref_height"] = ref
        _save(meta, out)
        got = engine.Package(out).apply(np.round(x * 255).astype(np.uint8)).astype(np.float64) / 255.0
        d = diff(got, y)
        if best is None or d["mean"] < best[1]["mean"]:
            best = (ref, d)
        if all(op["type"] == "lut" for op in ops):
            break
    for op in ops:
        if op["type"] != "lut":
            op["ref_height"] = best[0]
    d = best[1]
    meta["verify"] = {"chart": "test.png", **d, "baseline": diff(x, y)}
    meta["verdict"] = "ИДЕНТИЧНО" if d["max"] <= IDENTICAL_TOL else "БЛИЗКО" if d["p99"] <= 3 else "ПРИБЛИЖЕНИЕ"
    _save(meta, out)
    return meta


def summary(meta):
    v, b = meta["verify"], meta["verify"]["baseline"]
    lines = [f"ЦК «{meta['look']}» (снят с: {meta['host']}) — {meta['verdict']}",
             f"  совпадение с хостом на test.png: макс. {v['max']} ур., 99% пикселей ≤ {v['p99']}, среднее {v['mean']} (из 255), PSNR {v['psnr']} дБ",
             f"  для сравнения, кадр без ЦК отличается от хоста: макс. {b['max']}, среднее {b['mean']}"]
    for r in meta["effects"]:
        f = f" — подбор: макс. {r['fit']['max']}, среднее {r['fit']['mean']}" if "fit" in r else ""
        lines.append(f"    {r['effect']}: {r['kind']}{f}")
    return "\n".join(lines)
