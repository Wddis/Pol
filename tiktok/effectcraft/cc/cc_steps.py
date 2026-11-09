#!/usr/bin/env python3
"""Готовые ЦК из урока -> команды EffectCraft для adjustment-слоя (или любого слоя).

    from cc_steps import cc_steps
    steps += cc_steps("night", layer="$grade", fx_start=1, comp_h=1080)

Пресеты (cc_presets.json, подобраны fit_native.py по кадру сравнения из урока):
    bright | dusky | night | raspberry_haze | two_sharp
Цепочка (повторяет логику урока «шарпы -> цвет»):
    Unsharp Mask (шарпы: S_Sharpen + BCC Unsharp) -> Channel Mixer (насыщенность) -> Levels (R,G,B)
Параметр strength (0..1) смешивает пресет с оригиналом (аналог Strength в Magic Bullet Looks).

CLI: python3 cc_steps.py <preset> [strength] -> steps.json в stdout (layer "$cur", эффекты с #1)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PRESETS = json.load(open(os.path.join(HERE, "cc_presets.json")))
CH = {"red": "red", "green": "green", "blue": "blue"}


def _mix(v, ident, k):
    return ident + (v - ident) * k


def cc_steps(name, layer="$cur", fx_start=1, comp_h=1080, strength=1.0, sharpen=True):
    p = PRESETS[name]
    st = []
    X = lambda c, **kw: st.append({"c": c, "p": kw})
    fx = fx_start
    if sharpen and p["unsharp"]["amount"] > 0:
        X("effect.apply", effect="Unsharp Mask", layers=[layer])
        X("prop.set", layer=layer, path=f"effects/#{fx}/amount", value=round(p["unsharp"]["amount"] * strength))
        X("prop.set", layer=layer, path=f"effects/#{fx}/radius", value=round(p["unsharp"]["radius_at_1080"] * comp_h / 1080, 1))
        fx += 1
    ident = {"rr": 100, "gg": 100, "bb": 100}
    X("effect.apply", effect="Channel Mixer", layers=[layer])
    for k, v in p["channel_mixer"].items():
        X("prop.set", layer=layer, path=f"effects/#{fx}/{k}", value=int(round(_mix(v, ident.get(k, 0), strength))))
    fx += 1
    X("effect.apply", effect="Levels", layers=[layer])
    idl = {"inBlack": 0, "inWhite": 1, "gamma": 1, "outBlack": 0, "outWhite": 1}
    for ch, lv in p["levels"].items():
        for k, v in lv.items():
            X("prop.set", layer=layer, path=f"effects/#{fx}/{ch}{k[0].upper()}{k[1:]}",
              value=round(_mix(v, idl[k], strength), 3))
    fx += 1
    return st


if __name__ == "__main__":
    s = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
    print(json.dumps({"steps": cc_steps(sys.argv[1], strength=s)}, ensure_ascii=False, indent=1))
