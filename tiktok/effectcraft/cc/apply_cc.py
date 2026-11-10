#!/usr/bin/env python3
"""ЕДИНСТВЕННЫЙ способ применить ЦК: замороженные команды из static/.

    from apply_cc import cc
    X("layer.newAdjustment", name="GRADE", save="grade")
    steps += cc("night", layer="$grade")            # всегда один и тот же результат

Имя — любое из ALIASES («найт», «night», «тёмный»…). Ничего не пересчитывается и не подбирается:
файл static/<имя>.json читается как есть, меняются только слой и номера эффектов (fx_start —
сколько эффектов уже стоит на слое). Размер кадра учитывают сами эффекты (формулы от высоты композиции).

CLI: python3 apply_cc.py <имя> [fx_start]  -> {"steps": [...]} в stdout (слой "$cur")
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.join(HERE, "static")
ALIASES = {
    "bright": ["bright", "bright cc", "брайт", "светлый", "яркий"],
    "dusky": ["dusky", "dusky cc", "даски", "сумеречный", "сумерки"],
    "night": ["night", "night cc", "найт", "тёмный", "темный", "ночной"],
    "raspberry_haze": ["raspberry_haze", "raspberry haze", "raspberry haze cc", "raspberry", "малиновый", "малина", "хейз", "распберри"],
    "two_sharp": ["two_sharp", "two sharp", "two sharp cc", "ту шарп", "резкий", "шарп"],
}


def resolve(name):
    n = name.strip().lower().replace("ё", "е")
    for key, al in ALIASES.items():
        if n in [a.replace("ё", "е") for a in al]:
            return key
    raise KeyError(f"нет ЦК «{name}». Есть: {', '.join(ALIASES)}")


def cc(name, layer="$cur", fx_start=0):
    """команды EffectCraft для ЦК `name` на слое `layer`."""
    key = resolve(name)
    text = json.dumps(json.load(open(os.path.join(STATIC, key + ".json"), encoding="utf8"))["steps"], ensure_ascii=False)
    text = re.sub(r"effects/#(\d+)", lambda m: f"effects/#{int(m.group(1)) + fx_start}", text)
    text = re.sub(r'(\$?)aefx(\d+)"', lambda m: f'{m.group(1)}cc_{key}_{m.group(2)}"', text)
    steps = json.loads(text)
    for s in steps:
        p = s["p"]
        if p.get("layer") == "$cur":
            p["layer"] = layer
        if p.get("layers") == ["$cur"]:
            p["layers"] = [layer]
        for k in p.get("keys") or []:
            if k.get("layer") == "$cur":
                k["layer"] = layer
        if s["c"] == "effect.toggle":
            p["effect"] += fx_start
    return steps


if __name__ == "__main__":
    print(json.dumps({"steps": cc(sys.argv[1], fx_start=int(sys.argv[2]) if len(sys.argv) > 2 else 0)}, ensure_ascii=False, indent=1))
