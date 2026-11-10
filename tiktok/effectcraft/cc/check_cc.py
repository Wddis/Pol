#!/usr/bin/env python3
"""Проверка: ЦК на сайте выглядят ровно как на эталоне (knowledge/cc_presets/reference/ETALON.jpg).

    python3 cc/check_cc.py                 # все 5 ЦК на кадре Скофилда (кадр эталона)
    python3 cc/check_cc.py raspberry_haze  # один ЦК
    python3 cc/check_cc.py --fury          # на втором эталонном кадре

Рендерит кадр через apply_cc (замороженные ЦК) и сравнивает с эталонным рендером пиксель в пиксель.
Норма — отличие 0 (рендер EffectCraft детерминирован, проверено). Любое отличие = ЦК применён не так:
изменён static/, другой размер композиции, ЦК на другом слое, что-то ещё на слое. Код возврата 1.
"""
import os
import sys
import tempfile

from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from preview_cc import ORDER, render  # noqa: E402

REF = os.path.join(HERE, "..", "..", "knowledge", "cc_presets", "reference")


def main(argv):
    frame = "fury" if "--fury" in argv else "scofield"
    keys = [a for a in argv if not a.startswith("--")] or [k for k, _ in ORDER]
    work = tempfile.mkdtemp(prefix="check_cc_")
    img = os.path.join(work, "frame.png")
    Image.open(os.path.join(REF, frame + "_frame.jpg")).convert("RGB").save(img)
    W, H = Image.open(img).size
    ok = True
    for k in keys:
        got = Image.open(render(k, img, W, H, work)).convert("RGB")
        ref = Image.open(os.path.join(REF, "stills", f"{frame}__{k}.png")).convert("RGB")
        d = max(m for _, m in ImageChops.difference(got, ref).getextrema()) if got.size == ref.size else 255
        print(f"{k:15s} {'OK — совпадает с эталоном' if d == 0 else f'ОТЛИЧАЕТСЯ (макс. {d} уровней из 255)'}")
        ok &= d == 0
    return ok


if __name__ == "__main__":
    sys.exit(0 if main(sys.argv[1:]) else 1)
