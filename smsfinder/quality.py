"""Надёжность номера: процент успешных проверок под сервис — категория «надёжные 80%+».

Раньше номер становился «проверенным» после ОДНОГО SMS от сервиса, а неудачные проверки
нигде не запоминались. Поэтому номер, который сработал один раз из десяти, выглядел так же,
как номер, работающий всегда. Здесь копится история проверок, и по ней считается процент.

Что считается проверкой (испытанием) номера под сервис:
  test   — тест доставки при подборе: SMS от сервиса пришло (успех) или нет (неудача);
  window — окно фонового наблюдения (WINDOW, по умолчанию 1 ч): за окно пришло SMS
           от сервиса — успех, не пришло — неудача;
  user   — отзыв пользователя «код подошёл» / «не подошёл».
Для «любого сервиса» (other) успех — любое новое SMS.

Номер попадает в «надёжные», если за последние HORIZON (7 дней) у него не меньше MIN_TRIALS
проверок под этот сервис и доля успешных — не ниже THRESHOLD (80%). Платные номера (5sim)
сами сообщают % доставки по оператору — для них берётся он.

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
THRESHOLD = float(os.getenv("SMSFINDER_RELIABLE", 0.8))
MIN_TRIALS = int(os.getenv("SMSFINDER_RELIABLE_MIN", 5))
HORIZON = int(os.getenv("SMSFINDER_RELIABLE_DAYS", 7)) * 86400
WINDOW = int(os.getenv("SMSFINDER_WINDOW", 3600))
MAX_TRIALS = 60  # на номер и сервис храним только последние проверки
KINDS = {"test": "тест доставки", "window": "окно наблюдения", "user": "отзыв"}

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
    """{n, ok, rate, reliable, by_kind, last_ok_at} за последние HORIZON секунд."""
    cutoff = time.time() - HORIZON
    trials = [t for t in _load().get(number, {}).get(_norm(service), []) if t[0] >= cutoff]
    n, ok = len(trials), sum(t[1] for t in trials)
    by_kind: dict[str, list[int]] = {}
    for _, good, kind in trials:
        k = by_kind.setdefault(kind, [0, 0])
        k[0] += good
        k[1] += 1
    rate = ok / n if n else None
    return {"n": n, "ok": ok, "rate": rate, "reliable": n >= MIN_TRIALS and rate is not None and rate >= THRESHOLD,
            "by_kind": by_kind, "last_ok_at": max((t[0] for t in trials if t[1]), default=None)}


def describe(st: dict) -> str:
    if not st["n"]:
        return "надёжность: ещё не измерялась"
    parts = ", ".join(f"{KINDS.get(k, k)} {g}/{t}" for k, (g, t) in st["by_kind"].items())
    pct = round(st["rate"] * 100)
    if st["n"] < MIN_TRIALS:
        return f"надёжность: {pct}% по {st['n']} проверкам — мало данных, нужно ≥{MIN_TRIALS} ({parts})"
    return f"надёжность {pct}% ({st['ok']} из {st['n']} проверок за {HORIZON // 86400} дн: {parts})"


def annotate(cand, service: str) -> dict:
    """Записать в кандидата процент работоспособности под сервис (extra['quality'])."""
    if cand.public:
        st = stats(cand.number, service)
        q = {"rate": st["rate"], "n": st["n"], "ok": st["ok"], "reliable": st["reliable"],
             "source": "наблюдение", "text": describe(st)}
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
