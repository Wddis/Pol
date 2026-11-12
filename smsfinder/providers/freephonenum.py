"""freephonenum.com — ~600 бесплатных номеров, в т.ч. редкие страны (EE, LV, NZ, RO, ZA, BG...).

Источник найден в GitHub-списках бесплатных SMS-сервисов
(mishakorzik/VirtualPhoneNumber, fazgal0/free-sms-receivers).
"""
from __future__ import annotations

import random
import re
import time
from urllib.parse import urljoin

from ..fast import Soup

from ..core import (COMMON_COUNTRIES, TIMEOUT, Candidate, Health, ProviderError, Sms, ago_to_seconds,
                    extract_code, http)
from ..services import COUNTRIES, country_matches, service_matchers
from .base import FreeProvider

BASE = "https://freephonenum.com"
_HREF = re.compile(r"^/([a-z]{2})/receive-sms/(\d+)$")


class FreePhoneNum(FreeProvider):
    name = "freephonenum"
    title = "freephonenum.com (бесплатно, публично, редкие страны)"
    max_check = 45

    def __init__(self):
        super().__init__()
        self.s = http()

    def _get(self, path):
        t0 = time.time()
        r = self.s.get(urljoin(BASE, path), timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"HTTP {r.status_code}")
        return Soup(r.text), int((time.time() - t0) * 1000)

    def listing(self, country, mode):
        soup, _ = self._get("/receive-sms")
        by_cc: dict[str, list[Candidate]] = {}
        for a in soup.select("a[href*='/receive-sms/']"):
            m = _HREF.match(a.get("href", ""))
            if not m:
                continue
            iso, local = m.groups()
            code = COUNTRIES.get(iso, ("", ""))[1]
            if not country_matches(country, iso, COUNTRIES.get(iso, ("",))[0], code):
                continue
            by_cc.setdefault(iso, []).append(Candidate(
                provider=self.name, number=f"+{code}{local}" if code else f"+{local}",
                country=iso.upper(), ref=m.group(0), price=0.0, public=True))
        if not by_cc:
            if country:
                return []
            raise ProviderError(f"{self.name}: не удалось получить список номеров")
        # нишевый режим: сначала редкие страны, из «заезженных» — случайная выборка
        rare = [c for iso, cs in by_cc.items() if iso not in COMMON_COUNTRIES for c in cs]
        common = [c for iso, cs in by_cc.items() if iso in COMMON_COUNTRIES for c in cs]
        random.shuffle(common)
        # «Get a fresh number» на сайте отдаёт номера с живой лентой — ставим их первыми
        def random_one(_):
            try:
                r = self.s.get(f"{BASE}/receive-sms/random", timeout=TIMEOUT, allow_redirects=False)
            except Exception:
                return None
            m = re.search(r"/([a-z]{2})/receive-sms/(.+)$", r.headers.get("Location", ""))
            if not (m and country_matches(country, m.group(1))):
                return None
            loc = "".join(ch for ch in m.group(2) if ch.isdigit())
            iso = m.group(1)
            code = COUNTRIES.get(iso, ("", ""))[1]
            return Candidate(provider=self.name, number=f"+{code}{loc}", country=iso.upper(),
                             ref=f"/{iso}/receive-sms/{loc}", price=0.0, public=True)

        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=4) as ex:  # раньше 4 запроса шли друг за другом
            fresh = [c for c in ex.map(random_one, range(4)) if c]
        seen, out = set(), []
        for c in fresh + (rare + common if mode == "niche" else common + rare):
            if c.ref not in seen:
                seen.add(c.ref)
                out.append(c)
        return out

    @staticmethod
    def _parse_sms(soup) -> list[Sms]:
        out = []
        for div in soup.select("#messages .msg"):
            body = div.select_one(".js-msgtext")
            if not body:
                continue
            name = div.select_one(".font-bold")
            num = div.select_one("a.mono")
            when = div.select_one("time") or div.find(string=re.compile(r"ago", re.I))
            sender = (name.get_text(strip=True) if name else "") or "?"
            if sender.lower() == "sender" and num:
                sender = num.get_text(strip=True)
            txt = body.get_text(" ", strip=True)
            mid = (num.get("href", "").rstrip("/").split("/")[-1] if num else "")
            out.append(Sms(sender=sender, text=txt, code=extract_code(txt),
                           received=(when.get_text(strip=True) if hasattr(when, "get_text")
                                     else (f"#{mid}" if mid else ""))))
        return out

    def health(self, cand, service):
        try:
            soup, ms = self._get(cand.ref)
        except Exception as e:
            return Health(ok=False, reason=f"страница недоступна ({e})")
        pill = soup.select_one(".pill-live")
        status = pill.get_text(" ", strip=True).lower() if pill else ""
        sms = self._parse_sms(soup)
        if not sms:
            return Health(ok=False, reason="ни одного SMS — не подтверждено, что номер принимает", latency_ms=ms)
        if "archiv" in status:
            return Health(ok=False, reason="лента в архиве — номер больше не принимает", latency_ms=ms)
        last_s = next((s for s in (ago_to_seconds(m.received) for m in sms) if s is not None), None)
        matchers = service_matchers(service)
        hits = [i for i, m in enumerate(sms) if matchers and any(k in (m.sender + " " + m.text).lower() for k in matchers)]
        seen = len(hits)
        svc_last = 0 if hits and hits[0] <= 1 else None  # SMS сервиса среди самых последних — номер занят
        txt = soup.get_text(" ", strip=True)
        svc = re.search(r"(\d+)\s+services", txt)
        return Health(ok=True, last_s=last_s, usage=len(sms),
                      services=int(svc.group(1)) if svc else len({m.sender for m in sms}),
                      service_seen=seen, service_last_s=svc_last, latency_ms=ms, keys=[m.key for m in sms])

    def messages(self, state):
        return self._parse_sms(self._get(state["ref"])[0])
