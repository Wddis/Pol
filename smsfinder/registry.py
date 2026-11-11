"""Реестр выданных номеров: один и тот же номер не выдаётся повторно."""
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


def is_used(number: str, service: str) -> bool:
    if not number:
        return False
    rec = _load().get(number)
    return bool(rec) and (service in rec.get("services", []) or "*" in rec.get("services", []))


def mark_used(number: str, service: str, provider: str) -> None:
    if not number:
        return
    with _lock:
        data = _load()
        rec = data.setdefault(number, {"services": [], "provider": provider})
        if service not in rec["services"]:
            rec["services"].append(service)
        rec["last"] = int(time.time())
        PATH.parent.mkdir(parents=True, exist_ok=True)
        PATH.write_text(json.dumps(data, ensure_ascii=False, indent=1))


def count() -> int:
    return len(_load())
