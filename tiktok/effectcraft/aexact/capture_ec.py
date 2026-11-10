"""Съёмка ЦК с EffectCraft (хост-заменитель After Effects): тот же формат папки, что даёт AEXACT_Capture.jsx.

Для каждой калибровочной картинки открывается сайт, на слой ставятся эффекты (steps), рендерится весь стек
и каждый эффект по отдельности.
"""
import json
import os
import subprocess
from concurrent.futures import ThreadPoolExecutor

from PIL import Image

from . import charts

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL = r"""
const L = saved.cur;
const tree = await ec.execute('layer.tree', { layer: L, depth: 2 });
const fxs = tree.properties.children.find((g) => g.match === 'effects').children
  .map((e, i) => ({ index: i + 1, name: e.name, match: e.effect || e.match }));
const real = fxs.filter((e) => !String(e.match).startsWith('ec.control.'));
const shot = async (tag) => { const f = await ec.renderFrame({ comp: 'EDIT', time: 0.5 }); await window.saveOutput(`${cfg.chart}__${tag}.png`, f.png); };
await shot('full');
if (cfg.solo) {
  for (const e of real) {
    for (const o of real) await ec.execute('effect.toggle', { layer: L, effect: o.index, value: o.index === e.index });
    await shot('fx' + String(e.index).padStart(2, '0'));
  }
  for (const o of real) await ec.execute('effect.toggle', { layer: L, effect: o.index, value: true });
}
outputs['effects.json'] = b64s(JSON.stringify(real));
return real.length;
"""


def _one(chart, folder, steps, work, solo=True):
    img = os.path.join(folder, "charts", chart + ".png")
    W, H = Image.open(img).size
    d = os.path.join(work, chart)
    os.makedirs(d, exist_ok=True)
    name = chart + ".png"
    head = [{"c": "file.newProject", "p": {}}, {"c": "file.projectSettings", "p": {"bitDepth": 8}},
            {"c": "file.import", "p": {"paths": [name]}},
            {"c": "comp.new", "p": {"name": "EDIT", "width": W, "height": H, "frameRate": 30, "duration": 1, "background": "#000000", "open": True}},
            {"c": "layer.addItem", "p": {"item": name, "time": 0, "duration": 1, "save": "cur"}}]
    json.dump({"steps": head + steps}, open(os.path.join(d, "steps.json"), "w"))
    open(os.path.join(d, "eval.js"), "w").write(EVAL)
    json.dump({"files": [img], "steps": os.path.join(d, "steps.json"), "outDir": folder, "evalFile": os.path.join(d, "eval.js"),
               "chart": chart, "solo": solo}, open(os.path.join(d, "cfg.json"), "w"))
    env = dict(os.environ)
    env.setdefault("NODE_PATH", subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip())
    r = subprocess.run(["node", os.path.join(HERE, "..", "driver.js"), os.path.join(d, "cfg.json")], capture_output=True, text=True, env=env, timeout=1800)
    if '["errors",0,' not in r.stdout or "ERR" in r.stdout:
        raise RuntimeError(f"{chart}: {(r.stdout + r.stderr)[-500:]}")
    return chart


def capture(look, steps, folder, fit_photo, test_photo, work, n=charts.N, solo=True):
    os.makedirs(folder, exist_ok=True)
    names = charts.write(os.path.join(folder, "charts"), fit_photo, test_photo, n)
    with ThreadPoolExecutor(2) as ex:           # не больше двух браузеров сразу
        list(ex.map(lambda c: _one(c, folder, steps, work, solo), names))
    effects = json.load(open(os.path.join(folder, "effects.json"), encoding="utf8"))
    json.dump({"look": look, "host": "EffectCraft 0.9.2", "charts": names, "effects": effects},
              open(os.path.join(folder, "manifest.json"), "w", encoding="utf8"), ensure_ascii=False, indent=1)
    return effects
