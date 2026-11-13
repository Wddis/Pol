"""Реестр номеров: выданные, остановленные и не подошедшие номера больше не показываются.

Запись: {services: [...], provider, last, blocked: bool, reason, feedback: [{ok, reason, service, at}]}
"""
from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path

PATH = Path(os.getenv("SMSFINDER_DATA", Path.home() / ".smsfinder")) / "used.json"
_lock = threading.Lock()


def _load() -> dict:
    try:
        return json.loads(PATH.read_text())
    except (OSError, ValueError):
        return {}


def _write(data: dict):
    PATH.parent.mkdir(parents=True, exist_ok=True)
    PATH.write_text(json.dumps(data, ensure_ascii=False, indent=1))


def _update(number: str, fn):
    with _lock:
        data = _load()
        rec = data.setdefault(number, {"services": []})
        fn(rec)
        rec["last"] = int(time.time())
        _write(data)


def is_blocked(number: str) -> bool:
    return bool(number) and bool(_load().get(number, {}).get("blocked"))


def is_used(number: str, service: str) -> bool:
    """Номер не показывать: заблокирован или уже выдавался для этого сервиса."""
    if not number:
        return False
    rec = _load().get(number)
    if not rec:
        return False
    return bool(rec.get("blocked")) or service in rec.get("services", []) or "*" in rec.get("services", [])


def mark_used(number: str, service: str, provider: str) -> None:
    if not number:
        return

    def f(rec):
        rec["provider"] = provider
        if service not in rec["services"]:
            rec["services"].append(service)
    _update(number, f)


def block(number: str, reason: str) -> None:
    """Номер остановлен / не подошёл — больше не выдавать никогда."""
    if not number:
        return

    def f(rec):
        rec["blocked"] = True
        rec["reason"] = reason
    _update(number, f)


def feedback(number: str, service: str, ok: bool, reason: str = "") -> None:
    def f(rec):
        rec.setdefault("feedback", []).append({"ok": ok, "reason": reason, "service": service,
                                                "at": int(time.time())})
        if not ok:
            rec["blocked"], rec["reason"] = True, reason or "не подошёл"
    _update(number, f)
    from . import quality
    quality.add(number, service, ok, "user")


def range_of(number: str) -> str:
    """Диапазон номера: всё, кроме трёх последних цифр (+447454712483 → +447454712)."""
    return (number or "")[:-3]


def banned_ranges(service: str = "telegram") -> dict:
    """{диапазон: сколько номеров из него пользователи отметили «заблокирован»/«Invalid»}."""
    out: dict[str, int] = {}
    for num, r in _load().items():
        if any(not f["ok"] and f.get("service") == service and
               any(k in (f.get("reason") or "").lower() for k in ("заблокир", "banned", "invalid"))
               for f in r.get("feedback", [])):
            out[range_of(num)] = out.get(range_of(num), 0) + 1
    return out


# Страны, где Telegram шлёт SMS только платно (auth.sentCodePaymentRequired, «купите Premium»).
# TW — проверено на практике; остальные добавляются отзывами пользователей.
PAY_SMS_REGIONS = {"telegram": {"TW"}}


def pay_regions(service: str = "telegram") -> set[str]:
    """Страны, где сервис просит оплату за SMS: известные + из отзывов «просит оплату»."""
    from .numcheck import info
    out = set(PAY_SMS_REGIONS.get(service, set()))
    for num, r in _load().items():
        if any(not f["ok"] and f.get("service") == service and "оплат" in (f.get("reason") or "").lower()
               for f in r.get("feedback", [])):
            reg = info(num)["region"]
            if reg:
                out.add(reg)
    return out


def stats() -> dict:
    data = _load()
    fb = [f for r in data.values() for f in r.get("feedback", [])]
    return {"total": len(data), "blocked": sum(1 for r in data.values() if r.get("blocked")),
            "worked": sum(1 for f in fb if f["ok"]), "failed": sum(1 for f in fb if not f["ok"])}


def count() -> int:
    return len(_load())
