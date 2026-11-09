#!/usr/bin/env python3
"""Декодер Magic Bullet Looks (MB LookSuite3) из .ffx/.aep.

Looks хранит «look» не в обычных параметрах AE, а в бинарном блоке `MBL2` (arbitrary-параметр aRbp):
  'MBL2' u32(version) затем чанки  <tag:4><len:u32 BE><body>
    flag  — флаги
    imag  — миниатюра цепочки (w,h,rowbytes,0 + RGBA)
    #tls  — число инструментов
    tool  — инструмент: <id:4> u32(0) u32(n) + n×double BE  (параметры)
    name, strn (Strength 0..1), ui*, inpt/outp/OCIO — служебное
Расшифровка значений параметров — по наблюдению (Red Giant формат не документирован).

Использование:  python3 mblooks.py preset.ffx [...]   -> JSON в stdout
"""
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rifx import parse  # noqa: E402

# id инструмента -> название в Looks (по набору Tools в Looks 2/3)
TOOLS = {
    "duto": "Duotone (двухцветное тонирование тени/света)",
    "mjo2": "Mojo II (teal & orange: тёплая кожа, холодный фон)",
    "husa": "Hue/Saturation (сдвиг тона, насыщенность)",
    "csta": "Colorista (3-way: lift/gamma/gain + кривые)",
    "shol": "Shoulder (мягкий спад светов)",
    "haze": "Haze (дымка/подъём чёрных с цветом)",
    "lenv": "Lens Vignette (виньетка)",
    "crv2": "Curves",
    "expo": "Exposure",
    "cont": "Contrast",
    "chab": "Chromatic Aberration",
    "2stp": "2-Strip Process (двухплёночный цвет)",
    "cmix": "Channel Mixer",
    "odif": "Optical Diffusion",
    "diff": "Diffusion",
    "shad": "Shadows/Highlights",
    "fltr": "Warm/Cool Filter (цветной фильтр)",
    "logc": "Log/Cosmo (контраст в логе)",
    "grad": "Gradient (градиентный фильтр)",
    "grex": "Gradient Exposure",
    "spex": "Spot Exposure",
}


def _blobs(nodes):
    for n in nodes:
        if "children" in n:
            yield from _blobs(n["children"])
        elif (n.get("data") or b"")[:4] == b"MBL2":
            yield n["data"]


def decode_blob(b):
    out = {"tools": [], "strength": None}
    i = 8
    while i < len(b) - 8:
        tag = b[i:i + 4]
        ln = struct.unpack(">I", b[i + 4:i + 8])[0]
        body = b[i + 8:i + 8 + ln]
        if tag == b"tool":
            tid = body[:4].decode("latin1")
            n = struct.unpack(">I", body[8:12])[0]
            vals = [struct.unpack(">d", body[12 + 8 * k:20 + 8 * k])[0] for k in range(n)]
            out["tools"].append({"id": tid, "name": TOOLS.get(tid, "?"), "values": [round(v, 5) for v in vals]})
        elif tag == b"strn":
            out["strength"] = round(struct.unpack(">d", body)[0], 4)
        elif tag == b"imag":
            w, h = struct.unpack(">2I", body[:8])
            out["thumb"] = [w, h]
        i += 8 + ln
    return out


def looks_in(path):
    return [decode_blob(b) for b in _blobs(parse(open(path, "rb").read()))]


if __name__ == "__main__":
    res = {os.path.basename(p): looks_in(p) for p in sys.argv[1:]}
    print(json.dumps(res, ensure_ascii=False, indent=1))
