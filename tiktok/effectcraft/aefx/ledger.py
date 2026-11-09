"""Слой учёта: ни один параметр исходного эффекта не может исчезнуть без записи.

Каждый параметр каждого эффекта получает ровно один статус. Конвертер обязан закрыть учёт:
параметр, который никто не обработал, автоматически становится LOSS (если его значение
отличается от умолчания плагина, анимировано или задано выражением).
"""

# статус -> (класс, пояснение)
STATUS = {
    "EXACT":      ("ok",     "перенесён 1:1 в одноимённый параметр EffectCraft"),
    "CONVERTED":  ("ok",     "перенесён с пересчётом единиц (цвет, список, точка, диапазон)"),
    "DECODED":    ("ok",     "произвольные данные (arb) расшифрованы и перенесены"),
    "CALIBRATED": ("ok",     "перенесён по формуле калибровки (EffectCraft считает иначе, чем AE)"),
    "DERIVED":    ("approx", "использован в сборке-эмуляции стороннего плагина"),
    "STORED":     ("loss",   "сохранён на слое контролом (значение/ключи 1:1), но на картинку не влияет: эмуляция его не использует"),
    "DEFAULT":    ("ok",     "нет аналога, но значение равно умолчанию плагина"),
    "INERT":      ("ok",     "не влияет на картинку (кнопка, группа, служебный вид, выключенная ветка)"),
    "CLAMPED":    ("loss",   "значение вне диапазона EffectCraft — будет обрезано"),
    "LOSS":       ("loss",   "НЕ перенесён"),
}
FIDELITY = {"native": "1:1 (тот же эффект в EffectCraft)", "recipe": "эмуляция (сборка из встроенных)",
            "none": "не перенесён"}


class Ledger:
    def __init__(self, source=""):
        self.source = source
        self.effects = []   # {name, match, fidelity, target, entries: [...], notes: [...]}
        self._cur = None

    # --- запись
    def begin(self, e, fidelity, target):
        self._cur = {"name": e.get("name"), "match": e.get("match"), "order": e.get("order"),
                     "fidelity": fidelity, "target": target, "entries": [], "notes": []}
        self.effects.append(self._cur)
        return self._cur

    def add(self, param, status, detail="", target=None):
        assert status in STATUS, status
        name = param if isinstance(param, str) else (param.get("name") or param.get("match"))
        key = param if isinstance(param, str) else param["match"]  # match name уникален, имена повторяются
        if not isinstance(param, str) and param.get("group"):
            name = " / ".join(param["group"]) + " / " + name
        self._cur["entries"].append({"key": key, "param": name, "status": status, "detail": detail, "target": target})

    def note(self, text):
        self._cur["notes"].append(text)

    def recorded(self):
        return {x["key"] for x in self._cur["entries"]}

    # --- итоги
    def counts(self):
        c = {k: 0 for k in STATUS}
        for e in self.effects:
            for x in e["entries"]:
                c[x["status"]] += 1
        return c

    def by_class(self):
        c = {"ok": 0, "approx": 0, "loss": 0}
        for k, n in self.counts().items():
            c[STATUS[k][0]] += n
        return c

    def losses(self):
        return [(e, x) for e in self.effects for x in e["entries"] if STATUS[x["status"]][0] == "loss"]

    def lossless(self):
        return not self.losses()

    def exact(self):
        """Перенос без потерь И без эмуляций."""
        return self.lossless() and all(e["fidelity"] == "native" for e in self.effects)

    def to_json(self):
        return {"source": self.source, "lossless": self.lossless(), "exact": self.exact(),
                "counts": self.counts(), "classes": self.by_class(), "effects": self.effects}

    def report(self, verbose=False):
        cl = self.by_class()
        total = sum(cl.values()) or 1
        out = [f"ОТЧЁТ ПЕРЕНОСА: {self.source}",
               f"  параметров учтено: {total} | без потерь: {cl['ok']} | эмуляция: {cl['approx']} | ПОТЕРЯ: {cl['loss']}",
               "  вердикт: " + ("ПОЛНЫЙ ПЕРЕНОС 1:1" if self.exact() else
                                "без потерь параметров, но часть эффектов — эмуляция" if self.lossless() else
                                "ЕСТЬ ПОТЕРИ — см. строки LOSS / CLAMPED / STORED"), ""]
        for e in self.effects:
            n = {"ok": 0, "approx": 0, "loss": 0}
            for x in e["entries"]:
                n[STATUS[x["status"]][0]] += 1
            out.append(f"[{e['order']}] {e['name']} <{e['match']}> → {e['target'] or '—'}  ·  {FIDELITY[e['fidelity']]}"
                       f"  ·  ok {n['ok']}, эмуляция {n['approx']}, потеря {n['loss']}")
            for t in e["notes"]:
                out.append(f"      · {t}")
            for x in e["entries"]:
                cls = STATUS[x["status"]][0]
                if cls == "loss" or verbose:
                    tgt = f" → {x['target']}" if x["target"] else ""
                    out.append(f"      {x['status']:<10} {x['param']}{tgt}" + (f"  ({x['detail']})" if x["detail"] else ""))
        return "\n".join(out)
