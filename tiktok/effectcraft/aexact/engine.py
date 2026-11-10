"""Движок aexact: применяет «пакет ЦК» к кадру. Собственный рендер — не зависит от EffectCraft и его плагинов.

Пакет — упорядоченный список операций, полученных из съёмки с хоста (compile.py):
  lut     точная таблица цвета 128³ (трилинейная интерполяция)
  linear  линейный пространственный фильтр: out = in + Σ a_k·(in − G_σk(in))   (резкость / размытие)
  glow    свечение: размытые света, сложенные с кадром (add или screen)
Одинаковый вход -> одинаковый выход, бит в бит.
"""
import json
import os

import numpy as np

from . import charts


def gauss(img, sigma):
    """гауссово размытие (H, W, C) float; края — зеркалом."""
    if sigma <= 0:
        return img
    pad = int(min(max(img.shape[0], img.shape[1]) - 1, np.ceil(3 * sigma)))
    x = np.pad(img, ((pad, pad), (pad, pad), (0, 0)), mode="reflect")
    H, W = x.shape[:2]
    fy = np.fft.fftfreq(H)[:, None]
    fx = np.fft.rfftfreq(W)[None, :]
    k = np.exp(-2 * (np.pi * sigma) ** 2 * (fy * fy + fx * fx))
    out = np.fft.irfft2(np.fft.rfft2(x, axes=(0, 1)) * k[:, :, None], s=(H, W), axes=(0, 1))
    return out[pad:H - pad, pad:W - pad]


def apply_lut(img, lut):
    """lut[b, g, r] -> rgb, узлы — charts.grid(n) (сетка 8-битных значений)."""
    n = lut.shape[0]
    if n == 256:  # полная таблица: каждому 8-битному цвету — свой ответ, без интерполяции
        q = np.clip(np.round(img * 255.0), 0, 255).astype(np.intp)
        return lut[q[..., 2], q[..., 1], q[..., 0]]
    grid = charts.grid(n).astype(np.float64) / 255.0
    idx, frac = [], []
    for c in range(3):
        v = np.clip(img[..., c], 0.0, 1.0)
        i = np.clip(np.searchsorted(grid, v, side="right") - 1, 0, n - 2)
        idx.append(i)
        frac.append((v - grid[i]) / (grid[i + 1] - grid[i]))
    (r0, g0, b0), (fr, fg, fb) = idx, [f[..., None] for f in frac]
    L = lut
    c00 = L[b0, g0, r0] * (1 - fr) + L[b0, g0, r0 + 1] * fr
    c01 = L[b0, g0 + 1, r0] * (1 - fr) + L[b0, g0 + 1, r0 + 1] * fr
    c10 = L[b0 + 1, g0, r0] * (1 - fr) + L[b0 + 1, g0, r0 + 1] * fr
    c11 = L[b0 + 1, g0 + 1, r0] * (1 - fr) + L[b0 + 1, g0 + 1, r0 + 1] * fr
    return (c00 * (1 - fg) + c01 * fg) * (1 - fb) + (c10 * (1 - fg) + c11 * fg) * fb


def _scale(op, height):
    ref = op.get("ref_height")
    return height / ref if ref else 1.0


def apply_op(img, op, luts):
    t = op["type"]
    if t == "lut":
        return apply_lut(img, luts[op["lut"]])
    k = _scale(op, img.shape[0])
    if t == "linear":
        out = img.copy()
        for s, a in zip(op["sigmas"], op["gains"]):
            if a:
                out += a * (img - gauss(img, s * k))
        return np.clip(out, 0.0, 1.0)
    if t == "glow":
        thr = op["threshold"]
        src = np.clip((img - thr) / max(1e-6, 1 - thr), 0.0, 1.0)
        g = gauss(src, op["sigma"] * k) * np.asarray(op["gain"])[None, None, :]
        out = img + g if op["mode"] == "add" else 1 - (1 - img) * (1 - np.clip(g, 0, 1))
        return np.clip(out, 0.0, 1.0)
    raise ValueError(f"неизвестная операция {t}")


class Package:
    def __init__(self, folder):
        self.folder = folder
        with open(os.path.join(folder, "package.json"), encoding="utf8") as fh:
            self.meta = json.load(fh)
        self.luts = {}
        for op in self.meta["ops"]:
            if op["type"] == "lut":
                raw = np.load(os.path.join(folder, op["lut"]))["lut"]
                self.luts[op["lut"]] = raw.astype(np.float32) / (255.0 if raw.dtype == np.uint8 else 65535.0)

    def apply(self, img8):
        """кадр uint8 (H, W, 3) -> кадр uint8."""
        x = img8.astype(np.float64) / 255.0
        for op in self.meta["ops"]:
            x = apply_op(x, op, self.luts)
        return np.clip(np.round(x * 255.0), 0, 255).astype(np.uint8)


def apply_video(pkg, src, dst, crf=16):
    """видео -> видео через ffmpeg (кадры идут через движок, звук копируется)."""
    import subprocess
    info = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate",
                           "-of", "csv=p=0", src], capture_output=True, text=True).stdout.strip().split(",")
    W, H, fps = int(info[0]), int(info[1]), info[2]
    rd = subprocess.Popen(["ffmpeg", "-v", "error", "-i", src, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    wr = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", fps, "-i", "-",
                           "-i", src, "-map", "0:v", "-map", "1:a?", "-c:v", "libx264", "-crf", str(crf), "-pix_fmt", "yuv420p",
                           "-c:a", "copy", dst], stdin=subprocess.PIPE)
    n = 0
    while True:
        buf = rd.stdout.read(W * H * 3)
        if len(buf) < W * H * 3:
            break
        wr.stdin.write(pkg.apply(np.frombuffer(buf, np.uint8).reshape(H, W, 3)).tobytes())
        n += 1
    wr.stdin.close()
    wr.wait(), rd.wait()
    return n
