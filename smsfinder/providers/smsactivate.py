"""Провайдеры с протоколом SMS-Activate (handler_api.php): Grizzly SMS, SMS-Activate и др.

Настройка:
  SMSACT_URL  — адрес handler_api.php (по умолчанию Grizzly SMS)
  SMSACT_KEY  — API-ключ
"""
from __future__ import annotations

import math
import os

from ..core import TIMEOUT, Candidate, ProviderError, Sms, extract_code, http, noop_emit
from ..services import country_matches, service_name
from .base import Provider

DEFAULT_URL = "https://api.grizzlysms.com/stubs/handler_api.php"


class SmsActivateCompat(Provider):
    name = "smsactivate"
    paid = True
    public = False

    def __init__(self, url: str | None = None, key: str | None = None):
        self.url = url or os.getenv("SMSACT_URL", DEFAULT_URL)
        self.key = key or os.getenv("SMSACT_KEY", "")
        self.s = http()
        host = self.url.split("/")[2] if "//" in self.url else self.url
        self.title = f"{host} (платно, протокол SMS-Activate)"
        self._countries = None

    def available(self):
        return (True, "") if self.key else (False, "нет SMSACT_KEY")

    def can_acquire(self):
        return bool(self.key)

    def _call(self, action, **params):
        if not self.key:
            raise ProviderError("smsactivate: задайте SMSACT_KEY")
        r = self.s.get(self.url, params={"api_key": self.key, "action": action, **params}, timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"smsactivate: HTTP {r.status_code}")
        try:
            return r.json()
        except ValueError:
            t = r.text.strip()
            if t in ("BAD_KEY", "NO_KEY", "BAD_ACTION", "ERROR_SQL", "NO_BALANCE", "NO_NUMBERS", "BAD_SERVICE"):
                raise ProviderError(f"smsactivate: {t}")
            return t

    def _country_names(self) -> dict:
        if self._countries is None:
            try:
                d = self._call("getCountries")
                self._countries = {str(k): (v.get("eng") or v.get("rus") or str(k)) for k, v in d.items()} \
                    if isinstance(d, dict) else {}
            except ProviderError:
                self._countries = {}
        return self._countries

    def find(self, service, country, limit=20, mode="niche", emit=noop_emit):
        svc = service_name(service, "smsact")
        prices = self._call("getPrices", service=svc)
        if not isinstance(prices, dict):
            raise ProviderError(f"smsactivate: неожиданный ответ {str(prices)[:100]}")
        names = self._country_names()
        out = []
        for cid, svcs in prices.items():
            info = (svcs or {}).get(svc)
            if not info:
                continue
            cname = names.get(str(cid), str(cid))
            if not country_matches(country, cid, cname):
                continue
            count, cost = int(info.get("count", 0)), float(info.get("cost", 0))
            if count <= 0:
                continue
            score = 25 + min(math.log10(count + 1) * 5, 25) - min(cost, 100) * 0.3
            out.append(Candidate(
                provider=self.name, number="", country=cname, ref=str(cid), score=round(score, 1),
                price=cost, public=False, stock=count, tier="premium",
                note=f"приватный номер · в наличии {count} · номер новый для этого сервиса",
            ))
        out.sort(key=lambda c: c.score, reverse=True)
        return out[:limit]

    def acquire(self, cand, service):
        t = self._call("getNumber", service=service_name(service, "smsact"), country=cand.ref)
        if not isinstance(t, str) or not t.startswith("ACCESS_NUMBER"):
            raise ProviderError(f"smsactivate: {t}")
        _, aid, phone = t.split(":", 2)
        self._call("setStatus", id=aid, status=1)  # «SMS отправлено», ускоряет выдачу у части сервисов
        return {"number": "+" + phone, "id": aid, "ref": cand.ref}

    def messages(self, state):
        t = self._call("getStatus", id=state["id"])
        if isinstance(t, str) and t.startswith("STATUS_OK:"):
            code = t.split(":", 1)[1]
            return [Sms(sender="activation", text=code, code=extract_code(code) or code)]
        if isinstance(t, str) and t.startswith("STATUS_CANCEL"):
            raise ProviderError("smsactivate: активация отменена провайдером")
        return []

    def release(self, state, success):
        try:
            return str(self._call("setStatus", id=state["id"], status=6 if success else 8))
        except ProviderError as e:
            return str(e)
