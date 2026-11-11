"""Проверка доставкой: на номер отправляется SMS со случайным кодом, и скрипт ждёт,
пока это SMS появится во входящих номера. Дошло — номер получает статус «проверенный».

Результаты кэшируются (verified.json), чтобы не тратить квоту отправки повторно.
"""
from __future__ import annotations

import json
import os
import secrets
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

from . import sender
from .core import Candidate, Emit, noop_emit

PATH = Path(os.getenv("SMSFINDER_DATA", Path.home() / ".smsfinder")) / "verified.json"
TTL = int(os.getenv("SMSFINDER_VERIFY_TTL", 6 * 3600))  # сколько действует статус «проверенный»
_lock = threading.Lock()


def _load() -> dict:
    try:
        return json.loads(PATH.read_text())
    except (OSError, ValueError):
        return {}


def cached(number: str) -> Optional[dict]:
    rec = _load().get(number)
    if rec and rec.get("ok") and time.time() - rec.get("at", 0) < TTL:
        return rec
    return None


def _save(number: str, rec: dict):
    with _lock:
        data = _load()
        data[number] = rec
        PATH.parent.mkdir(parents=True, exist_ok=True)
        PATH.write_text(json.dumps(data, ensure_ascii=False, indent=1))


def mark(cand: Candidate, rec: dict):
    """Перевести кандидата в категорию «проверенный»."""
    cand.tier = "verified"
    cand.extra["verified"] = rec
    cand.score = round(cand.score + 50, 1)
    ago = int((time.time() - rec["at"]) // 60)
    cand.note = (f"✅ тестовое SMS дошло за {rec['delivered_s']} с ({rec['via']}, {ago} мин назад) · " + cand.note)


class Verification:
    def __init__(self, engine, cand: Candidate, wait: int = 180, emit: Emit = noop_emit):
        self.id = uuid.uuid4().hex[:12]
        self.engine, self.cand, self.wait, self.emit = engine, cand, wait, emit
        self.provider = engine.providers[cand.provider]
        self.token = f"{secrets.randbelow(900000) + 100000}"
        self.text = f"SMSFinder check {self.token}"
        self.status = "pending"   # pending → sent → verified | failed
        self.detail = ""
        self.started = time.time()
        self.sent_at: Optional[float] = None
        self.delivered_s: Optional[int] = None
        self.via = ""

    def to_dict(self) -> dict:
        return {"id": self.id, "cand_id": self.cand.id, "number": self.cand.number, "status": self.status,
                "detail": self.detail, "token": self.token, "via": self.via,
                "delivered_s": self.delivered_s, "elapsed": int(time.time() - self.started),
                "wait": self.wait, "candidate": self.cand.to_dict()}

    def _say(self, ok, reason):
        self.cand.log(f"проверка SMS: {reason}")
        self.emit({"type": "verify", "provider": self.cand.provider, "number": self.cand.number,
                   "ok": ok, "reason": reason, "status": self.status})

    def run(self) -> "Verification":
        c = self.cand
        rec = cached(c.number)
        if rec:
            self.status, self.delivered_s, self.via = "verified", rec["delivered_s"], rec["via"]
            mark(c, rec)
            self._say(True, f"уже проверен ранее (SMS дошло за {rec['delivered_s']} с)")
            return self
        try:  # всё, что уже лежит во входящих, — не наше
            baseline = {m.key for m in self.provider.messages({"ref": c.ref, "number": c.number})}
        except Exception:
            baseline = set(c.extra.get("keys") or [])
        res = sender.send(c.number, self.text)
        self.via = res.via
        if not res.ok:
            self.status, self.detail = "failed", f"не удалось отправить: {res.error}"
            self._say(False, self.detail)
            return self
        self.sent_at, self.status = time.time(), "sent"
        self._say(True, f"отправлено тестовое SMS с кодом {self.token} через {res.via}"
                        + (f" (квота: {res.quota})" if res.quota is not None else ""))
        while time.time() - self.sent_at < self.wait:
            time.sleep(5)
            try:
                msgs = self.provider.messages({"ref": c.ref, "number": c.number})
            except Exception:
                continue
            if any(self.token in m.text for m in msgs if m.key not in baseline):
                self.delivered_s = int(time.time() - self.sent_at)
                self.status = "verified"
                rec = {"ok": True, "at": time.time(), "delivered_s": self.delivered_s, "via": res.via,
                       "provider": c.provider}
                _save(c.number, rec)
                mark(c, rec)
                self._say(True, f"SMS дошло за {self.delivered_s} с → статус «проверенный»")
                return self
        self.status = "failed"
        self.detail = f"тестовое SMS не дошло за {self.wait} с"
        c.extra["verify_failed"] = True
        c.score = round(c.score - 40, 1)
        c.note = f"⚠ {self.detail} · " + c.note
        _save(c.number, {"ok": False, "at": time.time(), "via": res.via, "provider": c.provider})
        self._say(False, self.detail)
        return self

    def start(self) -> "Verification":
        threading.Thread(target=self.run, daemon=True).start()
        return self
