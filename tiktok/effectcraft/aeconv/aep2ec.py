#!/usr/bin/env python3
""".aep -> проект EffectCraft (команды для runner.js).

Переносится: композиция (размер, длительность), слои (тайминг), текст (строки, размер,
трекинг, интерлиньяж, сдвиг базовой линии, цвет, выравнивание), текстовые аниматоры
(Range/Wiggly selector, position/rotation/tracking с ключами), выражения, трансформации,
эффекты (через ae2ec: стандартные 1:1, платные — сборки из встроенных).
Футаж, которого нет в проекте (путь на чужом диске), подменяется на --footage.
"""
import argparse
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rifx import parse
from aep_extract import comps as aep_comps, TICKS
from ae2ec import convert_effects, keys_sec

FONT_MAP = {"Arial-BoldMT": ("Inter", "Bold"), "ArialMT": ("Inter", "Regular")}


def find(ps, match):
    for p in ps or []:
        if p.get("match") == match:
            return p
    return None


def ldta_times(hexs):
    b = bytes.fromhex(hexs)
    start = struct.unpack(">i", b[12:16])[0] / TICKS
    inoff = struct.unpack(">i", b[20:24])[0] / TICKS
    dur = struct.unpack(">i", b[28:32])[0] / TICKS
    return start, start + inoff, start + dur


def text_style(doc_raw):
    """размер/трекинг/интерлиньяж/сдвиг/заливка/выравнивание из CoS-документа."""
    import re
    st = {"size": 36.0, "leading": None, "tracking": 0, "baselineShift": 0, "fill": [1, 1, 1], "justify": "left"}
    i = doc_raw.find(b"/6 << /0 [ << /0 << /0 << /0 (")
    seg = doc_raw[i:i + 3000] if i >= 0 else doc_raw
    seg = re.sub(rb"\(\xfe\xff(?:\\.|[^)\\])*\)", b"(S)", seg).decode("latin1")
    m = re.search(r"/6 << /0 \d+ /1 ([\d.\-]+) /2 \w+ /3 \w+ /4 \w+ /5 ([\d.\-]+) /6 [\d.\-]+ /7 [\d.\-]+ /8 ([\d.\-]+) /9 ([\d.\-]+)", seg)
    if m:
        st.update(size=float(m.group(1)), leading=float(m.group(2)), tracking=float(m.group(3)), baselineShift=float(m.group(4)))
    m = re.search(r"/53 << /99 /SimplePaint /0 << /0 1 /1 \[ ([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+) \]", seg)
    if m:
        st["fill"] = [float(m.group(2)), float(m.group(3)), float(m.group(4))]
    para = doc_raw.decode("latin1")
    m = re.search(r"/5 << /0 (\d) /1 0\.0 /2 0\.0", para)
    if m:
        st["justify"] = {0: "left", 1: "right", 2: "center"}.get(int(m.group(1)), "left")
    return st


def text_docs(tree):
    docs = []

    def walk(ns):
        for n in ns:
            if n.get("id") == "btdkraw":
                docs.append(n["data"])
            if "children" in n:
                walk(n["children"])
    walk(tree)
    return docs


def hexcol(c):
    return "#%02x%02x%02x" % tuple(int(round(x * 255)) for x in c[:3])


def build(aep_path, footage=None, footage_src=0.0, bg=None, fps=30, roto_strokes=None):
    tree = parse(open(aep_path, "rb").read())
    comps = aep_comps(tree)
    docs = text_docs(tree)
    steps, notes = [], []
    X = lambda c, **p: steps.append({"c": c, "p": p})
    files = []
    c = comps[0]
    W, H = c["size"]
    cd = bytes.fromhex(c["cdta"])
    dur = struct.unpack(">i", cd[44:48])[0] / struct.unpack(">i", cd[48:52])[0] if len(cd) >= 52 else 10
    # длительность = максимум out слоёв
    times = [ldta_times(L["ldta"]) for L in c["layers"]]
    dur = max(t[2] for t in times)
    X("file.newProject")
    if footage:
        X("file.import", paths=[os.path.basename(footage)]); files.append(footage)
    if bg:
        X("file.import", paths=[os.path.basename(bg)]); files.append(bg)
    X("comp.new", name="EDIT", width=W, height=H, frameRate=fps, duration=round(dur, 4), background="#000000", open=True)
    if bg:
        X("layer.addItem", item=os.path.basename(bg), time=0, duration=round(dur, 4), save="bgl")
        X("layer.setTransform", layers=["$bgl"], prop="scale", value=[100 * H / 720, 100 * H / 720, 100])
    doc_i = 0
    # слои AE: первый в списке — верхний; в EffectCraft новый слой ложится сверху -> идём снизу вверх
    order = list(range(len(c["layers"])))[::-1]
    for li in order:
        L = c["layers"][li]
        start, tin, tout = times[li]
        props = L.get("props", [])
        textp = find(props, "ADBE Text Properties")
        tr = find(props, "ADBE Transform Group")
        fxp = find(props, "ADBE Effect Parade")
        if textp is not None:
            di = sum(1 for k in range(li) if find(c["layers"][k].get("props"), "ADBE Text Properties"))
            raw = docs[di] if di < len(docs) else b""
            import re
            strings = []
            for m in re.finditer(rb"\((\xfe\xff(?:\\.|[^)\\])*)\)", raw):
                s = re.sub(rb"\\(.)", rb"\1", m.group(1))[2:].decode("utf-16-be", "replace")
                strings.append((m.start(), s))
            body = next((s for pos, s in strings if pos > 3900 and "\r" in s), L["name"])
            fonts = [s for pos, s in strings if pos < 700 and s and s[0].isalpha() and "-" in s]
            fam, sty = FONT_MAP.get(fonts[0] if fonts else "", ("Inter", "Bold"))
            st = text_style(raw)
            pos = (find(tr["children"], "ADBE Position") or {}).get("value", [W / 2, H / 2])[:2] if tr else [W / 2, H / 2]
            X("layer.newText", text=body.rstrip("\r").replace("\r", "\n"), font=fam, style=sty, size=st["size"],
              fill=hexcol(st["fill"]), tracking=st["tracking"], leading=st["leading"] or "auto",
              baselineShift=st["baselineShift"], justify=st["justify"], position=[round(pos[0], 2), round(pos[1], 2)], save="cur")
            notes.append(f"{L['name']}: текст {body.strip()!r}, {fonts[0] if fonts else '?'} {st['size']}px → {fam} {sty} (Arial в EffectCraft нет)")
            anims = find(textp["children"], "ADBE Text Animators")
            added = 0
            for an in (anims or {}).get("children", []):
                aprops = find(an["children"], "ADBE Text Animator Properties")
                sels = find(an["children"], "ADBE Text Selectors")
                plist = []
                kmap = {"ADBE Text Position 3D": "position", "ADBE Text Rotation": "rotation", "ADBE Text Tracking Amount": "tracking",
                        "ADBE Text Opacity": "opacity", "ADBE Text Scale 3D": "scale", "ADBE Text Blur": "blur"}
                for p in (aprops or {}).get("children", []):
                    if p["match"] in kmap:
                        plist.append((kmap[p["match"]], p))
                if not plist:
                    continue
                X("layer.addTextAnimator", layer="$cur", properties=[k for k, _ in plist], save="anim")
                added += 1
                base = "text/animators/animator" + ("" if added == 1 else f"#{added}")
                for k, p in plist:
                    path = f"{base}/properties/{k}"
                    if p.get("keys"):
                        for kk in keys_sec(p, 0):
                            X("prop.addKey", layer="$cur", path=path, time=round(start + kk["t"], 5), timeBase="comp", value=kk["v"])
                    elif p.get("value"):
                        v = p["value"]
                        X("prop.set", layer="$cur", path=path, value=[v[0], v[1], v[2]] if k == "position" else v[0])
                for s in (sels or {}).get("children", []):
                    if s["match"] == "ADBE Text Wiggly Selector":
                        X("layer.addTextSelector", layer="$cur", animator="$anim", kind="wiggly")
                        notes.append(f"{L['name']}: Wiggly Selector перенесён (символы дрожат по position/rotation)")
            notes.append(f"{L['name']}: аниматоры {[a.get('name') for a in (anims or {}).get('children', [])]}")
        else:
            if not footage:
                notes.append(f"{L['name']}: футаж отсутствует в проекте — слой пропущен"); continue
            X("layer.addItem", item=os.path.basename(footage), time=round(start, 4), save="cur")
            X("layer.enableTimeRemap", layers=["$cur"], value=True)
            X("prop.addKey", layer="$cur", path="timeRemap", time=round(start, 4), timeBase="comp", value=footage_src)
            X("prop.addKey", layer="$cur", path="timeRemap", time=round(tout, 4), timeBase="comp", value=footage_src + (tout - start))
            X("layer.setTransform", layers=["$cur"], prop="scale", value=[100 * H / 720, 100 * H / 720, 100])
            notes.append(f"{L['name']}: исходный клип не входит в .aep → подставлен {os.path.basename(footage)} с {footage_src}s")
        X("layer.rename", layer="$cur", name=L["name"])
        X("layer.timing", layers=["$cur"], start=round(start, 4), **{"in": round(tin, 4)}, out=round(tout, 4))
        if tr:
            for p in tr["children"]:
                if p.get("expr") and p["match"] == "ADBE Rotate Z":
                    X("prop.setExpression", layer="$cur", path="transform/rotation", expression=p["expr"])
                    notes.append(f"{L['name']}: выражение rotation = {p['expr']}")
        if fxp and fxp.get("children"):
            effs = [e for e in fxp["children"] if e.get("type") == "effect"]
            origin = None
            if textp is not None and tr:
                origin = ((find(tr["children"], "ADBE Position") or {}).get("value") or [W / 2, H / 2])[:2]
            o = convert_effects(effs, layer="$cur", t0=start, comp=(W, H), t_base=0, layer_start=start, origin=origin, roto_strokes=roto_strokes)
            steps.extend(o.steps)
            notes += [f"{L['name']}: {n}" for n in o.notes]
    return {"files": files, "steps": steps, "notes": notes, "comp": {"w": W, "h": H, "dur": dur}}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("aep"); ap.add_argument("out")
    ap.add_argument("--footage"); ap.add_argument("--footage-src", type=float, default=0.0); ap.add_argument("--bg")
    ap.add_argument("--roto", help="JSON: [[kind, [[x,y],...], radius], ...] в пикселях футажа")
    a = ap.parse_args()
    rs = json.load(open(a.roto)) if a.roto else None
    r = build(a.aep, a.footage, a.footage_src, a.bg, roto_strokes=rs)
    json.dump(r, open(a.out, "w"), ensure_ascii=False, indent=1)
    print(len(r["steps"]), "steps", r["comp"]); print("\n".join("  " + n for n in r["notes"]))
