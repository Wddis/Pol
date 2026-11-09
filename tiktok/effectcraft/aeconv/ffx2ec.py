#!/usr/bin/env python3
""".ffx (пресет AE) -> пакет для EffectCraft:
  <name>.steps.json — команды (ставят эффекты + значения + ключи) для runner.js
  <name>.ecpreset   — нативный пресет EffectCraft (значения/ключи; применять после того, как эффекты стоят на слое)
  <name>.report.txt — что перенесено 1:1, что собрано из встроенных эффектов
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rifx import parse
from extract_ffx import effects as ffx_effects
from ae2ec import convert_effects

TPS = 254016000000  # тики времени в .ecpreset


def to_ecpreset(name, steps):
    """prop.set / prop.addKey / keys.setEase -> формат effectcraftAnimationPreset."""
    fx_ids = {}
    n = 0
    props = {}
    for st in steps:
        c, p = st["c"], st["p"]
        if c == "effect.apply":
            n += 1; fx_ids[f"effects/#{n}"] = p["effect"]
        if c in ("prop.set", "prop.addKey"):
            path = p["path"]
            pr = props.setdefault(path, {"expression": None, "keys": [], "path": path, "value": None})
            v = p["value"]
            tv = {"t": "Scalar", "v": v} if isinstance(v, (int, float)) and not isinstance(v, bool) else {"t": "Raw", "v": v}
            if c == "prop.set":
                pr["value"] = tv
            else:
                pr["keys"].append({"auto_bezier": False, "continuous": False, "in_ease": [], "in_interp": "Linear",
                                   "out_ease": [], "out_interp": "Linear", "roving": False, "spatial_auto": True,
                                   "spatial_continuous": False, "spatial_in": [0, 0, 0], "spatial_out": [0, 0, 0],
                                   "time": round(p["time"] * TPS), "value": tv})
        if c == "keys.setEase":
            pr = props[p["path"]]
            for k in pr["keys"]:
                if abs(k["time"] - p["time"] * TPS) < TPS / 1000:
                    k[f"{p['side']}_interp"] = "Bezier"
                    k[f"{p['side']}_ease"] = [{"influence": p["influence"] / 100, "speed": p["speed"]}]
        if c == "prop.setExpression":
            props.setdefault(p["path"], {"keys": [], "path": p["path"], "value": None})["expression"] = p["expression"]
    return {"effectcraftAnimationPreset": 1, "name": name, "effects": [], "requires": list(fx_ids.values()),
            "properties": list(props.values())}


if __name__ == "__main__":
    src, outdir = sys.argv[1], sys.argv[2]
    name = os.path.splitext(os.path.basename(src))[0]
    eff = ffx_effects(parse(open(src, "rb").read()))
    o = convert_effects(eff, layer="$cur", t0=0.0)
    os.makedirs(outdir, exist_ok=True)
    json.dump({"source": os.path.basename(src), "notes": o.notes, "steps": o.steps},
              open(os.path.join(outdir, name + ".steps.json"), "w"), ensure_ascii=False, indent=1)
    json.dump(to_ecpreset(name, o.steps), open(os.path.join(outdir, name + ".ecpreset"), "w"), ensure_ascii=False, indent=1)
    open(os.path.join(outdir, name + ".report.txt"), "w").write("\n".join(o.notes) + "\n")
    print(name, len(o.steps), "steps"); print("\n".join("  " + n for n in o.notes))
