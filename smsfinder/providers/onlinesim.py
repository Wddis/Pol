"""onlinesim.io — бесплатные публичные номера через открытое API."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from ..core import TIMEOUT, Candidate, ProviderError, Sms, ago_to_seconds, digits, extract_code, http
from ..services import country_matches, service_matchers
from .base import Provider

API = "https://onlinesim.io/api"


class OnlineSimFree(Provider):
    name = "onlinesim"
    title = "onlinesim.io free (бесплатно, публично)"

    def __init__(self):
        self.s = http()

    def _json(self, method, **params):
        r = self.s.get(f"{API}/{method}", params=params, timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"{self.name}: HTTP {r.status_code}")
        return r.json()

    def find(self, service, country, limit=20):
        countries = self._json("getFreeList").get("countries", [])
        countries = [c for c in countries
                     if country_matches(country, c.get("country"), c.get("country_original"),
                                        c.get("country_text"))]

        def numbers(c):
            try:
                return [(c, n) for n in self._json("getFreePhoneList", country=c["country"]).get("numbers", [])]
            except Exception:
                return []

        with ThreadPoolExecutor(max_workers=8) as ex:
            pairs = [p for chunk in ex.map(numbers, countries) for p in chunk]

        matchers = service_matchers(service)
        out = []
        for c, n in pairs:
            num = digits(str(n.get("full_number") or n.get("number") or ""))
            if n.get("status") == "disabled" or n.get("is_archive") or len(num) < 8:
                continue
            last = ago_to_seconds(n.get("data_humans", ""))
            score = 30 + (40 if last is not None and last <= 600 else 20 if last is not None and last <= 3600 else 0)
            out.append(Candidate(
                provider=self.name, number="+" + num, country=c.get("country_original", ""),
                ref=num, score=score, price=0.0, public=True,
                note=f"обновлён {n.get('data_humans', '?')}", extra={"last_s": last},
            ))
        # сверка: смотрим, приходили ли SMS от нужного сервиса
        if matchers and out:
            def seen(c):
                try:
                    msgs = self.messages({"ref": c.ref})
                except Exception:
                    return c
                hit = sum(any(k in (m.sender + " " + m.text).lower() for k in matchers) for m in msgs)
                if hit:
                    c.score += 30
                c.note += f", от сервиса: {hit}"
                return c
            with ThreadPoolExecutor(max_workers=8) as ex:
                out = list(ex.map(seen, out))
        out.sort(key=lambda c: c.score, reverse=True)
        return out[:limit]

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
