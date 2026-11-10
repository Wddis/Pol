#!/usr/bin/env python3
"""Заморозка ЦК: .ffx -> static/<имя>.json (один раз; дальше ЦК применяется только из этих файлов).

    python3 freeze.py            # пересоздать static/ из knowledge/cc_presets/ffx  (осознанное действие!)
    python3 freeze.py --check    # убедиться, что static/ не менялся (сверка sha256 с MANIFEST.json)

Замороженный файл — готовый список команд EffectCraft, полученный переносом aefx. Он не зависит от
сессии, версии конвертера и настроек: «применить night» всегда даёт один и тот же набор эффектов.
Перезапускать freeze.py нужно только если ЦК меняют намеренно; тогда обновить и эталонные картинки.
"""
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
FFX = os.path.join(HERE, "..", "..", "knowledge", "cc_presets", "ffx")
STATIC = os.path.join(HERE, "static")
PRESETS = {"bright": "Bright CC.ffx", "dusky": "Dusky CC.ffx", "night": "Night CC.ffx",
           "raspberry_haze": "Raspberry haze CC.ffx", "two_sharp": "Two sharp CC.ffx"}


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def freeze():
    from aefx import ir as IR
    from aefx.convert import convert
    os.makedirs(STATIC, exist_ok=True)
    man = {}
    for key, fname in PRESETS.items():
        src = os.path.join(FFX, fname)
        c = convert(IR.load(src))
        effects = [s["p"]["effect"] for s in c.steps if s["c"] == "effect.apply"]
        out = os.path.join(STATIC, key + ".json")
        json.dump({"cc": key, "source": fname, "source_sha256": sha(src), "effects": len(effects),
                   "ledger": c.ledger.by_class(), "steps": c.steps, "expect": c.expect},
                  open(out, "w", encoding="utf8"), ensure_ascii=False, indent=1, sort_keys=True)
        man[key] = {"file": key + ".json", "sha256": sha(out), "source": fname, "effects": len(effects)}
        print(f"{key}: {len(c.steps)} команд, {len(effects)} эффектов")
    json.dump(man, open(os.path.join(STATIC, "MANIFEST.json"), "w", encoding="utf8"), ensure_ascii=False, indent=1, sort_keys=True)


def check():
    man = json.load(open(os.path.join(STATIC, "MANIFEST.json"), encoding="utf8"))
    bad = [k for k, m in man.items() if sha(os.path.join(STATIC, m["file"])) != m["sha256"]]
    print("static/ не менялся" if not bad else f"ИЗМЕНЕНЫ: {bad}")
    return not bad


if __name__ == "__main__":
    if "--check" in sys.argv:
        sys.exit(0 if check() else 1)
    freeze()
