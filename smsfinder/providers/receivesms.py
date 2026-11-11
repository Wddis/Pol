"""receivesms.co — бесплатные публичные номера (скрапинг HTML)."""
from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

from bs4 import BeautifulSoup

from ..core import TIMEOUT, Candidate, ProviderError, Sms, ago_to_seconds, digits, extract_code, http
from ..services import country_matches, service_matchers
from .base import Provider

BASE = "https://www.receivesms.co"
LISTS = ["/active-numbers", "/free-phone-numbers"]


class ReceiveSmsCo(Provider):
    name = "receivesms"
    title = "receivesms.co (бесплатно, публично)"

    def __init__(self):
        self.s = http()

    # --- парсинг ---------------------------------------------------------
    def _get(self, path: str) -> BeautifulSoup:
        r = self.s.get(urljoin(BASE, path), timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"{self.name}: HTTP {r.status_code} на {path}")
        return BeautifulSoup(r.text, "html.parser")

    def _listing(self) -> list[dict]:
        seen, out = set(), []
        for path in LISTS:
            try:
                soup = self._get(path)
            except Exception:
                continue
            for a in soup.select("a.card-link[href*='-phone-number/']"):
                href = a.get("href", "")
                if href in seen:
                    continue
                seen.add(href)
                flag = a.select_one("img.flag")
                num = a.select_one("strong")
                out.append({
                    "href": href,
                    "number": "+" + digits(num.get_text() if num else ""),
                    "iso": (flag.get("alt") if flag else "").lower(),
                })
        if not out:
            raise ProviderError(f"{self.name}: не удалось получить список номеров")
        return out

    def _page(self, href: str) -> dict:
        soup = self._get(href)
        text = soup.get_text(" ", strip=True)
        last = re.search(r"Last activity:\s*([^|<]+?ago|just now)", text)
        m24 = re.search(r"Messages \(24h\):\s*(\d+)", text)
        return {
            "last_s": ago_to_seconds(last.group(1)) if last else None,
            "msgs24": int(m24.group(1)) if m24 else 0,
            "sms": self._parse_sms(soup),
        }

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

    # --- API провайдера ---------------------------------------------------
    def find(self, service, country, limit=20):
        items = [i for i in self._listing() if country_matches(country, i["iso"])]
        items = items[: max(limit * 2, 10)]
        matchers = service_matchers(service)

        def check(item):
            try:
                p = self._page(item["href"])
            except Exception as e:
                return None, str(e)
            last = p["last_s"]
            if last is None or last > 86400:  # номер «мёртв» больше суток
                return None, "inactive"
            seen = [m for m in p["sms"]
                    if any(k in (m.sender + " " + m.text).lower() for k in matchers)]
            score = min(p["msgs24"], 100) * 0.5
            score += 40 if last <= 600 else 25 if last <= 3600 else 5
            score += 30 if seen else 0
            note = f"активность {last // 60} мин назад, SMS за 24ч: {p['msgs24']}"
            if matchers:
                note += f", от сервиса: {len(seen)}" if seen else ", от сервиса: не было"
            return Candidate(
                provider=self.name, number=item["number"], country=item["iso"].upper(),
                ref=item["href"], score=round(score, 1), price=0.0, public=True, note=note,
                extra={"msgs24": p["msgs24"], "last_s": last, "service_seen": len(seen)},
            ), None

        with ThreadPoolExecutor(max_workers=10) as ex:
            res = list(ex.map(check, items))
        cands = [c for c, _ in res if c]
        cands.sort(key=lambda c: c.score, reverse=True)
        return cands[:limit]

    def messages(self, state):
        return self._parse_sms(self._get(state["ref"]))
