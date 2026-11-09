"""Слой 2. IR -> команды EffectCraft, с полным учётом (ledger) и ожиданиями для проверки.

Отличия от старого aeconv/ae2ec.py (см. README.md, раздел «Что урезалось»):
  * эффект ищется во ВСЁМ каталоге EffectCraft (306 эффектов), а не в белом списке из 15;
  * параметры сопоставляются по определению из самого файла (имя + группа + тип), переносятся ВСЕ,
    включая равные умолчанию (умолчания AE и EffectCraft могут различаться);
  * ключи переносятся со всеми измерениями, ease по каждому измерению, hold/roving/continuous,
    пространственными касательными; выражения — как есть;
  * списки (popup) переводятся по подписи пункта, а не «индекс минус один»;
  * произвольные данные (arb) расшифровываются декодерами; чего расшифровать нельзя — LOSS;
  * сторонние плагины: каждый параметр переносится на слой контролом (значение, ключи, выражение 1:1),
    а эффекты-эмуляции управляются формулами-выражениями от этих контролов — анимация не запекается
    и не теряется, параметры остаются живыми и редактируемыми на сайте;
  * всё, что не перенесено, попадает в отчёт. Молча ничего не пропускается.
"""
import json
import math
import os
import re

from . import ir as IR
from .ledger import Ledger

HERE = os.path.dirname(os.path.abspath(__file__))
CAT = json.load(open(os.path.join(HERE, "catalog", "ec_catalog.json"), encoding="utf8"))["effects"]
BY_ADOBE = {e["adobeMatchName"]: n for n, e in CAT.items() if e.get("adobeMatchName")}

# match name AE -> эффект EffectCraft там, где не совпадают ни match name, ни отображаемое имя
ALIASES = {
    "ADBE Motion Blur": "Directional Blur", "ADBE WRPMESH": "Warp", "CS BlockLoad": "CC Block Load",
    "ADBE Gaussian Blur": "Gaussian Blur (Legacy)", "ADBE Box Blur2": "Fast Box Blur", "ADBE Box Blur": "Fast Box Blur",
    "ADBE Ramp": "Gradient Ramp", "ADBE Brightness & Contrast": "Brightness & Contrast",
    "ADBE Pro Levels": "Levels (Individual Controls)", "ADBE Easy Levels": "Levels",
    "ADBE Fast Blur": "Fast Box Blur", "ADBE Offset": "Offset", "ADBE Posterize": "Posterize",
}
# у этих параметров AE хранит значение в других единицах, чем показывает EffectCraft: (эффект, id) -> (a, b)
UNIT_FIX = {("Drop Shadow", "opacity"): (100.0 / 255.0, 0.0), ("Radial Shadow", "opacity"): (100.0 / 255.0, 0.0)}
# калибровки: EffectCraft 0.9.2 рендерит эти параметры иначе, чем AE (проверено рендером, см. ROADMAP §5–6)
CALIB_AFFINE = {("CC Block Load", "completion"): (0.6, 80.0, "шкала Completion сдвинута: 0.6·c + 80")}
# калибровка, зависящая от соседнего параметра, — выражением (value = перенесённое значение/ключи AE)
CALIB_EXPR = {("Turbulent Displace", "amount"): ('value * Math.min(1, {self}("Size") / 100)',
                                                 "в AE смещение растёт с Size: Amount·Size/100 (выражение)")}

KIND2EC = {"slider": {"scalar"}, "float": {"scalar"}, "slider_int": {"scalar"}, "angle": {"scalar"},
           "checkbox": {"bool", "enum"}, "popup": {"enum", "bool"}, "color": {"color"}, "point": {"vec2"},
           "point3d": {"vec3", "vec2"}, "layer": {"layer"}, "arbitrary": {"string"}, "path": {"scalar", "enum", "string"},
           "undefined": {"scalar", "bool", "enum", "color", "vec2", "vec3", "layer", "string"}}
INTERP = {1: "linear", 2: "bezier", 3: "hold"}


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


# ------------------------------------------------------------------ сопоставление
def resolve_effect(e):
    """IR-эффект -> имя эффекта EffectCraft (или None, если это сторонний плагин)."""
    m = e["match"]
    if m in BY_ADOBE:
        return BY_ADOBE[m], "match name"
    if m in ALIASES and ALIASES[m] in CAT:
        return ALIASES[m], "таблица соответствий"
    for cand in (e.get("plugin_name"), m):
        if cand in CAT:
            return cand, "имя эффекта"
    nn = {norm(n): n for n in CAT}
    for cand in (e.get("plugin_name"), m):
        if cand and norm(cand) in nn:
            return nn[norm(cand)], "имя эффекта (нормализованное)"
    return None, None


def _same_name(ae, ec):
    a, b = norm(ae), norm(ec)
    if not a:
        return False
    return a == b or (len(ae or "") >= 31 and b.startswith(a))  # pard.name обрезан до 31 символа


def match_params(ae_params, ec_params):
    """-> {ae match: ec param}. Только уверенные совпадения: имя (+группа) и совместимый тип."""
    free = list(ec_params)
    res = {}

    def ok(p, q):
        return q["type"] in KIND2EC.get(p["kind"], set())

    def grp(x):
        g = x.get("group") or []
        return norm(g[-1]) if g else ""

    todo = [p for p in ae_params if p["kind"] not in IR.STRUCTURAL]
    for strict_group in (True, False):
        for p in todo:
            if p["match"] in res:
                continue
            cands = [q for q in free if _same_name(p.get("name"), q["name"]) and ok(p, q)
                     and (not strict_group or grp(p) == grp(q))]
            if cands:
                res[p["match"]] = cands[0]
                free.remove(cands[0])
    return res, free


# ------------------------------------------------------------------ конвертер
class Target:
    """Экземпляр эффекта EffectCraft, который собирается на слое."""

    def __init__(self, conv, ec_name, name=None):
        self.conv, self.name, self.entry = conv, ec_name, CAT[ec_name]
        conv.n += 1
        conv.cur_targets.append(ec_name)
        self.path = f"effects/#{conv.n}"
        # имя на слое задаётся явно: EffectCraft сам нумерует повторы («Glow 2»), а на имена ссылаются выражения
        self.label = conv.unique(name or f"{conv.e['name']} · {ec_name}")
        conv.X("effect.apply", effect=ec_name, layers=[conv.layer], save=f"aefx{conv.n}")
        conv.X("prop.renameGroup", layer=conv.layer, prop=f"$aefx{conv.n}", name=self.label)
        conv.expect.append({"layer": conv.layer, "path": self.path, "name": self.label})
        self.q = {p["id"]: p for p in self.entry["params"]}

    def param(self, pid):
        if pid not in self.q:  # короткий id без группы: exposure -> master/exposure
            hits = [k for k in self.q if k.split("/")[-1] == pid]
            if len(hits) != 1:
                raise KeyError(f"{self.name}: нет параметра {pid}")
            pid = hits[0]
        return self.q[pid]

    def const(self, pid, value):
        """постоянное значение, не зависящее от исходного файла (часть рецепта эмуляции)."""
        q = self.param(pid)
        self.conv.set_value(f"{self.path}/{q['id']}", q, value)

    def expr(self, pid, template, note=""):
        """значение = формула от параметров исходного эффекта: «{Glow Width} * 0.5» (см. Converter.expr)."""
        q = self.param(pid)
        return self.conv.expr(f"{self.path}/{q['id']}", q, template, note)

    def direct(self, pid, ae_name, a=1.0, b=0.0):
        """перенос параметра AE как есть (значение, ключи, выражение), с линейным пересчётом a·x+b."""
        q = self.param(pid)
        p = self.conv.P(ae_name)
        if p is None:
            return
        self.conv.emit_direct(f"{self.path}/{q['id']}", q, p, a, b, status="DERIVED")


class Converter:
    def __init__(self, layer="$cur", t0=0.0, comp=(1080, 1080), fps=30.0, layer_start=0.0, fx_start=0,
                 layer_size=None, origin=None, points="auto", layer_map=None, source_kind="ffx", roto_strokes=None):
        self.layer, self.t0, self.comp, self.fps, self.ls = layer, t0, comp, fps, layer_start
        self.n = fx_start
        self.layer_size, self.origin, self.points = layer_size, origin, points
        self.layer_map = layer_map or {}
        self.kind = source_kind
        self.roto_strokes = roto_strokes
        self.rig_unused = False  # True внутри рецепта: неиспользованные параметры сохраняются контролами
        self.steps, self.expect = [], []
        self.ledger = None
        self.base = 0.0     # секунды файла, принятые за t0
        self.e = None       # текущий эффект IR
        self.cur_targets = []  # эффекты EffectCraft, созданные для текущего эффекта AE
        self.rigs, self.rig_names = {}, set()

    # --- низкий уровень
    def X(self, c, **p):
        self.steps.append({"c": c, "p": p})

    def unique(self, label):
        base, i = label, 1
        while label in self.rig_names:
            i += 1
            label = f"{base} {i}"
        self.rig_names.add(label)
        return label

    def tc(self, k):   # время ключа в композиции
        return round(self.t0 + (k["t"] - self.base), 6)

    def P(self, name, required=False):
        """параметр текущего эффекта по имени (как в AE) или match name."""
        for p in self.e["params"]:
            if p["kind"] in IR.STRUCTURAL:
                continue
            if p.get("name") == name or p["match"] == name:
                return p
        nn = norm(name)
        for p in self.e["params"]:
            if p["kind"] not in IR.STRUCTURAL and norm(p.get("name")) == nn:
                return p
        if required:
            raise KeyError(name)
        return None

    def point(self, v, vec=False):
        """точка эффекта из файла -> координаты слоя EffectCraft (пиксели)."""
        frac = self.points == "frac" or (self.points == "auto" and (self.kind == "ffx" or all(abs(c) <= 4 for c in v[:2])))
        if not frac:
            return [round(v[0], 3), round(v[1], 3)]
        if self.origin is not None:  # текстовый слой: точка задана в кадре, начало координат слоя — в точке текста
            W, H = self.comp
            return [round(v[0] * W, 3), round(v[1] * H, 3)] if vec else \
                [round(v[0] * W - self.origin[0], 3), round(v[1] * H - self.origin[1], 3)]
        if self.layer_size is None:  # размер слоя станет известен только на сайте — посчитает runner.js
            return {"$fracVec" if vec else "$frac": [v[0], v[1]]}
        W, H = self.layer_size
        return [round(v[0] * W, 3), round(v[1] * H, 3)]

    # --- преобразование значения AE -> EffectCraft
    def to_ec(self, q, p, vals, a=1.0, b=0.0):
        """-> (значение EffectCraft, статус, пояснение). Бросает ValueError, если перенести нельзя."""
        t = q["type"]
        if t == "scalar":
            if p["kind"] == "path":
                raise ValueError("ссылка на маску: в пресете нет самой маски")
            x = vals[0] * a + b
            return x, ("EXACT" if (a, b) == (1.0, 0.0) else "CONVERTED"), ""
        if t == "bool":
            if p["kind"] == "popup":
                return vals[0] >= 2, "CONVERTED", "список из двух пунктов → флажок"
            return bool(round(vals[0])), "EXACT", ""
        if t == "enum":
            return self.enum(q, p, vals[0])
        if t == "color":
            A, R, G, B = (list(vals) + [255.0] * 4)[:4]
            return [round(R / 255, 6), round(G / 255, 6), round(B / 255, 6), round(A / 255, 6)], "CONVERTED", "ARGB 0–255 → RGBA 0–1"
        if t == "vec2":
            return self.point(vals), "CONVERTED", "доли слоя → пиксели слоя"
        if t == "vec3":
            return [round(c, 4) for c in vals[:3]], "EXACT", ""
        if t == "layer":
            ref = (p.get("layer_ref") or {}).get("index", 0)
            if ref in self.layer_map:
                return self.layer_map[ref], "CONVERTED", "ссылка на слой по карте слоёв"
            raise ValueError(f"ссылка на слой №{ref}: слой не входит в файл (задайте layer_map)")
        raise ValueError(f"тип {t}: нужен декодер произвольных данных")

    def enum(self, q, p, v):
        idx = int(round(v))  # AE: с единицы, разделители «(-» тоже занимают номер
        opts, ec = p.get("options"), q.get("options") or []
        if p["kind"] == "checkbox":
            return int(bool(round(v))), "CONVERTED", "флажок → список"
        if opts and 1 <= idx <= len(opts):
            label = opts[idx - 1]
            for i, o in enumerate(ec):
                if norm(o) == norm(label):
                    return i, "CONVERTED", f"пункт «{label}»"
            real = [o for o in opts if o != "(-"]
            if len(real) == len(ec):  # те же пункты под другими подписями — по порядку без разделителей
                j = len([o for o in opts[:idx] if o != "(-"]) - 1
                return j, "CONVERTED", f"пункт «{label}» → «{ec[j]}» (по порядку)"
            raise ValueError(f"пункт «{label}» отсутствует в EffectCraft ({', '.join(ec)})")
        j = idx - 1
        if ec and not 0 <= j < len(ec):
            raise ValueError(f"пункт №{idx} вне списка EffectCraft")
        return j, "CONVERTED", "по номеру пункта (подписей в файле нет)"

    def set_value(self, path, q, value, clamp_report=None):
        """prop.set + ожидание для проверки; значения вне диапазона EffectCraft не прячутся."""
        clamped = None
        if q["type"] == "scalar" and isinstance(value, (int, float)):
            lo, hi = q.get("min"), q.get("max")
            if (lo is not None and value < lo - 1e-9) or (hi is not None and value > hi + 1e-9):
                clamped = f"{value:g} вне [{lo}, {hi}]"
            value = round(value, 6)
        self.X("prop.set", layer=self.layer, path=path, value=value)
        self.expect.append({"layer": self.layer, "path": path, "type": q["type"], "value": value, "clamped": bool(clamped)})
        return clamped

    # --- перенос параметра целиком
    def emit_direct(self, path, q, p, a=1.0, b=0.0, status=None, detail=""):
        """значение / ключи / выражение параметра AE -> параметр EffectCraft. Пишет строку учёта."""
        L = self.ledger
        tgt = f"{path.split('/', 2)[-1]}"
        try:
            if IR.is_animated(p):
                st, det = self._keys(path, q, p, a, b)
            else:
                val, st, det = self.to_ec(q, p, p.get("value") or [0.0], a, b)
                cl = self.set_value(path, q, val)
                if cl:
                    L.add(p, "CLAMPED", cl, tgt)
                    return
            if p.get("expr"):
                expr = p["expr"] if (a, b) == (1.0, 0.0) else f"({p['expr']}) * {a:g} + {b:g}"
                self.X("prop.setExpression", layer=self.layer, path=path, expression=expr)
                self.expect.append({"layer": self.layer, "path": path, "type": q["type"], "expr": expr})
                det = (det + "; " if det else "") + "выражение перенесено"
        except ValueError as ex:
            L.add(p, "LOSS", str(ex), tgt)
            return
        L.add(p, status or st, detail or det, tgt)

    def _keys(self, path, q, p, a, b):
        anim = p["anim"]
        if p.get("unparsed") or anim["layout"] == "unknown":
            raise ValueError(f"формат ключей не распознан (запись {anim['item_size']} байт) — сырые данные сохранены в IR")
        ks = anim["keys"]
        t = q["type"]
        vals, status, det = [], "EXACT", ""
        for k in ks:
            if k.get("value") is None:
                raise ValueError("ключи без значения (arb-анимация)")
            v, status, det = self.to_ec(q, p, k["value"], a, b)
            vals.append(v)
        exp_keys = []
        for k, v in zip(ks, vals):
            if isinstance(v, float):
                v = round(v, 6)
            self.X("prop.addKey", layer=self.layer, path=path, time=self.tc(k), timeBase="comp", value=v)
        for i, (k, v) in enumerate(zip(ks, vals)):
            lt = round(self.tc(k) - self.ls, 6)
            ek = {"time": self.tc(k), "value": v, "in": INTERP.get(k["interp_in"], "linear"),
                  "out": INTERP.get(k["interp_out"], "linear"), "roving": k["roving"], "in_ease": [], "out_ease": []}
            if t in ("bool", "enum", "layer", "string"):
                ek["in"] = ek["out"] = "hold"  # в EffectCraft такие свойства — только hold
                exp_keys.append(ek)
                continue
            if (ek["in"], ek["out"]) != ("linear", "linear") or k["roving"] or k["continuous"]:
                self.X("keys.select", keys=[{"layer": self.layer, "path": path, "time": lt}])
                ip = {"in": ek["in"], "out": ek["out"]}
                if k["roving"]:
                    ip["roving"] = True
                if k["continuous"]:
                    ip["continuous"] = True
                self.X("keys.interpolation", **ip)
            for side, code, eases in (("in", k["interp_in"], k.get("ease_in") or []), ("out", k["interp_out"], k.get("ease_out") or [])):
                if code != 2:
                    continue
                for dim, sp, infl in self._ease_dims(t, p, ks, i, side, eases, a):
                    kw = {"dim": dim} if dim is not None else {}
                    infl = round(min(100.0, max(0.1, infl * 100)), 4)
                    self.X("keys.setEase", layer=self.layer, path=path, time=lt, side=side, speed=sp, influence=infl, **kw)
                    ek[side + "_ease"].append({"dim": dim, "speed": sp, "influence": infl})
            if k.get("tan_in") and t == "vec2" and (any(k["tan_in"]) or any(k["tan_out"])):
                ti, to = self.point(k["tan_in"], vec=True), self.point(k["tan_out"], vec=True)
                self.X("keys.setSpatialTangents", layer=self.layer, path=path, time=lt, **{"in": ti, "out": to, "break": True})
                ek["tan_in"], ek["tan_out"] = ti, to
            exp_keys.append(ek)
        self.expect.append({"layer": self.layer, "path": path, "type": t, "keys": exp_keys})
        n = len(ks)
        extra = f"{n} ключей: интерполяция, ease по измерениям" + (", касательные" if any(k.get("tan_in") and any(k["tan_in"]) for k in ks) else "")
        return status, (det + "; " if det else "") + extra

    def _ease_dims(self, t, p, ks, i, side, eases, a):
        """скорости ease в единицах EffectCraft: [(dim | None, speed, influence 0..1)]."""
        k = ks[i]
        nb = ks[i - 1] if side == "in" and i > 0 else ks[i + 1] if side == "out" and i + 1 < len(ks) else None
        if t == "scalar":
            e = eases[0]
            return [(None, round(e["speed"] * a, 6) + 0.0, e["influence"])]
        if t == "color":
            if len(eases) >= 4:  # ease по каналам ARGB -> RGBA
                return [(d, round(eases[(d + 1) % 4]["speed"] / 255, 6), eases[(d + 1) % 4]["influence"]) for d in range(4)]
            e = eases[0]         # одна скорость вдоль пути в пространстве цвета -> проекции на каналы
            dv = [(nb["value"][c] - k["value"][c]) * (1 if side == "out" else -1) for c in range(4)] if nb else [0] * 4
            ln = math.sqrt(sum(x * x for x in dv)) or 1.0
            argb = [e["speed"] * x / ln / 255 for x in dv]
            return [(d, round(argb[(d + 1) % 4], 6) + 0.0, e["influence"]) for d in range(4)]
        if t == "vec2":
            if len(eases) >= 2 and not p.get("spatial"):
                sx, sy = eases[0]["speed"], eases[1]["speed"]
                sp = self._frac_speed(math.hypot(sx, sy), [sx, sy])
                return [(None, sp, eases[0]["influence"])]
            e = eases[0]
            dv = [nb["value"][c] - k["value"][c] for c in range(2)] if nb else [1.0, 0.0]
            return [(None, self._frac_speed(e["speed"], dv), e["influence"])]
        if t == "vec3":
            return [(d, round(eases[min(d, len(eases) - 1)]["speed"], 6), eases[min(d, len(eases) - 1)]["influence"]) for d in range(3)]
        return []

    def _frac_speed(self, s, direction):
        """скорость вдоль пути: доли слоя/с -> пиксели/с (зависит от направления, если слой не квадратный)."""
        dx, dy = direction[0], direction[1]
        if self.origin is not None:
            W, H = self.comp
            ln = math.hypot(dx, dy)
            return round(s * (math.hypot(dx * W, dy * H) / ln if ln else (W + H) / 2), 6)
        if self.layer_size is None:
            if self.points == "px" or (self.points == "auto" and self.kind != "ffx" and abs(s) > 4):
                return round(s, 6)
            return {"$fracSpeed": s, "dir": [dx, dy]}
        W, H = self.layer_size
        ln = math.hypot(dx, dy)
        return round(s * (math.hypot(dx * W, dy * H) / ln if ln else (W + H) / 2), 6)

    # --- риг: параметры стороннего плагина живут на слое как Expression Controls
    CONTROL = {"slider": ("Slider Control", "slider", "Slider"), "float": ("Slider Control", "slider", "Slider"),
               "slider_int": ("Slider Control", "slider", "Slider"), "popup": ("Slider Control", "slider", "Slider"),
               "angle": ("Angle Control", "angle", "Angle"), "checkbox": ("Checkbox Control", "checkbox", "Checkbox"),
               "color": ("Color Control", "color", "Color"), "point": ("Point Control", "point", "Point"),
               "point3d": ("3D Point Control", None, "3D Point")}

    def rig(self, p):
        """Создаёт (один раз) контрол с именем «Эффект › Параметр» и переносит в него значение, ВСЕ ключи
        и выражение параметра 1:1. Возвращает JS-обращение к контролу для формул или None."""
        key = p["match"]
        if key in self.rigs:
            return self.rigs[key]
        spec = self.CONTROL.get(p["kind"])
        if spec is None:
            self.rigs[key] = None
            return None
        ec_name, pid, prop = spec
        entry = CAT[ec_name]
        q = dict(entry["params"][0] if pid is None else next(x for x in entry["params"] if x["id"] == pid))
        if p["kind"] == "popup":
            q.pop("min", None), q.pop("max", None)
        self.n += 1
        label = f"{self.e['name']} › {p.get('name') or p['match']}"
        if label in self.rig_names:  # одноимённые параметры в разных группах
            label += " · " + " / ".join(p.get("group") or [p["match"].rsplit("-", 1)[-1]])
        label = self.unique(label)
        self.X("effect.apply", effect=ec_name, layers=[self.layer], save=f"aefx{self.n}")
        self.X("prop.renameGroup", layer=self.layer, prop=f"$aefx{self.n}", name=label)
        self.expect.append({"layer": self.layer, "path": f"effects/#{self.n}", "name": label})
        path = f"effects/#{self.n}/{q['id']}"
        mark = len(self.ledger._cur["entries"])
        self.emit_direct(path, q, p, status="STORED", detail="контрол на слое; значение, ключи и выражение 1:1")
        entries = self.ledger._cur["entries"]
        ok = len(entries) > mark and entries[-1]["status"] == "STORED"
        if ok:
            entries[-1]["target"] = f"контрол «{label}»"
        acc = f'effect("{label}")("{prop}")' if ok else None
        self.rigs[key] = acc
        return acc

    def expr(self, path, q, template, note=""):
        """Выражение EffectCraft на параметре эмуляции. В шаблоне {Имя параметра AE} заменяется обращением
        к контролу-ригу (создаётся по требованию); {W}/{H} — размер слоя. Формула считается на сайте
        вживую, поэтому учитывает анимацию и выражения исходных параметров без запекания."""
        missing = []

        def rep(m):
            name = m.group(1)
            if name == "W":
                return "thisLayer.width"
            if name == "H":
                return "thisLayer.height"
            p = self.P(name)
            if p is None:
                missing.append(name)
                return "0"
            acc = self.rig(p)
            if acc is None:
                missing.append(name)
                return "0"
            self.mark(p, "DERIVED", note, path.split("/", 2)[-1], force=True)
            return acc
        code = re.sub(r"\{([^{}]+)\}", rep, template)
        for name in missing:
            self.ledger.note(f"формула ждала параметр «{name}», в файле его нет — подставлен 0")
        self.X("prop.setExpression", layer=self.layer, path=path, expression=code)
        self.expect.append({"layer": self.layer, "path": path, "type": q["type"], "expr": code})
        return code

    def mark(self, p, status, detail="", target=None, force=False):
        """строка учёта для параметра; потеря (LOSS/CLAMPED) никогда не затирается."""
        for x in self.ledger._cur["entries"]:
            if x["key"] == p["match"]:
                if x["status"] not in ("LOSS", "CLAMPED") and (force or status in ("LOSS", "CLAMPED")):
                    x.update(status=status, detail=detail or x["detail"])
                if target and target not in (x["target"] or ""):
                    x["target"] = f"{x['target']}, {target}" if x["target"] else target
                return
        self.ledger.add(p, status, detail, target)

    # --- эффект целиком
    def close(self, e):
        """Закрытие учёта: всё, что осталось необработанным, получает статус явно."""
        done = self.ledger.recorded()
        for p in e["params"]:
            if p["match"] in done:
                continue
            if p["kind"] in IR.STRUCTURAL:
                continue
            if p.get("index") == 0 and p["kind"] == "layer":
                continue  # параметр №0 — входной слой самого эффекта
            if p.get("unparsed"):
                self.ledger.add(p, "LOSS", "данные не разобраны; сохранены в IR сырыми")
            elif (IR.is_animated(p) or p.get("expr")) and self.rig_unused and self.rig(p):
                pass
            elif IR.is_animated(p) or p.get("expr"):
                self.ledger.add(p, "LOSS", "анимация/выражение без приёмника в EffectCraft")
            elif p["kind"] == "arbitrary":
                d = p.get("arb_default")
                if d is not None and p.get("arb", d) == d:
                    self.ledger.add(p, "DEFAULT")
                else:
                    self.ledger.add(p, "LOSS", f"произвольные данные плагина ({len(p.get('arb', '')) // 2} байт) без декодера")
            elif p["kind"] in ("layer", "path"):
                ref = (p.get("layer_ref") or {}).get("index", 0) if p["kind"] == "layer" else p.get("mask_ref", 0)
                if p["kind"] == "layer" and self.kind == "ffx":
                    ref = 0  # в пресете ссылки на слои не хранятся (там номер слоя из проекта автора)
                self.ledger.add(p, "DEFAULT" if not ref else "LOSS", "" if not ref else f"ссылка на слой/маску №{ref} без приёмника")
            else:
                d = IR.is_default(p)
                if d:
                    self.ledger.add(p, "DEFAULT")
                elif self.rig_unused and self.rig(p):
                    pass  # rig() записал STORED: данные на сайте есть, но эмуляция их не использует
                else:
                    v = ", ".join(f"{x:g}" for x in (p.get("value") or []))
                    df = ", ".join(f"{x:g}" for x in (p.get("default") or [])) if p.get("default") else "?"
                    self.ledger.add(p, "LOSS", f"нет приёмника: значение {v}, умолчание {df}")
        b = e.get("builtin") or {}
        if abs(b.get("effect_opacity", 100.0) - 100.0) > 1e-6 or b.get("effect_opacity_anim") or b.get("effect_opacity_expr"):
            self.ledger.add("Compositing Options › Effect Opacity", "LOSS",
                            f"{b.get('effect_opacity'):g}% — в EffectCraft 0.9.2 у эффектов нет Effect Opacity")
        if b.get("mask_refs"):
            self.ledger.add("Compositing Options › Masks", "LOSS", "маски эффекта: в EffectCraft 0.9.2 нет масок эффекта")
        if not e.get("enabled", True):
            self.ledger.note("эффект выключен в AE — выключен и здесь")

    def native(self, e, ec_name, how):
        self.ledger.begin(e, "native", ec_name)
        self.ledger.note(f"найден по: {how}")
        first = self.n + 1
        tg = Target(self, ec_name, name=e["name"])
        claimed = set()
        if ec_name in SPECIAL:
            claimed = SPECIAL[ec_name](self, tg, e) or set()
        self.transfer_matching(tg, e, claimed)
        self._finish(e, first)

    def transfer_matching(self, tg, e, claimed=()):
        """Все параметры AE, у которых есть одноимённый параметр в эффекте EffectCraft, — как есть."""
        ec_name = tg.name
        pairs, _ = match_params([p for p in e["params"] if p["match"] not in claimed], tg.entry["params"])
        for p in e["params"]:
            q = pairs.get(p["match"])
            if q is None or p["match"] in claimed:
                continue
            path = f"{tg.path}/{q['id']}"
            key = (ec_name, q["id"])
            if key in CALIB_EXPR and q["type"] == "scalar":
                code, why = CALIB_EXPR[key]
                if p.get("expr"):
                    self.emit_direct(path, q, p)
                    self.mark(p, "LOSS", "на параметре своё выражение — формула калибровки не применена: " + why, force=True)
                else:
                    self.emit_direct(path, q, p, status="CALIBRATED", detail=why)
                    code = code.replace("{self}", f'effect("{tg.label}")')
                    self.X("prop.setExpression", layer=self.layer, path=path, expression=code)
                    self.expect.append({"layer": self.layer, "path": path, "type": q["type"], "expr": code})
            elif key in CALIB_AFFINE:
                a, b, why = CALIB_AFFINE[key]
                self.emit_direct(path, q, p, a, b, status="CALIBRATED", detail=why)
            elif key in UNIT_FIX or (p.get("percent") and p.get("max") == 255 and q.get("max") == 100):
                a, b = UNIT_FIX.get(key, (100.0 / 255.0, 0.0))
                self.emit_direct(path, q, p, a, b, status="CONVERTED", detail="0–255 → проценты")
            else:
                self.emit_direct(path, q, p)

    def _finish(self, e, first):
        if not e.get("enabled", True):
            for i in range(first, self.n + 1):
                self.X("effect.toggle", layer=self.layer, effect=i, value=False)
        self.close(e)

    def unsupported(self, e):
        self.ledger.begin(e, "none", None)
        self.ledger.note("эффекта нет в EffectCraft и для него нет рецепта эмуляции; плагины (.wasm) в веб-сборке 0.9.2 "
                         "не загружаются. Параметры сохранены на слое контролами (и целиком — в IR), на картинку не влияют.")
        self.e, self.rigs = e, {}
        self.rig_unused = True
        self.close(e)
        self.rig_unused = False


SPECIAL = {}     # эффект EffectCraft -> функция, забирающая параметры со своей логикой (arb-декодеры)
RECIPES = {}     # match name стороннего плагина -> функция-рецепт


def special(name):
    def deco(f):
        SPECIAL[name] = f
        return f
    return deco


def recipe(*matches):
    def deco(f):
        for m in matches:
            RECIPES[m] = f
        return f
    return deco


def first_time(ir):
    ts = [k["t"] for e in ir["effects"] for p in e["params"] if IR.is_animated(p) for k in p["anim"]["keys"]]
    return min(ts) if ts else 0.0


def convert(ir, layer="$cur", t0=0.0, comp=(1080, 1080), fps=30.0, t_base=None, **kw):
    """IR (aefx.ir.load) -> Converter со steps, ledger, expect.

    t_base: секунда файла, принятая за t0 (по умолчанию — первый ключ, как при применении пресета в AE).
    """
    from . import decoders, thirdparty  # noqa: F401  (регистрируют SPECIAL и RECIPES)
    c = Converter(layer=layer, t0=t0, comp=comp, fps=fps, source_kind=ir.get("kind", "ffx"), **kw)
    c.ledger = Ledger(ir.get("source", ""))
    c.base = first_time(ir) if t_base is None else t_base
    for e in ir["effects"]:
        c.e = e
        ec, how = resolve_effect(e)
        if ec:
            c.native(e, ec, how)
        elif e["match"] in RECIPES:
            first = c.n + 1
            c.cur_targets, c.rigs = [], {}
            c.ledger.begin(e, "recipe", None)
            RECIPES[e["match"]](c, e)
            c.ledger._cur["target"] = " + ".join(c.cur_targets) or None
            c.rig_unused = True
            c._finish(e, first)
            c.rig_unused = False
        else:
            c.unsupported(e)
    return c
