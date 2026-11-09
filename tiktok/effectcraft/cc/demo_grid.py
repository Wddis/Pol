#!/usr/bin/env python3
"""Демо-сетка 3×2: оригинал + 5 ЦК на любом кадре, рендер в EffectCraft (как в эдите).

    python3 demo_grid.py <image> <out.jpg> [workdir]
Нужны: node + playwright (облачная среда), effectcraft/driver.js.
"""
import json
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from cc_steps import cc_steps  # noqa: E402

NAMES = ["original", "bright", "dusky", "night", "raspberry_haze", "two_sharp"]
LABELS = ["Оригинал", "Bright CC", "Dusky CC", "Night CC", "Raspberry haze CC", "Two sharp CC"]


def main(src, out, work=None):
    work = os.path.abspath(work or os.path.join(os.path.dirname(os.path.abspath(out)), "_demo_grid"))
    os.makedirs(work, exist_ok=True)
    img = os.path.join(work, "frame.png")
    Image.open(src).convert("RGB").save(img)
    W, H = Image.open(img).size
    st = [{"c": "file.newProject", "p": {}}, {"c": "file.import", "p": {"paths": ["frame.png"]}},
          {"c": "comp.new", "p": {"name": "EDIT", "width": W * 3, "height": H * 2, "frameRate": 30, "duration": 2,
                                  "background": "#000000", "open": True}}]
    for i, n in enumerate(NAMES):
        st.append({"c": "layer.addItem", "p": {"item": "frame.png", "time": 0, "save": "cur"}})
        st.append({"c": "prop.set", "p": {"layer": "$cur", "path": "transform/position",
                                          "value": [W * (i % 3) + W / 2, H * (i // 3) + H / 2, 0]}})
        if n != "original":
            st += cc_steps(n, comp_h=H)
    json.dump({"steps": st}, open(os.path.join(work, "steps.json"), "w"))
    cfg = os.path.join(work, "cfg.json")
    json.dump({"files": [img], "steps": os.path.join(work, "steps.json"), "stills": [0.5],
               "outDir": os.path.join(work, "out")}, open(cfg, "w"))
    env = dict(os.environ, NODE_PATH=subprocess.check_output(["npm", "root", "-g"], text=True).strip())
    subprocess.run(["node", os.path.join(HERE, "..", "driver.js"), cfg], check=True, env=env)
    grid = Image.open(os.path.join(work, "out", "still_0.5.png")).convert("RGB")
    d = ImageDraw.Draw(grid)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", max(18, H // 24))
    except OSError:
        f = ImageFont.load_default()
    for i, t in enumerate(LABELS):
        x, y = W * (i % 3) + 14, H * (i // 3) + 10
        d.text((x + 2, y + 2), t, font=f, fill=(0, 0, 0))
        d.text((x, y), t, font=f, fill=(255, 255, 255))
    for x in (W, 2 * W):
        d.line([(x, 0), (x, 2 * H)], fill=(0, 0, 0), width=3)
    d.line([(0, H), (3 * W, H)], fill=(0, 0, 0), width=3)
    grid.save(out, quality=92)
    print(out, grid.size)


if __name__ == "__main__":
    main(*sys.argv[1:4])
