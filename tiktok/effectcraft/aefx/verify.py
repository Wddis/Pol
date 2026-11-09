"""Слой 3. Проверка на сайте: дошло ли до EffectCraft ровно то, что было в файле AE.

runner.js (config.aefxVerify = true) после применения команд сохраняет проект и возвращает его состояние.
Здесь каждое ожидание конвертера (значение, имя эффекта, каждый ключ — время, значение, интерполяция,
ease, касательные, roving — и каждое выражение) сверяется с тем, что реально записано в проекте.
"""
TPS = 254016000000  # тики времени EffectCraft на секунду
EPS = 1e-4


def _close(a, b, eps=EPS):
    if isinstance(a, bool) or isinstance(b, bool):
        return bool(a) == bool(b)
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(a - b) <= eps * max(1.0, abs(a), abs(b))
    if isinstance(a, (list, tuple)) and isinstance(b, (list, tuple)):
        n = min(len(a), len(b))
        return n > 0 and all(_close(x, y, eps) for x, y in zip(a[:n], b[:n]))
    return a == b


def _resolve(v, size):
    """$frac / $fracVec / $fracSpeed -> пиксели, как это делает runner.js."""
    if isinstance(v, dict):
        if "$frac" in v:
            return [v["$frac"][0] * size[0], v["$frac"][1] * size[1]]
        if "$fracVec" in v:
            return [v["$fracVec"][0] * size[0], v["$fracVec"][1] * size[1]]
        if "$fracSpeed" in v:
            dx, dy = v["dir"]
            ln = (dx * dx + dy * dy) ** 0.5
            k = ((dx * size[0]) ** 2 + (dy * size[1]) ** 2) ** 0.5 / ln if ln else (size[0] + size[1]) / 2
            return v["$fracSpeed"] * k
    return v


def _layer(state, ref):
    lid = state["saved"].get(ref[1:]) if isinstance(ref, str) and ref.startswith("$") else ref
    for comp in state["comps"]:
        for layer in comp["kind"]["layers"]:
            if layer["id"] == lid:
                return layer, lid
    return None, lid


def _node(layer, path):
    segs = path.split("/")
    node = next((g for g in layer["props"]["children"] if g.get("match") == segs[0]), None)
    for s in segs[1:]:
        if node is None:
            return None
        ch = node.get("children", [])
        if s.startswith("#"):
            i = int(s[1:]) - 1
            node = ch[i] if 0 <= i < len(ch) else None
        else:
            node = next((c for c in ch if c.get("match") == s), None)
    return node


def _val(node_value):
    return node_value.get("v") if isinstance(node_value, dict) else node_value


def verify(expect, state):
    bad, clamped, checked = [], [], 0
    for x in expect:
        layer, lid = _layer(state, x["layer"])
        where = f"{x['path']}"
        if layer is None:
            bad.append(f"{where}: слой {x['layer']} не найден")
            continue
        size = state.get("lsize", {}).get(str(lid)) or [1, 1]
        node = _node(layer, x["path"])
        if node is None:
            bad.append(f"{where}: свойства нет в проекте")
            continue
        if "name" in x:
            checked += 1
            if node.get("name") != x["name"]:
                bad.append(f"{where}: имя «{node.get('name')}» вместо «{x['name']}»")
        if "expr" in x:
            checked += 1
            ex = node.get("expr") or {}
            if ex.get("text") != x["expr"] or not ex.get("enabled", True):
                bad.append(f"{where}: выражение не совпало")
        if "value" in x:
            checked += 1
            want, got = _resolve(x["value"], size), _val(node.get("value"))
            if not _close(want, got):
                (clamped if x.get("clamped") else bad).append(f"{where}: значение {got} вместо {want}")
            elif x.get("clamped"):
                pass
        if "keys" in x:
            ks = node.get("keys") or []
            checked += 1
            if len(ks) != len(x["keys"]):
                bad.append(f"{where}: ключей {len(ks)} вместо {len(x['keys'])}")
                continue
            for i, (w, g) in enumerate(zip(x["keys"], ks)):
                checked += 1
                err = []
                if not _close(w["time"], g["time"] / TPS, 1e-6):
                    err.append(f"время {g['time'] / TPS:g}≠{w['time']:g}")
                if not _close(_resolve(w["value"], size), _val(g["value"])):
                    err.append(f"значение {_val(g['value'])}≠{_resolve(w['value'], size)}")
                for side in ("in", "out"):
                    if g[side + "_interp"].lower() != w[side]:
                        err.append(f"{side}: {g[side + '_interp']}≠{w[side]}")
                    for ez in w.get(side + "_ease", []):
                        ge = g.get(side + "_ease") or []
                        d = ez["dim"] if ez["dim"] is not None else 0
                        if d >= len(ge):
                            err.append(f"{side}-ease[{d}] отсутствует")
                            continue
                        if not _close(_resolve(ez["speed"], size), ge[d]["speed"]):
                            err.append(f"{side}-скорость[{d}] {ge[d]['speed']:g}≠{_resolve(ez['speed'], size):g}")
                        if not _close(ez["influence"] / 100.0, ge[d]["influence"], 1e-3):
                            err.append(f"{side}-влияние[{d}] {ge[d]['influence']:g}≠{ez['influence'] / 100:g}")
                if "tan_in" in w:
                    if not _close(_resolve(w["tan_in"], size), g["spatial_in"]) or not _close(_resolve(w["tan_out"], size), g["spatial_out"]):
                        err.append("касательные не совпали")
                if bool(g.get("roving")) != bool(w.get("roving")):
                    err.append("roving не совпал")
                if err:
                    bad.append(f"{where}: ключ {i + 1}: " + "; ".join(err))
    errs = (state.get("errors") or {}).get("text") or []
    for t in errs:
        bad.append("ошибка выражения на сайте: " + t)
    if state.get("stepErrors"):
        bad.append(f"команд с ошибкой при применении: {state['stepErrors']}")
    unexpected = [c for c in state.get("clamps", []) if not any(e.get("clamped") and e["path"] == c["path"] for e in expect)]
    for c in unexpected:
        bad.append(f"{c['path']}: движок обрезал {c['asked']} до {c['got']} (не было в отчёте)")
    ok = not bad
    lines = [f"ПРОВЕРКА НА САЙТЕ: сверено {checked} значений/ключей/выражений/имён — "
             + ("всё совпало" if ok else f"расхождений: {len(bad)}")]
    lines += ["  ✗ " + b for b in bad]
    lines += ["  ~ обрезано диапазоном EffectCraft (уже в отчёте как CLAMPED): " + c for c in clamped]
    return {"ok": ok, "checked": checked, "mismatches": bad, "clamped": clamped, "text": "\n".join(lines)}
