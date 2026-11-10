#!/usr/bin/env python3
"""Сетка «оригинал + 5 ЦК» на любом кадре — рендер в EffectCraft из замороженных ЦК (static/).

    python3 preview_cc.py <image> <out.jpg> [workdir]
"""
import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from apply_cc import cc  # noqa: E402

ORDER = [("bright", "BRIGHT CC"), ("dusky", "DUSKY CC"), ("night", "NIGHT CC"),
         ("raspberry_haze", "RASPBERRY HAZE CC"), ("two_sharp", "TWO SHARP CC")]


def render(key, img, W, H, work):
    d = os.path.join(work, key)
    os.makedirs(d, exist_ok=True)
    name = os.path.basename(img)
    steps = [{"c": "file.newProject", "p": {}}, {"c": "file.import", "p": {"paths": [name]}},
             {"c": "comp.new", "p": {"name": "EDIT", "width": W, "height": H, "frameRate": 30, "duration": 2, "background": "#000000", "open": True}},
             {"c": "layer.addItem", "p": {"item": name, "time": 0, "duration": 2, "save": "cur"}}] + cc(key)
    json.dump({"steps": steps}, open(os.path.join(d, "steps.json"), "w"))
    json.dump({"files": [img], "steps": os.path.join(d, "steps.json"), "outDir": d, "stills": [1]}, open(os.path.join(d, "cfg.json"), "w"))
    env = dict(os.environ)
    env.setdefault("NODE_PATH", subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip())
    r = subprocess.run(["node", os.path.join(HERE, "..", "driver.js"), os.path.join(d, "cfg.json")], capture_output=True, text=True, env=env, timeout=900)
    out = os.path.join(d, "still_1.png")
    if not os.path.exists(out) or '["errors",0,' not in r.stdout:
        raise RuntimeError(f"{key}: {(r.stdout + r.stderr)[-400:]}")
    return out


def main(src, out, work=None):
    work = os.path.abspath(work or tempfile.mkdtemp(prefix="cc_preview_"))
    os.makedirs(work, exist_ok=True)
    img = os.path.join(work, "frame.png")
    Image.open(src).convert("RGB").save(img)
    W, H = Image.open(img).size
    with ThreadPoolExecutor(2) as ex:  # не больше 2 браузеров (каждый ~5 ГБ)
        stills = list(ex.map(lambda k: render(k[0], img, W, H, work), ORDER))
    items = [("ОРИГИНАЛ", img)] + [(lab, s) for (_, lab), s in zip(ORDER, stills)]
    cols = 3 if H >= W else 2
    rows = (len(items) + cols - 1) // cols
    pad, bar = 12, 44
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 24)
    c = Image.new("RGB", (cols * W + (cols + 1) * pad, rows * (H + bar) + (rows + 1) * pad), (18, 18, 18))
    dr = ImageDraw.Draw(c)
    for i, (t, f) in enumerate(items):
        x, y = pad + (i % cols) * (W + pad), pad + (i // cols) * (H + bar + pad)
        c.paste(Image.open(f).convert("RGB").resize((W, H)), (x, y))
        dr.text((x + W / 2, y + H + bar / 2), t, font=font, fill=(235, 235, 235), anchor="mm")
    c.save(out, quality=90)
    print(out, c.size)


if __name__ == "__main__":
    main(*sys.argv[1:4])
