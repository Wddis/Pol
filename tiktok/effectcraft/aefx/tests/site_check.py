#!/usr/bin/env python3
"""Проверка на живом EffectCraft: применить пресет и сверить КАЖДОЕ значение/ключ/выражение с файлом AE.

    python3 aefx/tests/site_check.py [file.ffx …]      (из tiktok/effectcraft; нужен интернет и Playwright)

Без аргументов: синтетический пресет со всеми видами ключей + пресеты из knowledge/cc_presets/ffx.
Код возврата 0 — на сайте записано ровно то, что задумал конвертер, и ни одно выражение не дало ошибку.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
EC = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, EC)
sys.path.insert(0, HERE)

from aefx import ir as IR  # noqa: E402
from aefx.__main__ import demo_wrap  # noqa: E402
from aefx.convert import convert  # noqa: E402
from aefx.verify import verify  # noqa: E402


def check(path, work):
    ir = IR.load(path)
    c = convert(ir, t0=1.0, comp=(1280, 720))
    name = os.path.splitext(os.path.basename(path))[0]
    d = os.path.join(work, name)
    os.makedirs(d, exist_ok=True)
    plan = {"steps": demo_wrap(c.steps, (1280, 720), 30, 6.0), "expect": c.expect}
    json.dump(plan, open(os.path.join(d, "steps.json"), "w"))
    cfg = {"files": [], "steps": os.path.join(d, "steps.json"), "outDir": os.path.join(d, "run"), "aefxVerify": True}
    json.dump(cfg, open(os.path.join(d, "cfg.json"), "w"))
    env = dict(os.environ)
    env.setdefault("NODE_PATH", subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip())
    r = subprocess.run(["node", os.path.join(EC, "driver.js"), os.path.join(d, "cfg.json")], capture_output=True, text=True, env=env, timeout=900)
    state_path = os.path.join(d, "run", "aefx_state.json")
    if not os.path.exists(state_path):
        return name, False, f"драйвер не вернул состояние: {(r.stdout + r.stderr)[-400:]}", c
    res = verify(c.expect, json.load(open(state_path)))
    return name, res["ok"], res["text"], c


if __name__ == "__main__":
    work = tempfile.mkdtemp(prefix="aefx_site_")
    files = sys.argv[1:]
    if not files:
        import make_ffx
        sample = os.path.join(work, "synthetic_all_key_types.ffx")
        open(sample, "wb").write(make_ffx.sample())
        recipes = os.path.join(work, "synthetic_all_recipes.ffx")
        open(recipes, "wb").write(make_ffx.recipes_sample())
        files = [sample, recipes] + sorted(glob.glob(os.path.join(EC, "..", "knowledge", "cc_presets", "ffx", "*.ffx")))
    ok_all = True
    for f in files:
        name, ok, text, c = check(f, work)
        cl = c.ledger.by_class()
        print(f"== {name}: команд {len(c.steps)}, учёт ok/эмуляция/потеря = {cl['ok']}/{cl['approx']}/{cl['loss']}")
        print(text)
        ok_all &= ok
    sys.exit(0 if ok_all else 1)
