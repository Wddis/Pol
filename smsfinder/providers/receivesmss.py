"""receive-smss.com — бесплатные публичные номера.

Разметка восстановлена по GitHub-проекту 1d8/sedoppkit (modules/misc/tempsms.py):
список — h4.number-boxes-itemm-number на главной, SMS — таблица на /sms/<номер>/
(колонки: отправитель — 2-я, время — 4-я, текст — 5-я). Сайт за Cloudflare: с некоторых
IP отвечает 403 — тогда провайдер честно сообщает об этом и пропускается.
"""
from __future__ import annotations

import time

from ..fast import Soup

from ..core import TIMEOUT, Candidate, Health, ProviderError, Sms, ago_to_seconds, digits, extract_code, http
from ..services import COUNTRIES, country_matches, service_matchers
from .asms import _iso_for
from .base import FreeProvider

BASE = "https://receive-smss.com"


class ReceiveSmss(FreeProvider):
    name = "receivesmss"
    title = "receive-smss.com (бесплатно, публично)"

    def __init__(self):
        super().__init__()
        self.s = http()

    def available(self):
        import os
        if os.getenv("SMSFINDER_RECEIVESMSS") == "1":
            return True, ""
        return False, "сайт закрыт защитой Cloudflare для серверов (403); включить: SMSFINDER_RECEIVESMSS=1"

    def _get(self, path):
        t0 = time.time()
        r = self.s.get(BASE + path, timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"HTTP {r.status_code}" + (" (защита Cloudflare)" if r.status_code == 403 else ""))
        return Soup(r.text), int((time.time() - t0) * 1000)

    def listing(self, country, mode):
        soup, _ = self._get("/")
        out = []
        for h in soup.select("h4.number-boxes-itemm-number"):
            num = digits(h.get_text())
            if len(num) < 8:
                continue
            iso = _iso_for(num)
            if country_matches(country, iso, COUNTRIES.get(iso, ("",))[0]):
                out.append(Candidate(provider=self.name, number="+" + num, country=iso.upper(),
                                     ref=f"/sms/{num}/", price=0.0, public=True))
        if not out and not country:
            raise ProviderError(f"{self.name}: список номеров пуст")
        return out

    @staticmethod
    def _parse_sms(soup):
        out = []
        for tr in soup.find_all("tr")[1:]:
            tds = tr.find_all("td")
            if len(tds) < 5:
                continue
            when = tds[3].get_text(" ", strip=True)
            when = when[: when.find("real")] if "real" in when else when
            txt = tds[4].get_text(" ", strip=True)
            out.append(Sms(sender=tds[1].get_text(strip=True), text=txt, code=extract_code(txt), received=when))
        return out

    def health(self, cand, service):
        try:
            soup, ms = self._get(cand.ref)
        except Exception as e:
            return Health(ok=False, reason=f"страница недоступна ({e})")
        sms = self._parse_sms(soup)
        if not sms:
            return Health(ok=False, reason="ни одного SMS", latency_ms=ms)
        ages = [ago_to_seconds(m.received) for m in sms]
        matchers = service_matchers(service)
        svc = [(m, a) for m, a in zip(sms, ages) if matchers and any(k in (m.sender + " " + m.text).lower()
                                                                     for k in matchers)]
        return Health(ok=True, last_s=next((a for a in ages if a is not None), None), usage=len(sms),
                      services=len({m.sender for m in sms}), service_seen=len(svc),
                      service_last_s=svc[0][1] if svc else None, latency_ms=ms, keys=[m.key for m in sms])

    def messages(self, state):
        return self._parse_sms(self._get(state["ref"])[0])
