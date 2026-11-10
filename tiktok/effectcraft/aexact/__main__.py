"""aexact — перенос ЦК «по эталону»: снять с хоста → точный пакет → свой движок → измеренное совпадение.

  python3 -m aexact kit <папка> [fit.jpg test.jpg]         собрать набор для After Effects (картинки + скрипт)
  python3 -m aexact capture-ec <имя ЦК | steps.json> <папка>   снять ЦК с EffectCraft (проверка системы)
  python3 -m aexact compile <папка съёмки> <папка пакета>     съёмка -> пакет + отчёт о совпадении
  python3 -m aexact apply <папка пакета> <вход> <выход>       применить к картинке или видео
Запуск из tiktok/effectcraft.
"""
import json
import os
import shutil
import sys
import tempfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(HERE, "..", "..", "knowledge", "cc_presets", "reference")
FIT, TEST = os.path.join(REF, "fury_frame.jpg"), os.path.join(REF, "scofield_frame.jpg")


def main(argv):
    from . import charts, compile as C, engine
    cmd = argv[0]
    if cmd == "kit":
        out = argv[1]
        fit, test = (argv[2], argv[3]) if len(argv) > 3 else (FIT, TEST)
        charts.write(os.path.join(out, "charts"), fit, test)
        shutil.copy(os.path.join(HERE, "AEXACT_Capture.jsx"), out)
        os.makedirs(os.path.join(out, "presets"), exist_ok=True)
        print(f"набор готов: {out}  (положите .ffx в presets/, в After Effects: File ▸ Scripts ▸ Run Script File ▸ AEXACT_Capture.jsx)")
    elif cmd == "capture-ec":
        from .capture_ec import capture
        src, out = argv[1], argv[2]
        if os.path.exists(src):
            steps, look = json.load(open(src, encoding="utf8"))["steps"], os.path.splitext(os.path.basename(src))[0]
        else:
            sys.path.insert(0, os.path.join(HERE, "..", "cc"))
            from apply_cc import cc, resolve
            steps, look = cc(src), resolve(src)
        n = int(argv[argv.index("--lut") + 1]) if "--lut" in argv else charts.N
        fx = capture(look, steps, out, FIT, TEST, tempfile.mkdtemp(prefix="aexact_"), n=n, solo="--no-solo" not in argv)
        print(f"снято: {look}, эффектов {len(fx)} → {out}")
    elif cmd == "compile":
        print(C.summary(C.compile_capture(argv[1], argv[2])))
    elif cmd == "apply":
        pkg = engine.Package(argv[1])
        if argv[2].lower().endswith((".mp4", ".mov", ".mkv", ".webm", ".m4v")):
            print(engine.apply_video(pkg, argv[2], argv[3]), "кадров")
        else:
            Image.fromarray(pkg.apply(np.asarray(Image.open(argv[2]).convert("RGB")))).save(argv[3])
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:] or ["help"])
