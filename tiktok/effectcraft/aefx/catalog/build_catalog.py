#!/usr/bin/env python3
"""raw.json (harvest.js) -> ec_catalog.json: каталог всех эффектов EffectCraft для сопоставления с AE.

    python3 build_catalog.py raw.json [ec_catalog.json]
"""
import json
import os
import sys


def leaves(ps, g=()):
    for p in ps:
        if "ch" in p:
            yield from leaves(p["ch"], g + (p["n"],))
        else:
            yield p, g


def build(raw):
    script = {c["name"]: c for c in raw["script"]}
    out, problems = {}, []
    for e in raw["list"]:
        c = script.get(e["name"]) or {}
        ls = list(leaves(c.get("ps", [])))
        params, i = [], 0
        for p in e["params"]:
            # скриптовый слой добавляет к слоям псевдо-параметр «… Source» — пропускаем до совпадения имени
            while i < len(ls) and ls[i][0]["n"] != p["name"]:
                i += 1
            group = list(ls[i][1]) if i < len(ls) else None
            if group is None:
                problems.append(f"{e['name']}: {p['name']} нет в скриптовом дереве")
            i += 1
            q = {"id": p["id"], "name": p["name"], "type": p["type"], "default": p.get("default")}
            if group:
                q["group"] = group
            for k in ("min", "max"):
                if k in p:
                    q[k] = p[k]
            if "options" in p:
                q["options"] = [o["label"] for o in p["options"]]
            params.append(q)
        o = {"id": e["id"], "category": e.get("category"), "params": params}
        mn = c.get("mn", "")
        if mn and not mn.startswith("ec."):
            o["adobeMatchName"] = mn
        out[e["name"]] = o
    return out, problems


if __name__ == "__main__":
    raw = json.load(open(sys.argv[1]))
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "ec_catalog.json")
    effects, problems = build(raw)
    json.dump({"effectcraft": raw["caps"]["version"], "wasmPlugins": raw["plugins"]["wasm"], "effects": effects},
              open(dst, "w", encoding="utf8"), ensure_ascii=False, separators=(",", ":"))
    print(len(effects), "эффектов,", sum(len(e["params"]) for e in effects.values()), "параметров,",
          sum("adobeMatchName" in e for e in effects.values()), "с match name Adobe")
    for p in problems:
        print("  !", p)
