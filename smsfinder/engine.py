"""Подбор номеров по всем провайдерам и ожидание SMS."""
from __future__ import annotations

import os
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Optional

import requests

from .core import Candidate, ProviderError, Sms
from .providers import all_providers
from .services import service_matchers


def telegram_notify(text: str) -> None:
    """Отправить сообщение в Telegram, если заданы TELEGRAM_TOKEN и TELEGRAM_CHAT_ID."""
    token, chat = os.getenv("TELEGRAM_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
    if not (token and chat):
        return
    try:
        requests.post(f"https://api.telegram.org/bot{token}/sendMessage",
                      json={"chat_id": chat, "text": text}, timeout=10)
    except requests.RequestException:
        pass


class Activation:
    def __init__(self, engine: "Engine", cand: Candidate, service: str, timeout: int, poll: float):
        self.id = uuid.uuid4().hex[:12]
        self.engine, self.cand, self.service = engine, cand, service
        self.provider = engine.providers[cand.provider]
        self.timeout, self.poll = timeout, poll
        self.status = "starting"
        self.number = cand.number
        self.messages: list[Sms] = []
        self.code = ""
        self.error = ""
        self.started = time.time()
        self.state: dict = {}
        self._baseline: set[str] = set()
        self._stop = threading.Event()
        self.on_sms: list[Callable[["Activation", Sms], None]] = []

    def to_dict(self) -> dict:
        return {
            "id": self.id, "status": self.status, "number": self.number, "code": self.code,
            "error": self.error, "service": self.service, "provider": self.cand.provider,
            "public": self.cand.public, "elapsed": int(time.time() - self.started),
            "timeout": self.timeout, "candidate": self.cand.to_dict(),
            "messages": [m.to_dict() for m in self.messages],
        }

    def start(self) -> "Activation":
        self.state = self.provider.acquire(self.cand, self.service)
        self.number = self.state.get("number") or self.cand.number
        if self.cand.public:
            # публичный номер: запоминаем уже пришедшие SMS, ждём только новые
            try:
                self._baseline = {m.key for m in self.provider.messages(self.state)}
            except Exception:
                self._baseline = set()
        self.status = "waiting"
        threading.Thread(target=self._loop, daemon=True).start()
        return self

    def _loop(self):
        matchers = service_matchers(self.service)
        while not self._stop.is_set():
            if time.time() - self.started > self.timeout:
                self.status = "timeout" if not self.messages else self.status
                if not self.cand.public:  # платный номер без кода — отменяем, деньги вернутся
                    self.provider.release(self.state, success=bool(self.code))
                return
            try:
                for m in self.provider.messages(self.state):
                    if m.key in self._baseline:
                        continue
                    self._baseline.add(m.key)
                    self.messages.append(m)
                    relevant = not matchers or any(k in (m.sender + " " + m.text).lower() for k in matchers)
                    if relevant and m.code and not self.code:
                        self.code = m.code
                        self.status = "received"
                    for cb in self.on_sms:
                        cb(self, m)
                if self.code and not self.cand.public:
                    self.provider.release(self.state, success=True)
                    return
            except ProviderError as e:
                self.error = str(e)
                if "отменена" in self.error:
                    self.status = "error"
                    return
            except Exception as e:  # сеть/разметка — пробуем снова
                self.error = f"{type(e).__name__}: {e}"
            self._stop.wait(self.poll)

    def cancel(self):
        self._stop.set()
        if not self.cand.public and self.state:
            self.provider.release(self.state, success=bool(self.code))
        if self.status in ("waiting", "starting"):
            self.status = "cancelled"

    def wait(self) -> "Activation":
        while self.status in ("starting", "waiting") and not self._stop.is_set():
            time.sleep(0.5)
        return self


class Engine:
    def __init__(self, providers: Optional[dict] = None):
        self.providers = providers or all_providers()
        self.candidates: dict[str, Candidate] = {}
        self.activations: dict[str, Activation] = {}

    def provider_info(self) -> list[dict]:
        out = []
        for p in self.providers.values():
            ok, why = p.available()
            out.append({"name": p.name, "title": p.title, "paid": p.paid, "public": p.public,
                        "enabled": ok, "note": why})
        return out

    def find(self, service: str, country: str = "", providers: Optional[list[str]] = None,
             limit: int = 15) -> dict:
        """Параллельно опрашивает провайдеров, сверяет номера и сортирует по score."""
        names = providers or [p["name"] for p in self.provider_info() if p["enabled"]]
        t0 = time.time()

        def run(name):
            try:
                return name, self.providers[name].find(service, country, limit), None
            except Exception as e:
                return name, [], str(e)

        with ThreadPoolExecutor(max_workers=len(names) or 1) as ex:
            results = list(ex.map(run, names))
        cands, errors = [], {}
        for name, cs, err in results:
            cands += cs
            if err:
                errors[name] = err
        for c in cands:
            if not c.public:  # приватные (платные) номера надёжнее — небольшой бонус
                c.score = round(c.score + 15, 1)
            c.extra["can_buy"] = self.providers[c.provider].can_acquire()
            self.candidates[c.id] = c
        cands.sort(key=lambda c: (c.extra["can_buy"], c.score), reverse=True)
        return {"candidates": [c.to_dict() for c in cands], "errors": errors,
                "took": round(time.time() - t0, 2)}

    def start(self, cand_id: str, service: str, timeout: int = 600, poll: float = 5.0) -> Activation:
        cand = self.candidates.get(cand_id)
        if not cand:
            raise ProviderError("кандидат не найден — выполните поиск заново")
        act = Activation(self, cand, service, timeout, poll)
        act.on_sms.append(lambda a, m: telegram_notify(
            f"📩 SMS на {a.number} ({a.cand.provider})\nОт: {m.sender}\n{m.text}"
            + (f"\nКод: {m.code}" if m.code else "")))
        act.start()
        self.activations[act.id] = act
        return act

    def auto(self, service: str, country: str = "", providers: Optional[list[str]] = None,
             timeout: int = 600, tries: int = 3) -> Activation:
        """Подобрать лучший номер и занять его; при ошибке покупки — следующий."""
        res = self.find(service, country, providers)
        last_err = None
        for c in [c for c in res["candidates"] if c["extra"].get("can_buy")][:tries]:
            try:
                return self.start(c["id"], service, timeout)
            except ProviderError as e:
                last_err = e
        raise ProviderError(f"не удалось занять номер: {last_err or res['errors'] or 'нет кандидатов'}")
