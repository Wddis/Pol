"""aefx — перенос эффектов After Effects (.ffx / .aep) в EffectCraft без молчаливых потерь.

  python3 -m aefx inspect  preset.ffx                 что в файле (эффекты, параметры, анимация)
  python3 -m aefx convert  preset.ffx out/ [--strict] команды для runner.js + отчёт + ожидания + IR
  python3 -m aefx verify   out/x.steps.json aefx_state.json    сверка: дошло ли до сайта всё, что задумано

Запуск из tiktok/effectcraft (или python3 tiktok/effectcraft/aefx_cli.py …).
Код возврата convert --strict: 0 — без потерь, 2 — есть потери (LOSS / CLAMPED / STORED).
"""
import argparse
import json
import os
import sys

from . import ir as IR
from .convert import convert


def wh(s):
    w, h = s.lower().split("x")
    return int(w), int(h)


def demo_wrap(steps, comp, fps, duration, footage=None):
    """оборачивает команды эффектов в самодостаточный проект: композиция EDIT + слой (футаж или заливка)."""
    head = [{"c": "file.newProject", "p": {}}]
    if footage:
        head.append({"c": "file.import", "p": {"paths": [os.path.basename(footage)]}})
    head.append({"c": "comp.new", "p": {"name": "EDIT", "width": comp[0], "height": comp[1], "frameRate": fps,
                                        "duration": duration, "background": "#000000", "open": True}})
    if footage:
        head.append({"c": "layer.addItem", "p": {"item": os.path.basename(footage), "time": 0, "duration": duration, "save": "cur"}})
    else:
        head.append({"c": "layer.newSolid", "p": {"name": "AEFX", "color": "#808080", "save": "cur"}})
    return head + steps


def cmd_inspect(a):
    ir = IR.load(a.file)
    print(IR.summary(ir))
    if a.json:
        json.dump(ir, open(a.json, "w", encoding="utf8"), ensure_ascii=False, indent=1)


def cmd_convert(a):
    ir = IR.load(a.file)
    c = convert(ir, layer=a.layer, t0=a.t0, comp=a.comp, fps=a.fps, layer_size=a.layer_size, fx_start=a.fx_start)
    name = os.path.splitext(os.path.basename(a.file))[0]
    os.makedirs(a.out, exist_ok=True)
    steps = demo_wrap(c.steps, a.comp, a.fps, a.duration, a.footage) if (a.demo or a.footage) else c.steps
    base = os.path.join(a.out, name)
    json.dump({"source": ir["source"], "steps": steps, "expect": c.expect, "ledger": c.ledger.to_json()},
              open(base + ".steps.json", "w", encoding="utf8"), ensure_ascii=False, indent=1)
    json.dump(ir, open(base + ".ir.json", "w", encoding="utf8"), ensure_ascii=False)
    rep = c.ledger.report(verbose=a.verbose)
    open(base + ".report.txt", "w", encoding="utf8").write(c.ledger.report(verbose=True) + "\n")
    print(rep)
    print(f"\n{len(steps)} команд → {base}.steps.json  (отчёт: .report.txt, исходные данные без потерь: .ir.json)")
    if a.strict and not c.ledger.lossless():
        sys.exit(2)


def cmd_verify(a):
    from .verify import verify
    plan = json.load(open(a.steps, encoding="utf8"))
    state = json.load(open(a.state, encoding="utf8"))
    res = verify(plan["expect"], state)
    print(res["text"])
    sys.exit(0 if res["ok"] else 1)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="aefx", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("inspect")
    p.add_argument("file"), p.add_argument("--json", help="сохранить полный IR")
    p.set_defaults(f=cmd_inspect)
    p = sub.add_parser("convert")
    p.add_argument("file"), p.add_argument("out")
    p.add_argument("--layer", default="$cur", help="слой-приёмник (по умолчанию $cur — сохранённый в steps)")
    p.add_argument("--t0", type=float, default=0.0, help="время композиции, куда встаёт первый ключ пресета")
    p.add_argument("--comp", type=wh, default=(1080, 1080)), p.add_argument("--fps", type=float, default=30.0)
    p.add_argument("--layer-size", type=wh, help="размер слоя; без него точки пересчитываются на сайте по факту")
    p.add_argument("--fx-start", type=int, default=0, help="сколько эффектов уже стоит на слое")
    p.add_argument("--demo", action="store_true", help="добавить создание проекта/композиции/слоя (для проверки)")
    p.add_argument("--footage", help="как --demo, но слой — этот файл (его же указать в files конфига driver.js)")
    p.add_argument("--duration", type=float, default=6.0)
    p.add_argument("--strict", action="store_true", help="код возврата 2, если хоть что-то не перенесено")
    p.add_argument("--verbose", action="store_true", help="показать все параметры, а не только потери")
    p.set_defaults(f=cmd_convert)
    p = sub.add_parser("verify")
    p.add_argument("steps"), p.add_argument("state")
    p.set_defaults(f=cmd_verify)
    a = ap.parse_args(argv)
    a.f(a)


if __name__ == "__main__":
    main()
