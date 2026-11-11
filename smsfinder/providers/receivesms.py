"""receivesms.co — бесплатные публичные номера (скрапинг HTML)."""
from __future__ import annotations

import re
import time
from urllib.parse import urljoin

from bs4 import BeautifulSoup

from ..core import (TIMEOUT, Candidate, Health, ProviderError, Sms, ago_to_seconds, digits,
                    extract_code, http)
from ..services import country_matches, service_matchers
from .base import FreeProvider

BASE = "https://www.receivesms.co"
LISTS = ["/active-numbers", "/free-phone-numbers"]


class ReceiveSmsCo(FreeProvider):
    name = "receivesms"
    title = "receivesms.co (бесплатно, публично)"

    def __init__(self):
        self.s = http()

    def _get(self, path: str) -> tuple[BeautifulSoup, int]:
        t0 = time.time()
        r = self.s.get(urljoin(BASE, path), timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"HTTP {r.status_code}")
        return BeautifulSoup(r.text, "html.parser"), int((time.time() - t0) * 1000)

    def listing(self, country, mode):
        seen, out = set(), []
        for path in LISTS:
            try:
                soup, _ = self._get(path)
            except Exception:
                continue
            for a in soup.select("a.card-link[href*='-phone-number/']"):
                href = a.get("href", "")
                flag = a.select_one("img.flag")
                iso = (flag.get("alt") if flag else "").lower()
                num = a.select_one("strong")
                if href in seen or not country_matches(country, iso):
                    continue
                seen.add(href)
                out.append(Candidate(provider=self.name, number="+" + digits(num.get_text() if num else ""),
                                     country=iso.upper(), ref=href, price=0.0, public=True))
        if not out and not country:
            raise ProviderError(f"{self.name}: не удалось получить список номеров")
        return out

    @staticmethod
    def _parse_sms(soup: BeautifulSoup) -> list[Sms]:
        out = []
        for art in soup.select("#sms-comments article.entry-card"):
            body = art.select_one(".sms")
            if not body:
                continue
            sender = art.select_one(".from-link") or art.select_one(".entry-left")
            chip = art.select_one("[data-code]")
            when = art.select_one(".entry-right .muted")
            txt = body.get_text(" ", strip=True)
            out.append(Sms(
                sender=(sender.get_text(" ", strip=True) if sender else "?").replace("From ", ""),
                text=txt,
                code=(chip.get("data-code") if chip else "") or extract_code(txt),
                received=when.get_text(strip=True) if when else "",
            ))
        return out

    def health(self, cand, service):
        try:
            soup, ms = self._get(cand.ref)
        except Exception as e:
            return Health(ok=False, reason=f"страница недоступна ({e})")
        text = soup.get_text(" ", strip=True)
        if "Last activity" not in text and not soup.select("#sms-comments"):
            return Health(ok=False, reason="номер снят с сайта", latency_ms=ms)
        last = re.search(r"Last activity:\s*([^|<]+?ago|just now)", text)
        m24 = re.search(r"Messages \(24h\):\s*(\d+)", text)
        sms = self._parse_sms(soup)
        matchers = service_matchers(service)
        seen = sum(any(k in (m.sender + " " + m.text).lower() for k in matchers) for m in sms) if matchers else 0
        last_s = ago_to_seconds(last.group(1)) if last else None
        if last_s is None and sms:
            last_s = ago_to_seconds(sms[0].received)
        if last_s is None:
            return Health(ok=False, reason="ни одного SMS — не подтверждено, что номер принимает", latency_ms=ms)
        return Health(ok=True, last_s=last_s, usage=int(m24.group(1)) if m24 else len(sms),
                      services=len({m.sender.lower() for m in sms}), service_seen=seen,
                      latency_ms=ms, keys=[m.key for m in sms])

    def messages(self, state):
        return self._parse_sms(self._get(state["ref"])[0])
