"""onlinesim.io — бесплатные публичные номера через открытое API."""
from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor

from ..core import TIMEOUT, Candidate, Health, ProviderError, Sms, ago_to_seconds, digits, extract_code, http
from ..services import country_matches, service_matchers
from .base import FreeProvider

API = "https://onlinesim.io/api"


class OnlineSimFree(FreeProvider):
    name = "onlinesim"
    title = "onlinesim.io free (бесплатно, публично)"

    def __init__(self):
        self.s = http()

    def _json(self, method, **params):
        r = self.s.get(f"{API}/{method}", params=params, timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"{self.name}: HTTP {r.status_code}")
        return r.json()

    def listing(self, country, mode):
        countries = [c for c in self._json("getFreeList").get("countries", [])
                     if country_matches(country, c.get("country"), c.get("country_original"), c.get("country_text"))]

        def numbers(c):
            try:
                return [(c, n) for n in self._json("getFreePhoneList", country=c["country"]).get("numbers", [])]
            except Exception:
                return []

        with ThreadPoolExecutor(max_workers=8) as ex:
            pairs = [p for chunk in ex.map(numbers, countries) for p in chunk]
        out = []
        for c, n in pairs:
            num = digits(str(n.get("full_number") or n.get("number") or ""))
            if len(num) < 8:
                continue  # номер скрыт/отключён провайдером
            out.append(Candidate(provider=self.name, number="+" + num, country=c.get("country_original", ""),
                                 ref=num, price=0.0, public=True,
                                 extra={"status": n.get("status"), "archive": n.get("is_archive")}))
        return out

    def health(self, cand, service):
        if cand.extra.get("status") == "disabled" or cand.extra.get("archive"):
            return Health(ok=False, reason="отключён провайдером")
        t0 = time.time()
        try:
            sms = self.messages({"ref": cand.ref})
        except Exception as e:
            return Health(ok=False, reason=f"API недоступно ({e})")
        ms = int((time.time() - t0) * 1000)
        if not sms:
            return Health(ok=False, reason="ни одного SMS", latency_ms=ms)
        matchers = service_matchers(service)
        svc = [m for m in sms if matchers and any(k in (m.sender + " " + m.text).lower() for k in matchers)]
        seen = len(svc)
        svc_last = next((s for s in (ago_to_seconds(m.received) for m in svc) if s is not None), None)
        return Health(ok=True, last_s=ago_to_seconds(sms[0].received), usage=len(sms),
                      services=len({m.sender for m in sms}), service_seen=seen, service_last_s=svc_last, latency_ms=ms,
                      keys=[m.key for m in sms])

    def messages(self, state):
        data = self._json("getFreeMessageList", phone=state["ref"], lang="en")
        msgs = data.get("messages", {})
        items = msgs.get("data", []) if isinstance(msgs, dict) else msgs
        out = []
        for m in items or []:
            text = m.get("text") or m.get("message") or ""
            out.append(Sms(
                sender=str(m.get("in_number") or m.get("service") or m.get("from") or "?"),
                text=text,
                code=str(m.get("code") or "") or extract_code(text),
                received=str(m.get("data_humans") or m.get("created_at") or ""),
            ))
        return out
