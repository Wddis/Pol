"""Надёжность номера: процент успешных проверок под сервис — категория «надёжные 95%+».

Раньше номер становился «проверенным» после ОДНОГО SMS от сервиса, а неудачные проверки
нигде не запоминались. Поэтому номер, который сработал один раз из десяти, выглядел так же,
как номер, работающий всегда. Здесь копится история проверок, и по ней считается процент.

Что считается проверкой (испытанием) номера:
  под сервис (Telegram и т.п.) — только РЕАЛЬНЫЕ попытки:
    user       — отзыв «код подошёл» / «не подошёл» (бан, Invalid, чужой аккаунт, код не пришёл);
    activation — номер взяли, ждали код, и он не пришёл до таймаута.
  под «любой сервис» (other) — живость номера:
    window — окно фонового наблюдения (WINDOW, 1 ч): пришло ли хоть одно новое SMS;
    test   — тест доставки при подборе.

Почему так: проверка на практике показала, что «пришёл ли код Telegram за час» измеряет,
как часто ДРУГИЕ запрашивают код на номер, а не работает ли он: у лучшего номера коды были
в 38% часов. А отсутствие кодов на живом номере чаще означает бан или чужой аккаунт
(код уходит в приложение/на почту), чем «чистый» номер.

Номер попадает в «надёжные», если за последние HORIZON (7 дней) под этот сервис:
  * не меньше MIN_TRIALS проверок;
  * взвешенная доля успешных не ниже THRESHOLD (95%). Вес проверки падает вдвое каждые
    HALF_LIFE (48 ч): номер, который неделю назад работал, а вчера начал сбоить, теряет процент
    быстро, а не ждёт, пока старые успехи выйдут за 7 дней;
  * и простая доля успешных тоже не ниже THRESHOLD;
  * последние STREAK проверок подряд — успешные (номер не «умер» прямо сейчас).
Данные libphonenumber (тип линии, оператор) показываются рядом, но на категорию не влияют:
решает реальная доставка, а база отстаёт от новых диапазонов.
Платные номера (5sim) сами сообщают % доставки по оператору — для них берётся он.

quality.json: {номер: {сервис: [[время, 1|0, вид], ...]}}
"""
from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path
from typing import Optional

PATH = Path(os.getenv("SMSFINDER_DATA", Path.home() / ".smsfinder")) / "quality.json"
THRESHOLD = float(os.getenv("SMSFINDER_RELIABLE", 0.95))
MIN_TRIALS = int(os.getenv("SMSFINDER_RELIABLE_MIN", 5))
HORIZON = int(os.getenv("SMSFINDER_RELIABLE_DAYS", 7)) * 86400
WINDOW = int(os.getenv("SMSFINDER_WINDOW", 3600))
HALF_LIFE = int(os.getenv("SMSFINDER_HALF_LIFE_H", 48)) * 3600
STREAK = int(os.getenv("SMSFINDER_STREAK", 2))
MAX_TRIALS = 60  # на номер и сервис храним только последние проверки
KINDS = {"test": "тест доставки", "window": "окно наблюдения", "user": "отзыв",
         "activation": "код не пришёл после выдачи"}

_lock = threading.Lock()
_cache: Optional[dict] = None


def _norm(service: str) -> str:
    return (service or "other").lower().strip() or "other"


def _load() -> dict:
    global _cache
    if _cache is None:
        try:
            _cache = json.loads(PATH.read_text())
        except (OSError, ValueError):
            _cache = {}
    return _cache


def _write(data: dict):
    PATH.parent.mkdir(parents=True, exist_ok=True)
    tmp = PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False))
    tmp.replace(PATH)


def reset_cache():
    """Перечитать файл с диска (для тестов и смены SMSFINDER_DATA)."""
    global _cache
    with _lock:
        _cache = None


def add(number: str, service: str, ok: bool, kind: str, at: Optional[float] = None) -> None:
    """Записать результат одной проверки номера под сервис."""
    if not number:
        return
    now = at or time.time()
    with _lock:
        data = _load()
        trials = data.setdefault(number, {}).setdefault(_norm(service), [])
        trials.append([int(now), 1 if ok else 0, kind])
        cutoff = time.time() - HORIZON
        trials[:] = [t for t in trials if t[0] >= cutoff][-MAX_TRIALS:]
        _write(data)


def services_of(number: str) -> set[str]:
    """Сервисы, по которым у номера уже есть история."""
    return set(_load().get(number, {}))


def forget(number: str) -> None:
    with _lock:
        data = _load()
        if data.pop(number, None) is not None:
            _write(data)


def stats(number: str, service: str = "other") -> dict:
    """{n, ok, rate (взвешенная), raw_rate, streak, reliable, why, by_kind, last_ok_at} за HORIZON."""
    now = time.time()
    trials = sorted((t for t in _load().get(number, {}).get(_norm(service), []) if t[0] >= now - HORIZON),
                    key=lambda t: t[0])
    n, ok = len(trials), sum(t[1] for t in trials)
    by_kind: dict[str, list[int]] = {}
    wsum = wok = 0.0
    for at, good, kind in trials:
        k = by_kind.setdefault(kind, [0, 0])
        k[0] += good
        k[1] += 1
        w = 0.5 ** ((now - at) / HALF_LIFE)
        wsum += w
        wok += w * good
    streak = 0
    for t in reversed(trials):
        if not t[1]:
            break
        streak += 1
    raw = ok / n if n else None
    rate = wok / wsum if wsum else None
    why = ""
    if n < MIN_TRIALS:
        why = f"мало проверок ({n} из {MIN_TRIALS})"
    elif streak < STREAK:
        why = ("последняя проверка неудачная — номер, возможно, перестал работать" if streak == 0
               else f"подряд успешных только {streak} из нужных {STREAK}")
    elif rate < THRESHOLD or raw < THRESHOLD:
        why = f"успешных {int(min(rate, raw) * 100)}% < {round(THRESHOLD * 100)}%"
    return {"n": n, "ok": ok, "rate": rate, "raw_rate": raw, "streak": streak, "reliable": not why,
            "why": why, "by_kind": by_kind, "last_ok_at": max((t[0] for t in trials if t[1]), default=None)}


def describe(st: dict) -> str:
    if not st["n"]:
        return "надёжность: ещё не измерялась"
    parts = ", ".join(f"{KINDS.get(k, k)} {g}/{t}" for k, (g, t) in st["by_kind"].items())
    pct = int(st["rate"] * 100 + 1e-9)  # вниз: 94,9% не должно выглядеть как 95%
    if st["n"] < MIN_TRIALS:
        return f"надёжность: {pct}% по {st['n']} проверкам — мало данных, нужно ≥{MIN_TRIALS} ({parts})"
    tail = "" if st["reliable"] else f" · не в «надёжных»: {st['why']}"
    return f"надёжность {pct}% ({st['ok']} из {st['n']} проверок за {HORIZON // 86400} дн, " \
           f"свежие весят больше: {parts}){tail}"


def annotate(cand, service: str) -> dict:
    """Записать в кандидата процент работоспособности под сервис (extra['quality'])."""
    from . import numcheck
    from .services import profile
    if cand.public:
        st = stats(cand.number, service)
        line_ok, line_why, li = numcheck.verdict(cand.number, bool(profile(service).get("strict")))
        text = describe(st)
        if not line_ok:
            text += f" · ⚠ по базе libphonenumber: {line_why}"
        q = {"rate": st["rate"], "n": st["n"], "ok": st["ok"], "reliable": st["reliable"],
             "streak": st["streak"], "why": st["why"] or line_why, "line": numcheck.label(li),
             "source": "наблюдение", "text": text}
    else:  # платный номер: % доставки по оператору сообщает сам провайдер
        r = cand.extra.get("rate")
        rate = r / 100 if isinstance(r, (int, float)) else None
        q = {"rate": rate, "n": None, "ok": None, "reliable": rate is not None and rate >= THRESHOLD,
             "source": "провайдер",
             "text": f"доставка {round(rate * 100)}% по данным провайдера" if rate is not None
             else "% доставки провайдер не сообщает"}
    cand.extra["quality"] = q
    return q


def is_reliable(cand, service: str) -> bool:
    return annotate(cand, service)["reliable"]
