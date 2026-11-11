"""5sim.net — платные приватные номера. Подбор по цене, наличию и % доставки.

Цены и статистика доставки берутся из публичного guest-API (ключ не нужен),
покупка и чтение SMS — по API-ключу из переменной FIVESIM_API_KEY.
"""
from __future__ import annotations

import math
import os

from ..core import TIMEOUT, Candidate, ProviderError, Sms, extract_code, http, noop_emit
from ..services import country_matches, service_name
from .base import Provider

API = "https://5sim.net/v1"


class FiveSim(Provider):
    name = "5sim"
    title = "5sim.net (платно, приватный номер)"
    paid = True
    public = False

    def __init__(self, api_key: str | None = None):
        self.key = api_key or os.getenv("FIVESIM_API_KEY", "")
        self.s = http()
        self.s.headers["Accept"] = "application/json"

    def available(self):
        return (True, "") if self.key else (True, "только просмотр цен: нет FIVESIM_API_KEY")

    def can_acquire(self):
        return bool(self.key)

    def _user(self, path):
        if not self.key:
            raise ProviderError("5sim: задайте FIVESIM_API_KEY, чтобы покупать номера")
        r = self.s.get(f"{API}/user/{path}", headers={"Authorization": f"Bearer {self.key}"},
                       timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"5sim: HTTP {r.status_code}: {r.text[:200]}")
        try:
            return r.json()
        except ValueError:
            raise ProviderError(f"5sim: {r.text[:200]}")

    def find(self, service, country, limit=20, mode="niche", emit=noop_emit):
        product = service_name(service, "5sim")
        r = self.s.get(f"{API}/guest/prices", params={"product": product}, timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"5sim: HTTP {r.status_code}")
        data = r.json().get(product, {})
        out, dropped = [], 0
        for cname, ops in data.items():
            if not country_matches(country, cname):
                continue
            for op, info in ops.items():
                count, cost = info.get("count", 0), float(info.get("cost", 0))
                rate = info.get("rate")
                if count <= 0 or (rate is not None and rate < 10):
                    dropped += 1  # нет в наличии или почти не доставляет SMS
                    continue
                real_sim = not op.startswith("virtual")
                # % доставки важнее всего, затем «реальная SIM», наличие и цена
                score = (rate if rate is not None else 25) + (30 if real_sim else 0) \
                    + min(math.log10(count + 1) * 5, 25) - min(cost, 100) * 0.3
                c = Candidate(
                    provider=self.name, number="", country=cname, ref=f"{cname}/{op}",
                    score=round(score, 1), price=cost, public=False, stock=count,
                    tier="premium" if (real_sim or (rate or 0) >= 50) else "regular",
                    note=(f"{'реальная SIM' if real_sim else 'виртуальный'} оператор {op} · "
                          f"доставка {rate if rate is not None else '?'}% · в наличии {count} · "
                          "номер новый для этого сервиса"),
                    extra={"operator": op, "rate": rate},
                )
                c.log(f"этап 1: {c.note}")
                out.append(c)
        emit({"type": "stage", "provider": self.name,
              "text": f"операторов прошло: {len(out)}, отсеяно (нет в наличии / доставка <10%): {dropped}"})
        out.sort(key=lambda c: c.score, reverse=True)
        return out[:limit]

    def acquire(self, cand, service):
        product = service_name(service, "5sim")
        d = self._user(f"buy/activation/{cand.ref}/{product}")
        return {"number": d.get("phone", ""), "id": d.get("id"), "ref": cand.ref}

    def messages(self, state):
        d = self._user(f"check/{state['id']}")
        return [Sms(sender=m.get("sender", "?"), text=m.get("text", ""),
                    code=m.get("code") or extract_code(m.get("text", "")),
                    received=m.get("created_at", "")) for m in d.get("sms") or []]

    def release(self, state, success):
        try:
            self._user(f"{'finish' if success else 'cancel'}/{state['id']}")
            return "finished" if success else "cancelled"
        except ProviderError as e:
            return str(e)
