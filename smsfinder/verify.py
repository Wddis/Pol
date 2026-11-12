"""Подтверждение доставки SMS — категория «проверенный».

Два способа:
  passive (по умолчанию, бесплатно) — номер ставится под наблюдение; если во время
      наблюдения во входящие приходит НОВОЕ SMS (которого не было в начале), значит
      SMS доходят до номера прямо сейчас. Подход как в
      github.com/LayorX/Temporary-SMS-Receiver-Monitor, но с доказательством: фиксируем
      отпечаток нового SMS, отправителя и время с начала наблюдения.
  active — на номер отправляется SMS со случайным кодом через свой шлюз
      (textbee / httpSMS / Twilio / Textbelt, см. sender.py) и ищется во входящих.

Плюс фоновый «пул проверенных» (Watcher): постоянно наблюдает лучшие номера и
переводит в «проверенные» те, на которые пришло новое SMS.

Статус хранится в verified.json и действует SMSFINDER_VERIFY_TTL секунд (6 ч).
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
from .core import Candidate, Emit, Sms, noop_emit

PATH = Path(os.getenv("SMSFINDER_DATA", Path.home() / ".smsfinder")) / "verified.json"
TTL = int(os.getenv("SMSFINDER_VERIFY_TTL", 6 * 3600))
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


def invalidate(number: str):
    """Снять статус «проверенный» (номер остановлен или не подошёл)."""
    with _lock:
        data = _load()
        if data.pop(number, None) is not None:
            _write(data)


def _write(data: dict):
    PATH.parent.mkdir(parents=True, exist_ok=True)
    PATH.write_text(json.dumps(data, ensure_ascii=False, indent=1))


def mark(cand: Candidate, rec: dict):
    """Перевести кандидата в категорию «проверенный»."""
    if cand.tier != "verified":
        cand.tier = "verified"
        cand.score = round(cand.score + 50, 1)
        cand.extra["base_note"] = cand.note
    cand.extra["verified"] = rec
    ago = int((time.time() - rec["at"]) // 60)
    if rec.get("kind") == "passive":
        how = f"новое SMS от {rec.get('sender', '?')} через {rec['delivered_s']} с наблюдения"
    else:
        how = f"тестовое SMS дошло за {rec['delivered_s']} с ({rec['via']})"
    cand.note = f"✅ доставка подтверждена: {how}, {ago} мин назад" + (
        f" · {cand.extra['base_note']}" if cand.extra.get("base_note") else "")


def record_passive(cand: Candidate, sms: Sms, observed_s: int) -> dict:
    """Зафиксировать доказательство доставки: новое SMS, пришедшее во время наблюдения."""
    rec = {"ok": True, "at": time.time(), "kind": "passive", "via": "входящий трафик",
           "delivered_s": observed_s, "sender": sms.sender[:30], "proof": sms.key[:12],
           "provider": cand.provider, "ref": cand.ref, "country": cand.country}
    _save(cand.number, rec)
    mark(cand, rec)
    return rec


def _msgs(provider, cand) -> list[Sms]:
    return provider.messages({"ref": cand.ref, "number": cand.number})


class Verification:
    def __init__(self, engine, cand: Candidate, wait: int = 180, emit: Emit = noop_emit, method: str = "auto"):
        self.id = uuid.uuid4().hex[:12]
        self.engine, self.cand, self.wait, self.emit = engine, cand, wait, emit
        self.provider = engine.providers[cand.provider]
        if method == "auto":
            method = "active" if not sender.describe()["free"] else "passive"
        self.method = method
        self.token = f"{secrets.randbelow(900000) + 100000}" if method == "active" else ""
        self.status = "pending"   # pending → sent|watching → verified | failed
        self.detail = ""
        self.started = time.time()
        self.delivered_s: Optional[int] = None
        self.via = ""

    def to_dict(self) -> dict:
        return {"id": self.id, "cand_id": self.cand.id, "number": self.cand.number, "status": self.status,
                "method": self.method, "detail": self.detail, "token": self.token, "via": self.via,
                "delivered_s": self.delivered_s, "elapsed": int(time.time() - self.started),
                "wait": self.wait, "candidate": self.cand.to_dict()}

    def _say(self, ok, reason):
        self.cand.log(f"проверка доставки: {reason}")
        self.emit({"type": "verify", "provider": self.cand.provider, "number": self.cand.number,
                   "ok": ok, "reason": reason, "status": self.status})

    def _fail(self, detail):
        self.status, self.detail = "failed", detail
        self.cand.extra["verify_failed"] = True
        self.cand.score = round(self.cand.score - 40, 1)
        self.cand.note = f"⚠ {detail} · " + self.cand.note
        self._say(False, detail)
        return self

    def run(self) -> "Verification":
        c = self.cand
        rec = cached(c.number)
        if rec:
            self.status, self.delivered_s, self.via = "verified", rec["delivered_s"], rec["via"]
            mark(c, rec)
            self._say(True, "уже проверен ранее")
            return self
        try:  # всё, что уже лежит во входящих, — не в счёт
            baseline = {m.key for m in _msgs(self.provider, c)}
        except Exception:
            baseline = set(c.extra.get("keys") or [])
        t0 = time.time()

        if self.method == "active":
            res = sender.send(c.number, f"SMSFinder check {self.token}")
            self.via = res.via
            if not res.ok:
                # шлюз не отправил — переходим на пассивное подтверждение
                self._say(False, f"шлюз {res.via} не отправил ({res.error}) → пассивная проверка")
                self.method, self.token = "passive", ""
            else:
                self.status = "sent"
                self._say(True, f"отправлено SMS с кодом {self.token} через {res.via}")
        if self.method == "passive":
            self.status, self.via = "watching", "входящий трафик"
            self._say(True, f"наблюдение: жду новое входящее SMS до {self.wait} с")

        while time.time() - t0 < self.wait:
            time.sleep(5 if self.method == "active" else 15)
            try:
                msgs = _msgs(self.provider, c)
            except Exception:
                continue
            new = [m for m in msgs if m.key not in baseline]
            hit = [m for m in new if self.token and self.token in m.text] if self.method == "active" else new
            if hit:
                self.delivered_s = int(time.time() - t0)
                self.status = "verified"
                if self.method == "active":
                    rec = {"ok": True, "at": time.time(), "kind": "active", "delivered_s": self.delivered_s,
                           "via": self.via, "provider": c.provider}
                    _save(c.number, rec)
                    mark(c, rec)
                else:
                    record_passive(c, hit[0], self.delivered_s)
                self._say(True, f"SMS дошло ({hit[0].sender}) через {self.delivered_s} с → «проверенный»")
                return self
        return self._fail(("тестовое SMS не дошло" if self.method == "active" else "новых SMS не было")
                          + f" за {self.wait} с")

    def start(self) -> "Verification":
        threading.Thread(target=self.run, daemon=True).start()
        return self


class Watcher:
    """Фоновый пул: постоянно наблюдает лучшие номера и пополняет «проверенные»."""

    def __init__(self, engine, size: int = 24, cycle: int = 60, refresh: int = 900):
        self.engine, self.size, self.cycle, self.refresh = engine, size, cycle, refresh
        self.watch: dict[str, dict] = {}   # number -> {cand, baseline, since}
        self.events: list[str] = []
        self.last_refresh = 0.0
        self.started = time.time()
        self.running = False

    def log(self, msg):
        self.events.append(f"{time.strftime('%H:%M:%S')} {msg}")
        del self.events[:-50]

    def _refill(self):
        try:
            res = self.engine.find("other", "", None, self.size, "popular", 0, track=False)
        except Exception as e:
            self.log(f"обновление списка не удалось: {e}")
            return
        added = 0
        for d in res.get("candidates", []):
            c = self.engine.candidates.get(d["id"])
            from . import registry
            if not c or not c.public or c.number in self.watch or cached(c.number) or registry.is_blocked(c.number):
                continue
            if len(self.watch) >= self.size:
                break
            self.watch[c.number] = {"cand": c, "baseline": set(c.extra.get("keys") or []), "since": time.time()}
            added += 1
        self.last_refresh = time.time()
        self.log(f"под наблюдением: {len(self.watch)} (добавлено {added})")

    def _tick(self):
        for num, w in list(self.watch.items()):
            c = w["cand"]
            if cached(num):  # уже подтверждён другим способом
                del self.watch[num]
                continue
            prov = self.engine.providers[c.provider]
            try:
                msgs = _msgs(prov, c)
            except Exception:
                continue
            if not w["baseline"]:
                w["baseline"] = {m.key for m in msgs}
                continue
            new = [m for m in msgs if m.key not in w["baseline"]]
            if new:
                rec = record_passive(c, new[0], int(time.time() - w["since"]))
                self.engine.candidates[c.id] = c
                self.log(f"✅ {num} — новое SMS от {rec['sender']} → «проверенный»")
                del self.watch[num]
            elif time.time() - w["since"] > 3 * 3600:
                self.log(f"✗ {num} — 3 ч без новых SMS, снят с наблюдения")
                del self.watch[num]

    def _loop(self):
        while True:
            if time.time() - self.last_refresh > self.refresh or len(self.watch) < self.size // 3:
                self._refill()
            self._tick()
            time.sleep(self.cycle)

    def start(self):
        if not self.running:
            self.running = True
            threading.Thread(target=self._loop, daemon=True).start()
        return self

    def pool(self) -> list[dict]:
        """Все действующие «проверенные» номера (с кандидатами для выдачи)."""
        by_num = {c.number: c for c in self.engine.candidates.values() if c.public}
        out = []
        from . import registry
        for num, rec in _load().items():
            if not (rec.get("ok") and time.time() - rec.get("at", 0) < TTL) or registry.is_blocked(num):
                continue
            c = by_num.get(num)
            if c is None and rec.get("ref"):  # восстановить кандидата из записи (после перезапуска)
                c = Candidate(provider=rec["provider"], number=num, country=rec.get("country", ""),
                              ref=rec["ref"], price=0.0, public=True)
                self.engine.candidates[c.id] = c
            if c is None:
                continue
            mark(c, rec)
            c.extra["can_buy"] = True
            out.append(c)
        out.sort(key=lambda c: c.extra["verified"]["at"], reverse=True)
        return [c.to_dict() for c in out]

    def drop(self, number: str):
        self.watch.pop(number, None)

    def status(self) -> dict:
        return {"running": self.running, "watching": len(self.watch), "events": self.events[-15:][::-1],
                "watch_list": [{"number": n, "provider": w["cand"].provider,
                                "since": int(time.time() - w["since"])} for n, w in self.watch.items()]}
