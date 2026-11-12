"""Тест доставки под выбранный сервис — категория «проверенный».

Номер получает статус «проверенный для <сервис>», только если во время НАШЕГО наблюдения
во входящие пришло новое SMS именно от этого сервиса (для Telegram — SMS в формате
«Telegram code …»). Для «любого сервиса» (other) засчитывается любое новое SMS.

Способы:
  passive (по умолчанию, бесплатно) — номер ставится под наблюдение, фиксируется отпечаток
      нового SMS сервиса, отправитель и время с начала наблюдения. Подход как в
      github.com/LayorX/Temporary-SMS-Receiver-Monitor.
  active (только для «любого сервиса») — своё тестовое SMS со случайным кодом через шлюз
      SMS Gateway for Android / Telnyx / httpSMS / Twilio / Textbelt (sender.py). Для Telegram и т.п. бессмысленно:
      важно, доставляет ли сюда SMS сам сервис, а не наш шлюз.

Watcher — фоновый пул: постоянно наблюдает ~24 живых номера и записывает, от каких сервисов
на них реально приходят SMS прямо сейчас.

verified.json: {номер: {provider, ref, country, svc: {сервис: запись}}}; запись действует TTL (6 ч).
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
from .services import SERVICES, profile, service_code

PATH = Path(os.getenv("SMSFINDER_DATA", Path.home() / ".smsfinder")) / "verified.json"
TTL = int(os.getenv("SMSFINDER_VERIFY_TTL", 6 * 3600))
_lock = threading.Lock()


def _norm(service: str) -> str:
    s = (service or "other").lower().strip()
    return s or "other"


def _load() -> dict:
    try:
        data = json.loads(PATH.read_text())
    except (OSError, ValueError):
        return {}
    for num, e in list(data.items()):  # старый формат: одна запись на номер
        if "svc" not in e:
            data[num] = {"provider": e.get("provider"), "ref": e.get("ref"), "country": e.get("country"),
                         "svc": {"other": e} if e.get("ok") else {}}
    return data


def _write(data: dict):
    PATH.parent.mkdir(parents=True, exist_ok=True)
    PATH.write_text(json.dumps(data, ensure_ascii=False, indent=1))


def sms_services(m: Sms) -> set[str]:
    """От каких сервисов это SMS (для сервисов с профилем — строго по формату кода)."""
    text = f"{m.sender} {m.text}".lower()
    out = {"other"}
    for key, info in SERVICES.items():
        if key == "other":
            continue
        if profile(key).get("code_re"):
            if service_code(key, f"{m.sender} {m.text}"):
                out.add(key)
        elif any(k in text for k in info["match"]):
            out.add(key)
    return out


def cached(number: str, service: str = "other") -> Optional[dict]:
    e = _load().get(number)
    if not e:
        return None
    rec = e["svc"].get(_norm(service))
    if rec and time.time() - rec.get("at", 0) < TTL:
        return rec
    return None


def _save(cand: Candidate, service: str, rec: dict):
    with _lock:
        data = _load()
        e = data.setdefault(cand.number, {"svc": {}})
        e.update(provider=cand.provider, ref=cand.ref, country=cand.country)
        e["svc"][_norm(service)] = rec
        _write(data)


def invalidate(number: str):
    """Снять все статусы «проверенный» (номер остановлен или не подошёл)."""
    with _lock:
        data = _load()
        if data.pop(number, None) is not None:
            _write(data)


def svc_title(service: str) -> str:
    s = _norm(service)
    return "любого сервиса" if s == "other" else s.capitalize()


def mark(cand: Candidate, rec: dict, service: str = "other"):
    """Перевести кандидата в категорию «проверенный» (для данного сервиса)."""
    if cand.tier != "verified":
        cand.tier = "verified"
        cand.score = round(cand.score + 50, 1)
        cand.extra["base_note"] = cand.note
    cand.extra["verified"] = dict(rec, service=_norm(service))
    ago = int((time.time() - rec["at"]) // 60)
    if rec.get("kind") == "smart":
        how = f"тест 2/2: оба наших SMS дошли ({', '.join(rec.get('routes', []))}) за {rec['delivered_s']} с"
    elif rec.get("kind") == "active":
        how = f"наше тестовое SMS дошло за {rec['delivered_s']} с ({rec['via']})"
    else:
        how = f"{rec.get('sender', '?')} прислал SMS через {rec['delivered_s']} с наблюдения"
    busy = ""
    b = profile(service).get("busy_s", 0) if rec.get("kind") != "smart" else 0  # наш тест номер не занимает
    left = int(b - (time.time() - rec["at"])) if b else 0
    cand.extra["busy_left"] = max(left, 0)
    base = cand.extra.get("base_note") or ""
    if left > 0:
        busy = f" · ⚠ код сейчас запрашивал кто-то другой — номер освободится через ~{left // 60 + 1} мин"
        base = base.replace(", сейчас свободен", "")
    cand.note = f"✅ проверен для {svc_title(service)}: {how}, {ago} мин назад{busy}" + (f" · {base}" if base else "")


def record_sms(cand: Candidate, sms: Sms, observed_s: int) -> set[str]:
    """Новое SMS пришло во время наблюдения — записать доказательство для каждого его сервиса."""
    svcs = sms_services(sms)
    rec = {"ok": True, "at": time.time(), "kind": "passive", "via": "входящий трафик",
           "delivered_s": observed_s, "sender": sms.sender[:30], "proof": sms.key[:12]}
    for s in svcs:
        _save(cand, s, rec)
    return svcs


def _msgs(provider, cand) -> list[Sms]:
    return provider.messages({"ref": cand.ref, "number": cand.number})


class Verification:
    """Тест доставки одного номера под сервис."""

    def __init__(self, engine, cand: Candidate, wait: int = 180, emit: Emit = noop_emit,
                 method: str = "auto", service: str = "other", stop: Optional[threading.Event] = None):
        self.id = uuid.uuid4().hex[:12]
        self.engine, self.cand, self.wait, self.emit = engine, cand, wait, emit
        self.service = _norm(service)
        self.stop = stop or threading.Event()
        self.provider = engine.providers[cand.provider]
        if method == "auto":
            method = "active" if (self.service == "other" and not sender.describe()["free"]) else "passive"
        if self.service != "other":
            method = "passive"
        self.method = method
        self.token = f"{secrets.randbelow(900000) + 100000}" if method == "active" else ""
        self.status = "pending"   # pending → sent|watching → verified | failed
        self.detail = ""
        self.started = time.time()
        self.delivered_s: Optional[int] = None
        self.via = ""

    def to_dict(self) -> dict:
        return {"id": self.id, "cand_id": self.cand.id, "number": self.cand.number, "status": self.status,
                "service": self.service, "method": self.method, "detail": self.detail, "token": self.token,
                "via": self.via, "delivered_s": self.delivered_s, "elapsed": int(time.time() - self.started),
                "wait": self.wait, "candidate": self.cand.to_dict()}

    def _say(self, ok, reason):
        self.cand.log(f"тест доставки ({svc_title(self.service)}): {reason}")
        self.emit({"type": "verify", "provider": self.cand.provider, "number": self.cand.number,
                   "ok": ok, "reason": reason, "status": self.status, "id": self.cand.id,
                   "note": self.cand.note, "tier": self.cand.tier})

    def _fail(self, detail):
        self.status, self.detail = "failed", detail
        self.cand.extra["verify_failed"] = True
        self.cand.note = f"⏳ {detail} · " + self.cand.note
        self._say(False, detail)
        return self

    def run(self) -> "Verification":
        c = self.cand
        rec = cached(c.number, self.service)
        if rec:
            self.status, self.delivered_s, self.via = "verified", rec["delivered_s"], rec["via"]
            mark(c, rec, self.service)
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
                self._say(False, f"шлюз {res.via} не отправил ({res.error}) → жду входящие SMS")
                self.method, self.token = "passive", ""
            else:
                self.status = "sent"
                self._say(True, f"отправлено SMS с кодом {self.token} через {res.via}")
        if self.method == "passive":
            self.status, self.via = "watching", "входящий трафик"
            self._say(True, f"жду новое SMS от {svc_title(self.service)} до {self.wait // 60} мин")

        while time.time() - t0 < self.wait:
            if self.stop.wait(5 if self.method == "active" else 10):
                self.status = "stopped"
                self._say(False, "тест остановлен (цель достигнута или подбор остановлен)")
                return self
            try:
                msgs = _msgs(self.provider, c)
            except Exception:
                continue
            new = [m for m in msgs if m.key not in baseline]
            baseline |= {m.key for m in new}
            for m in new:  # всё новое — в общую копилку доказательств
                record_sms(c, m, int(time.time() - t0))
            if self.method == "active":
                hit = [m for m in new if self.token in m.text]
            else:
                hit = [m for m in new if self.service in sms_services(m)]
            if hit:
                self.delivered_s = int(time.time() - t0)
                self.status = "verified"
                if self.method == "active":
                    rec = {"ok": True, "at": time.time(), "kind": "active", "delivered_s": self.delivered_s,
                           "via": self.via}
                    _save(c, "other", rec)
                else:
                    rec = cached(c.number, self.service)
                mark(c, rec, self.service)
                self._say(True, f"{hit[0].sender} прислал SMS через {self.delivered_s} с → «проверенный»")
                return self
        return self._fail(f"за {self.wait // 60} мин SMS от {svc_title(self.service)} не было — "
                          "номер не подтверждён (фоновый пул продолжит наблюдение)")

    def start(self) -> "Verification":
        threading.Thread(target=self.run, daemon=True).start()
        return self


class Watcher:
    """Фоновый пул: постоянно наблюдает живые номера и записывает, от каких сервисов
    на них реально приходят SMS прямо сейчас."""

    def __init__(self, engine, size: int = 200, cycle: int = 30, refresh: int = 600):
        self.engine, self.size, self.cycle, self.refresh = engine, size, cycle, refresh
        self.watch: dict[str, dict] = {}   # number -> {cand, baseline, since, last_new}
        self.events: list[str] = []
        self.last_refresh = 0.0
        self.running = False

    def log(self, msg):
        self.events.append(f"{time.strftime('%H:%M:%S')} {msg}")
        del self.events[:-60]

    def _refill(self):
        """Пополнить наблюдение. Сначала берём номера, уже найденные подборами пользователя
        (без лишней нагрузки на сайты); свой поиск — только если их не хватает и сейчас
        не идёт пользовательский подбор."""
        from . import registry
        busy = any(not j.finished for j in self.engine.jobs.values())
        pool = [c for c in self.engine.candidates.values()
                if c.public and c.number not in self.watch and not registry.is_blocked(c.number)]
        if len(pool) < self.size and not busy:
            # строгие сервисы (Telegram) — первыми: номера, куда они уже доставляли SMS
            for svc in ("telegram", "other"):
                try:
                    res = self.engine.find(svc, "", None, self.size, "popular", 0, track=False)
                    pool += [self.engine.candidates[d["id"]] for d in res.get("candidates", [])
                             if d["id"] in self.engine.candidates]
                except Exception as e:
                    self.log(f"обновление списка не удалось: {e}")
        # приоритет: недавно приходило SMS от Telegram, затем общий score
        pool.sort(key=lambda c: (c.extra.get("svc_last_s") is None, c.extra.get("svc_last_s") or 0, -c.score))
        added = 0
        for c in pool:
            if not c.public or c.number in self.watch or registry.is_blocked(c.number):
                continue
            if len(self.watch) >= self.size:
                break
            now = time.time()
            self.watch[c.number] = {"cand": c, "baseline": set(c.extra.get("keys") or []),
                                    "since": now, "last_new": now}
            added += 1
        self.last_refresh = time.time()
        self.log(f"под наблюдением: {len(self.watch)} (добавлено {added})")

    def _tick(self):
        from concurrent.futures import ThreadPoolExecutor
        from . import registry
        items = list(self.watch.items())

        def fetch(item):
            num, w = item
            try:
                return num, _msgs(self.engine.providers[w["cand"].provider], w["cand"])
            except Exception:
                return num, None
        with ThreadPoolExecutor(max_workers=24) as ex:
            fetched = dict(ex.map(fetch, items))
        for num, w in items:
            c = w["cand"]
            if registry.is_blocked(num) or num not in self.watch:
                self.watch.pop(num, None)
                continue
            msgs = fetched.get(num)
            if msgs is None:
                continue
            if not w["baseline"]:
                w["baseline"] = {m.key for m in msgs}
                continue
            new = [m for m in msgs if m.key not in w["baseline"]]
            w["baseline"] |= {m.key for m in new}
            for m in new:
                svcs = record_sms(c, m, int(time.time() - w["since"])) - {"other"}
                w["last_new"] = time.time()
                if svcs:
                    self.log(f"✅ {num}: SMS от {', '.join(sorted(svcs))} → проверен для {', '.join(sorted(svcs))}")
                elif not any(e.startswith(f"✅ {num}") for e in self.events[-1:]):
                    self.log(f"✅ {num}: новое SMS ({m.sender[:20]}) → проверен (любой сервис)")
            if time.time() - w["last_new"] > 3 * 3600:
                self.log(f"✗ {num} — 3 ч без новых SMS, снят с наблюдения")
                del self.watch[num]

    def _loop(self):
        time.sleep(20)  # дать первому пользовательскому подбору отработать без конкуренции
        while True:
            try:
                if time.time() - self.last_refresh > self.refresh or len(self.watch) < self.size // 2:
                    self._refill()
                self._tick()
            except Exception as e:
                self.log(f"ошибка: {e}")
            time.sleep(self.cycle)

    def start(self):
        if not self.running:
            self.running = True
            threading.Thread(target=self._loop, daemon=True).start()
        return self

    def drop(self, number: str):
        self.watch.pop(number, None)

    def pool(self, service: str = "other", country: str = "") -> list[dict]:
        """Номера, проверенные для сервиса (новое SMS от него пришло при нашем наблюдении)."""
        from . import registry
        from .services import country_matches
        service = _norm(service)
        by_num = {c.number: c for c in self.engine.candidates.values() if c.public}
        out = []
        for num, e in _load().items():
            rec = e["svc"].get(service)
            if not rec or time.time() - rec.get("at", 0) >= TTL or registry.is_used(num, service):
                continue
            if not country_matches(country, e.get("country") or ""):
                continue
            c = by_num.get(num)
            if c is None and e.get("ref"):  # восстановить кандидата из записи (после перезапуска)
                c = Candidate(provider=e["provider"], number=num, country=e.get("country") or "",
                              ref=e["ref"], price=0.0, public=True)
                self.engine.candidates[c.id] = c
            if c is None:
                continue
            mark(c, rec, service)
            c.extra["can_buy"] = True
            out.append(c)
        out.sort(key=lambda c: c.extra["verified"]["at"], reverse=True)
        return [c.to_dict() for c in out]

    def status(self) -> dict:
        return {"running": self.running, "watching": len(self.watch), "events": self.events[-20:][::-1]}
