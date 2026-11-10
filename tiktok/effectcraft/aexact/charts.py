"""Калибровочные картинки: по ним ЦК «снимается» с хоста (After Effects или EffectCraft).

  hald.png           все 16,7 млн цветов (256×256×256) — даёт точную таблицу цвета (3D LUT)
  hald_shuffled.png  те же цвета, перемешанные по местам — показывает, зависит ли эффект от соседних пикселей
  fit.png            фото для подбора пространственных эффектов (резкость, свечение)
  test.png           другое фото, в подборе не участвует — на нём измеряется итоговое совпадение
"""
import os

import numpy as np
from PIL import Image

N = 256                     # узлов по каждой оси: 256 = все 16,7 млн цветов, картинка 4096×4096
SIZES = {64: (512, 512), 128: (1024, 2048), 256: (4096, 4096)}     # n -> (высота, ширина); 256 = все цвета 8 бит
SEED = 20261110


def grid(n):
    """значения узлов в 8 битах"""
    return np.round(np.arange(n) * 255.0 / (n - 1)).astype(np.uint8)


def size_to_n(pixels):
    return {h * w: n for n, (h, w) in SIZES.items()}[pixels]


def hald(n=N):
    i = np.arange(n ** 3)
    g = grid(n)
    return np.stack([g[i % n], g[(i // n) % n], g[i // (n * n)]], axis=1).reshape(*SIZES[n], 3)


def permutation(n=N):
    return np.random.default_rng(SEED).permutation(n ** 3)


def hald_shuffled(n=N):
    return hald(n).reshape(-1, 3)[permutation(n)].reshape(*SIZES[n], 3)


def write(folder, fit_photo, test_photo, n=N):
    os.makedirs(folder, exist_ok=True)
    Image.fromarray(hald(n)).save(os.path.join(folder, "hald.png"))
    Image.fromarray(hald_shuffled(n)).save(os.path.join(folder, "hald_shuffled.png"))
    for name, src in (("fit", fit_photo), ("test", test_photo)):
        im = Image.open(src).convert("RGB")
        im = im.crop((0, 0, im.width - im.width % 2, im.height - im.height % 2))
        im.save(os.path.join(folder, name + ".png"))
    return ["hald", "hald_shuffled", "fit", "test"]
