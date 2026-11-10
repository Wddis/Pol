#!/usr/bin/env python3
""".ffx (пресет AE) -> EffectCraft. Тонкая обёртка над aefx:  python3 aeconv/ffx2ec.py preset.ffx out/ [--strict]"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from aefx.__main__ import main  # noqa: E402

if __name__ == "__main__":
    main(["convert"] + sys.argv[1:])
