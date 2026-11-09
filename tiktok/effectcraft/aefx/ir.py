"""Слой 1. Чтение без потерь: .ffx / .aep -> IR (промежуточное представление).

Правило слоя: из файла читается ВСЁ, что относится к эффекту, и ничего не отбрасывается.
То, что не удалось разобрать, остаётся в IR сырыми байтами (hex) и помечается `unparsed`,
чтобы следующий слой сообщил о нём явно, а не потерял молча.

Что попадает в IR на каждый параметр:
  определение (pard): тип, имя, группа, default, допустимый диапазон, признак «проценты», пункты списка;
  значение (cdat): все компоненты (а не первая), слой/маска-ссылка, произвольные данные (aRbp) целиком;
  анимация (ldat): каждый ключ — время, тип интерполяции входа/выхода, hold, roving, auto/continuous
                   bezier, скорость и влияние ПО КАЖДОМУ измерению, пространственные касательные;
  выражение (Utf8).
Плюс на эффект: включён/выключен, пользовательское имя, Compositing Options (Effect Opacity, маски).

Формат чанков — по публичному описанию AEP (lottie-docs, «Parsing AEP Files») и по разбору
реальных пресетов из knowledge/cc_presets/ffx.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "aeconv"))
from rifx import parse  # noqa: E402  (контейнер RIFX общий со старым конвертером)

GROUP_END = "ADBE Group End"
BUILTIN = "ADBE Effect Built In Params"

# pard.type -> вид параметра (PF_ParamType из AE SDK)
PTYPES = {0: "layer", 1: "slider_int", 2: "slider", 3: "angle", 4: "checkbox", 5: "color", 6: "point",
          7: "popup", 8: "custom", 9: "nodata", 10: "float", 11: "arbitrary", 12: "path",
          13: "group", 14: "group_end", 15: "button", 16: "reserved", 17: "reserved", 18: "point3d"}
# виды, у которых нет значения, влияющего на картинку
STRUCTURAL = {"group", "group_end", "button", "nodata", "reserved"}


def _u8(b):
    if b[:4] == b"Utf8":
        return b[8:8 + struct.unpack(">I", b[4:8])[0]].decode("utf8", "replace")
    return b.decode("utf8", "replace").rstrip("\x00")


def _mn(b):
    return b.split(b"\x00")[0].decode("latin1")


def _fix(b):  # 16.16 fixed
    return struct.unpack(">i", b)[0] / 65536.0


def _child(node, cid=None, typ=None):
    for c in node.get("children", []):
        if cid and c["id"] == cid and "children" not in c:
            return c
        if typ and c.get("type") == typ:
            return c
    return None


# ------------------------------------------------------------------ pard
def parse_pard(b):
    """Определение параметра эффекта. Возвращает dict; сырой чанк всегда сохраняется."""
    t = b[15]
    d = {"ptype": t, "kind": PTYPES.get(t, f"unknown_{t}"), "name": b[16:48].split(b"\x00")[0].decode("latin1"),
         "ui_flags": struct.unpack(">I", b[48:52])[0], "raw_pard": b.hex()}
    v = b[56:]
    k = d["kind"]
    try:
        if k in ("slider", "angle", "slider_int"):
            # PF_FixedSliderDef: value, value_str[32], value_desc[32], valid_min, valid_max,
            #                    slider_min, slider_max, default, precision, display_flags
            if k == "angle":
                d["default"] = [_fix(v[4:8])]
            else:
                d["min"], d["max"] = _fix(v[68:72]), _fix(v[72:76])
                d["slider_min"], d["slider_max"] = _fix(v[76:80]), _fix(v[80:84])
                d["default"] = [_fix(v[84:88])]
                d["percent"] = bool(struct.unpack(">h", v[90:92])[0] & 1)
        elif k == "float":
            # PF_FloatSliderDef: value f64, phase f64, desc[32], valid_min..default f32×5, precision, display_flags
            mn, mx, smn, smx, df = struct.unpack(">5f", v[48:68])
            d.update(min=mn, max=mx, slider_min=smn, slider_max=smx, default=[df])
            d["percent"] = bool(struct.unpack(">h", v[70:72])[0] & 1)
        elif k == "checkbox":
            d["default"] = [float(v[4])]
        elif k == "color":
            d["default"] = [float(x) for x in v[4:8]]  # ARGB 0..255
        elif k == "point":
            # default хранится в процентах слоя (x_value, y_value 16.16) -> доли
            d["default"] = [_fix(v[12:16]) / 100.0, _fix(v[16:20]) / 100.0]
        elif k == "popup":
            n, df = struct.unpack(">HH", v[4:8])
            d["num_options"] = n
            d["default"] = [float(df)]
        elif k == "point3d":
            d["default"] = list(struct.unpack(">3d", v[24:48])) if len(v) >= 48 else None
    except (struct.error, IndexError):
        d["unparsed"] = "pard"
    return d


# ------------------------------------------------------------------ keys
def parse_tdb4(b):
    comps = struct.unpack(">H", b[2:4])[0]
    attrs = b[4:6]
    typ = b[54:58] if len(b) >= 58 else b"\0\0\0\0"
    return {"dims": comps,
            "spatial": bool(attrs[1] & 0x08),       # position-свойство: другой формат ключей
            "static": bool(attrs[1] & 0x01),
            "time_scale": struct.unpack(">I", b[10:14])[0] or 30720,
            "is_color": bool(typ[3] & 0x01), "is_integer": bool(typ[3] & 0x04),
            "no_value": bool(typ[1] & 0x01), "raw_tdb4": b.hex()}


def _ease(vals):
    return {"speed": vals[0], "influence": vals[1]}


def parse_keys(list_node, meta):
    """LIST list (lhd3 + ldat) -> ключи со ВСЕМИ измерениями, ease по измерениям, касательными."""
    hd = _child(list_node, "lhd3")["data"]
    count = struct.unpack(">H", hd[10:12])[0]
    size = struct.unpack(">H", hd[18:20])[0]
    ld = _child(list_node, "ldat")
    raw = ld["data"] if ld else b""
    if count and not size:
        size = len(raw) // count
    n = meta["dims"]
    if meta["is_color"] and size == 152:
        layout = "color"
    elif meta["no_value"] and size == 64:
        layout = "novalue"
    elif meta["spatial"] and size == 56 + 24 * n:
        layout = "spatial"
    elif size == 8 + 40 * n:
        layout = "multi"
    # tdb4 не совпал с размером записи — определяем по размеру, но помечаем
    elif size == 152:
        layout = "color"
    elif size >= 48 and (size - 8) % 40 == 0:
        layout, n = "multi", (size - 8) // 40
    elif size >= 80 and (size - 56) % 24 == 0:
        layout, n = "spatial", (size - 56) // 24
    else:
        layout = "unknown"
    keys = []
    for i in range(min(count, len(raw) // size if size else 0)):
        it = raw[i * size:(i + 1) * size]
        k = {"tick": struct.unpack(">i", it[0:4])[0], "interp_in": it[4], "interp_out": it[5], "label": it[6],
             "continuous": bool(it[7] & 0x08), "auto_bezier": bool(it[7] & 0x10), "roving": bool(it[7] & 0x20),
             "raw": it.hex()}
        k["t"] = k["tick"] / float(meta["time_scale"])
        body = it[8:]
        f = lambda off, c: list(struct.unpack(f">{c}d", body[off:off + 8 * c]))  # noqa: E731
        if layout == "multi":
            k["value"] = f(0, n)
            si, ii, so, io = f(8 * n, n), f(16 * n, n), f(24 * n, n), f(32 * n, n)
            k["ease_in"] = [_ease(p) for p in zip(si, ii)]
            k["ease_out"] = [_ease(p) for p in zip(so, io)]
        elif layout == "spatial":
            e = f(16, 4)
            k["ease_in"], k["ease_out"] = [_ease(e[0:2])], [_ease(e[2:4])]
            k["value"], k["tan_in"], k["tan_out"] = f(48, n), f(48 + 8 * n, n), f(48 + 16 * n, n)
        elif layout == "color":
            e = f(16, 4)
            k["ease_in"], k["ease_out"] = [_ease(e[0:2])], [_ease(e[2:4])]
            k["value"] = f(48, 4)  # ARGB 0..255
        elif layout == "novalue":
            e = f(16, 4)
            k["ease_in"], k["ease_out"] = [_ease(e[0:2])], [_ease(e[2:4])]
            k["value"] = None
        else:
            k["unparsed"] = "key"
        keys.append(k)
    return {"layout": layout, "item_size": size, "count": count, "keys": keys}


# ------------------------------------------------------------------ properties
def parse_tdbs(node, sibling_arb=None):
    """LIST tdbs -> значение/ключи/выражение свойства."""
    p = {}
    meta = None
    for k in node["children"]:
        cid = k["id"]
        if cid == "tdsn":
            p["user_name"] = _u8(k["data"])
        elif cid == "tdsb":
            p["flags"] = struct.unpack(">I", k["data"][:4])[0]
        elif cid == "tdb4":
            meta = parse_tdb4(k["data"])
            p.update(meta)
        elif cid == "cdat":
            d = k["data"]
            n = len(d) // 8
            p["cdat"] = [struct.unpack(">d", d[i * 8:i * 8 + 8])[0] for i in range(n)]
            p["raw_cdat"] = d.hex()
        elif cid == "Utf8":
            p["expr"] = _u8(k["data"])
        elif cid == "tdum":
            p["tdum"] = struct.unpack(">d", k["data"][:8])[0]
        elif cid == "tduM":
            p["tduM"] = struct.unpack(">d", k["data"][:8])[0]
        elif cid == "tdpi":
            p.setdefault("layer_ref", {})["index"] = struct.unpack(">I", k["data"][:4])[0]
        elif cid == "tdps":
            p.setdefault("layer_ref", {})["source"] = struct.unpack(">i", k["data"][:4])[0]
        elif cid == "tdli":
            m = struct.unpack(">i", k["data"][:4])[0]
            p["mask_ref"] = m if m > 0 else 0   # 0 / -1 = «None»
        elif k.get("type") == "list" and meta:
            p["anim"] = parse_keys(k, meta)
        elif k.get("type") == "aRbs":
            sibling_arb = k
        elif "children" not in k:
            p.setdefault("extra_chunks", {})[cid] = k["data"].hex()
    if meta and "cdat" in p:
        p["value"] = p["cdat"][:max(1, meta["dims"])]
    if sibling_arb is not None:
        a = _child(sibling_arb, "aRbp")
        if a is not None:
            p["arb"] = a["data"].hex()
    return p


def parse_group(tdgp):
    """LIST tdgp -> упорядоченный список (match, свойство | вложенная группа)."""
    out = []
    ch = tdgp["children"]
    cur = None
    i = 0
    while i < len(ch):
        c = ch[i]
        if c["id"] == "tdmn" and "children" not in c:
            cur = _mn(c["data"])
            if cur == GROUP_END:
                cur = None
        elif cur is not None and c.get("type") == "tdbs":
            arb = ch[i + 1] if i + 1 < len(ch) and ch[i + 1].get("type") == "aRbs" else None
            p = parse_tdbs(c, arb)
            p["match"] = cur
            out.append(p)
            if arb is not None:
                i += 1
            cur = None
        elif cur is not None and c.get("type") == "tdgp":
            name = next((_u8(k["data"]) for k in c["children"] if k["id"] == "tdsn"), "")
            out.append({"match": cur, "user_name": name, "children": parse_group(c)})
            cur = None
        elif cur is not None and "children" in c:
            # незнакомый контейнер внутри эффекта (om-s, otst…): сохраняем как есть
            out.append({"match": cur, "container": c.get("type"), "unparsed": "container"})
            cur = None
        i += 1
    return out


def parse_part(part):
    """LIST parT -> {match: определение}, с группами (по парам group/group_end)."""
    defs, order, stack, cur = {}, [], [], None
    for k in part["children"]:
        if k["id"] == "tdmn":
            cur = _mn(k["data"])
        elif k["id"] == "pard" and cur:
            d = parse_pard(k["data"])
            if d["kind"] == "group_end":
                if stack:
                    stack.pop()
            d["group"] = list(stack)
            if d["kind"] == "group":
                stack.append(d["name"])
            defs[cur] = d
            order.append(cur)
        elif k["id"] == "aRbp" and cur in defs:
            defs[cur]["arb_default"] = k["data"].hex()  # произвольные данные по умолчанию
        elif k["id"] == "pdnm" and cur in defs:
            s = _u8(k["data"])
            d = defs[cur]
            if d["kind"] == "popup":
                d["options"] = s.split("|")
            elif s and not d["name"]:
                d["name"] = s  # у чекбокса подпись лежит в pdnm
            d["pdnm"] = s
    return defs, order


def _index_of(match):
    tail = match.rsplit("-", 1)[-1]
    return int(tail) if tail.isdigit() else None


def effect_from_sspc(match, sspc, shared_defs=None, user_name=None):
    """Один экземпляр эффекта (LIST sspc) -> IR."""
    fnam = next((_u8(c["data"]) for c in sspc["children"] if c["id"] == "fnam"), None)
    part = _child(sspc, typ="parT")
    defs, order = parse_part(part) if part else ({}, [])
    if not defs and shared_defs:  # .aep: определения лежат в LIST EfdG
        defs, order = shared_defs.get(match, ({}, []))
    tdgp = _child(sspc, typ="tdgp")
    e = {"match": match, "plugin_name": fnam, "name": user_name or fnam, "enabled": True, "params": [], "builtin": {}}
    if tdgp is None:
        e["unparsed"] = "no tdgp"
        return e
    tdsb = _child(tdgp, "tdsb")
    if tdsb is not None:
        e["enabled"] = bool(struct.unpack(">I", tdsb["data"][:4])[0] & 1)
    tdsn = _child(tdgp, "tdsn")
    if tdsn is not None and _u8(tdsn["data"]):
        e["name"] = _u8(tdsn["data"])
    e["_defs"] = (defs, order) if defs else None
    for p in parse_group(tdgp):
        if p["match"] == BUILTIN:
            e["builtin"] = _builtin(p)
        else:
            e["params"].append(p)
    _bind(e, defs, order)
    return e


def _bind(e, defs, order):
    """Соединяет значения параметров с их определениями (pard). Определения лежат только у первого
    экземпляра эффекта в файле (.ffx) или в LIST EfdG (.aep) — остальные экземпляры берут их оттуда."""
    out = []
    for p in e["params"]:
        m = p["match"]
        d = dict(defs.get(m, {})) or {"kind": "undefined", "name": "", "group": []}
        raw = {k: v for k, v in p.items() if k not in ("kind", "name", "group", "renamed_to", "index")}
        un = raw.pop("user_name", p.get("_user_name", ""))
        d.update(raw)
        d["_user_name"] = un
        d["index"] = _index_of(m)
        # tdsn у параметра = имя, показанное в AE (пользователь мог переименовать)
        if un and un != "-_0_/-" and un != d.get("name"):
            if d.get("name"):
                d["renamed_to"] = un
            else:
                d["name"] = un
        if d.get("anim") and d["anim"]["layout"] == "unknown":
            d["unparsed"] = "keys"
        out.append(d)
    e["params"] = out
    # параметры, объявленные в parT, но отсутствующие в значениях, — для полноты учёта
    present = {p["match"] for p in out}
    e["declared_only"] = [m for m in order if m not in present and defs[m]["kind"] not in STRUCTURAL]


def _builtin(group):
    """Compositing Options: Effect Opacity, ссылки на маски эффекта."""
    out = {"effect_opacity": 100.0, "mask_refs": [], "raw": []}

    def walk(ps):
        for p in ps:
            if "children" in p:
                if p["match"] == "ADBE Effect Mask Parade":
                    out["mask_refs"] = [c for c in p["children"]]
                walk(p["children"])
            elif p["match"] == "ADBE Effect Mask Opacity":
                out["effect_opacity"] = (p.get("value") or [100.0])[0]
                if p.get("anim"):
                    out["effect_opacity_anim"] = p["anim"]
                if p.get("expr"):
                    out["effect_opacity_expr"] = p["expr"]
            else:
                out["raw"].append({k: p[k] for k in ("match", "value") if k in p})
    walk(group.get("children", []))
    return out


# ------------------------------------------------------------------ файл целиком
def _shared_defs(tree):
    """.aep: LIST EfdG -> {effect match: (defs, order)}."""
    res = {}

    def walk(ns):
        for n in ns:
            if n.get("type") == "EfDf":
                m, sspc = None, None
                for c in n["children"]:
                    if c["id"] == "tdmn" and "children" not in c:
                        m = _mn(c["data"])
                    if c.get("type") == "sspc":
                        sspc = c
                if m and sspc is not None:
                    part = _child(sspc, typ="parT")
                    if part:
                        res[m] = parse_part(part)
            elif "children" in n:
                walk(n["children"])
    walk(tree)
    return res


def effects_in(nodes, shared_defs=None, skip_definitions=True):
    """Все экземпляры эффектов под узлами `nodes`, в порядке файла (= порядок стека)."""
    out = []

    def walk(ns):
        last = None
        for n in ns:
            if n["id"] == "tdmn" and "children" not in n:
                last = _mn(n["data"])
            elif n.get("type") == "sspc":
                if _child(n, "fnam") is not None or _child(n, typ="tdgp") is not None:
                    out.append(effect_from_sspc(last or "?", n, shared_defs))
            elif n.get("type") == "tdsi":
                continue  # в .ffx: путь к месту вставки, там свой tdmn
            elif n.get("type") == "EfdG" and skip_definitions:
                continue
            elif "children" in n:
                walk(n["children"])
    walk(nodes)
    return out


def _ffx_matches(tree):
    """В .ffx match name эффекта лежит в LIST tdsp > LIST tdsi (второй tdmn), а не перед sspc."""
    res = []

    def walk(ns):
        for n in ns:
            if n.get("type") == "tdsp":
                ms = [_mn(c["data"]) for t in n["children"] if t.get("type") == "tdsi"
                      for c in t["children"] if c["id"] == "tdmn"]
                res.append(ms[-1] if ms else None)
            elif "children" in n:
                walk(n["children"])
    walk(tree)
    return res


def load(path):
    """Файл .ffx или .aep -> {"source", "kind", "time_scale", "effects": [...]}."""
    with open(path, "rb") as f:
        data = f.read()
    tree = parse(data)
    kind = tree[0].get("type") if tree and "children" in tree[0] else "?"
    kind = {"FaFX": "ffx", "Egg!": "aep"}.get(kind, kind)
    shared = _shared_defs(tree) if kind == "aep" else None
    effs = effects_in(tree, shared)
    if kind == "ffx":
        ms = [m for m in _ffx_matches(tree)]
        # tdsp идут парами к sspc в том же порядке
        fx_ms = [m for m in ms if m and m != "ADBE Effect Parade"]
        for e, m in zip(effs, fx_ms):
            e["match"] = m
    known = {}
    for i, e in enumerate(effs):
        e["order"] = i
        if e["match"] in (None, "?", "ADBE Effect Parade") and e["params"]:
            e["match"] = e["params"][0]["match"].rsplit("-", 1)[0]
        if e.get("_defs"):
            known.setdefault(e["match"], e["_defs"])
    for e in effs:
        if not e.pop("_defs", None) and e["match"] in known:
            _bind(e, *known[e["match"]])
        for p in e["params"]:
            p.pop("_user_name", None)
    return {"source": os.path.basename(path), "kind": kind, "effects": effs}


def layer_effects(layr_node, shared_defs):
    """.aep: эффекты одного слоя (узел LIST Layr)."""
    return effects_in([layr_node], shared_defs)


def shared_definitions(tree):
    return _shared_defs(tree)


# ------------------------------------------------------------------ служебное
def is_animated(p):
    return bool(p.get("anim") and p["anim"]["keys"])


def is_default(p, eps=1e-6):
    """Значение равно умолчанию плагина (None, если умолчание неизвестно)."""
    if is_animated(p) or p.get("expr"):
        return False
    d, v = p.get("default"), p.get("value")
    if d is None or v is None:
        return None
    return all(abs(a - b) <= eps * max(1.0, abs(b)) + 1e-4 for a, b in zip(v, d))


def summary(ir):
    lines = [f"{ir['source']} ({ir['kind']}): эффектов {len(ir['effects'])}"]
    for e in ir["effects"]:
        ps = [p for p in e["params"] if p["kind"] not in STRUCTURAL]
        lines.append(f"  [{e['order']}] {e['name']}  <{e['match']}>  параметров {len(ps)}, "
                     f"анимировано {sum(is_animated(p) for p in ps)}, выражений {sum(bool(p.get('expr')) for p in ps)}, "
                     f"arb-данных {sum('arb' in p for p in ps)}" + ("" if e["enabled"] else "  [выключен]"))
    return "\n".join(lines)


if __name__ == "__main__":
    import json
    ir = load(sys.argv[1])
    if len(sys.argv) > 2 and sys.argv[2] == "--json":
        print(json.dumps(ir, ensure_ascii=False, indent=1))
    else:
        print(summary(ir))
