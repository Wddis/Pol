"""asms.ai (бывший anonymsms.com) — бесплатные публичные номера с точным временем SMS.

Источник найден в GitHub-проектах Shelex/free-otp-api (провайдер anonymsms.com) и
AbirHasan2005/OPRecon. У сайта есть раздел «новые номера» (/new-numbers) — редко
используемые номера, их проверяем первыми. Время каждого SMS отдаётся как
<time dateTime="ISO">, поэтому свежесть номера и SMS сервиса определяется точно.
"""
from __future__ import annotations

import re
import time
from datetime import datetime, timezone
from urllib.parse import urljoin

from bs4 import BeautifulSoup

from ..core import TIMEOUT, Candidate, Health, ProviderError, Sms, extract_code, http
from ..services import COUNTRIES, country_matches, service_matchers
from .base import FreeProvider

BASE = "https://asms.ai"
_STATUS = re.compile(r"·\W{0,16}(Active|Inactive|Offline|Retired|Expired|Disabled|Archived)\b", re.I)
_NUM = re.compile(r"^/number/(\d{7,15})$")
# телефонный код -> ISO2 (длинные коды проверяются первыми)
_DIAL = sorted(((code, iso) for iso, (_, code) in COUNTRIES.items() if code), key=lambda x: -len(x[0]))


def _iso_for(number: str) -> str:
    for code, iso in _DIAL:
        if number.startswith(code):
            return iso
    return ""


def _ago(iso: str) -> str:
    try:
        dt = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    except ValueError:
        return ""
    sec = max(0, int((datetime.now(timezone.utc) - dt).total_seconds()))
    if sec < 60:
        return f"{sec} seconds ago"
    if sec < 3600:
        return f"{sec // 60} minutes ago"
    if sec < 86400:
        return f"{sec // 3600} hours ago"
    return f"{sec // 86400} days ago"


class AsmsAi(FreeProvider):
    name = "asms"
    title = "asms.ai (бесплатно, публично, новые номера)"
    max_check = 90

    def __init__(self):
        super().__init__()
        self.s = http()

    def _get(self, path: str) -> tuple[BeautifulSoup, int]:
        t0 = time.time()
        r = self.s.get(urljoin(BASE, path), timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"HTTP {r.status_code}")
        return BeautifulSoup(r.text, "html.parser"), int((time.time() - t0) * 1000)

    pages = int(__import__("os").getenv("SMSFINDER_ASMS_PAGES", "15"))  # 60 номеров на страницу

    def listing(self, country, mode):
        """Все страницы списка параллельно. Карточка списка уже содержит число SMS и время
        последнего SMS — по ним отсеиваем «спящие» номера, не открывая каждый."""
        from concurrent.futures import ThreadPoolExecutor
        from ..core import ago_to_seconds
        iso = country.lower().strip() if country and len(country.strip()) == 2 else ""
        if iso:
            paths = [f"/free-phone-numbers?country={iso}&page={i}" for i in range(1, 4)]
        else:
            paths = ["/new-numbers", "/new-numbers?page=2"] + [f"/free-phone-numbers?page={i}"
                                                              for i in range(1, self.pages + 1)]

        def fetch(path):
            try:
                return path, self._get(path)[0]
            except Exception:
                return path, None
        with ThreadPoolExecutor(max_workers=8) as ex:
            soups = list(ex.map(fetch, paths))
        seen, out = set(), []
        for path, soup in soups:
            if soup is None:
                continue
            for a in soup.select("a[href^='/number/']"):
                m = _NUM.match(a.get("href", ""))
                if not m or m.group(1) in seen:
                    continue
                num = m.group(1)
                seen.add(num)
                parts = [p.strip() for p in a.get_text("|", strip=True).split("|") if p.strip()]
                total = next((int(p) for p in parts if p.isdigit()), None)
                last_s = next((ago_to_seconds(p) for p in parts if ago_to_seconds(p) is not None), None)
                if last_s is not None and last_s > self.max_age:
                    continue  # больше суток без SMS — не тратим на него запрос
                ciso = _iso_for(num)
                if not country_matches(country, ciso, COUNTRIES.get(ciso, ("",))[0], *parts[1:2]):
                    continue
                out.append(Candidate(provider=self.name, number="+" + num, country=ciso.upper() or (parts[1:2] or [""])[0],
                                     ref=m.group(0), price=0.0, public=True,
                                     extra={"fresh_list": path.startswith("/new-numbers"), "list_total": total,
                                            "list_last_s": last_s}))
        if not out and not country:
            raise ProviderError(f"{self.name}: не удалось получить список номеров")
        # самые «живые» — первыми (их и проверяем подробно)
        out.sort(key=lambda c: (c.extra["list_last_s"] if c.extra["list_last_s"] is not None else 10 ** 9))
        return out

    @staticmethod
    def _parse_sms(soup: BeautifulSoup) -> list[Sms]:
        out = []
        for t in soup.select("time[datetime]"):
            card = t.find_parent("div", class_=re.compile("rounded"))
            if not card:
                continue
            body = card.find("p")
            if not body:
                continue
            snd = card.select_one(".font-semibold")  # <span> или <a href="/telegram"> для известных сервисов
            txt = body.get_text(" ", strip=True)
            out.append(Sms(sender=snd.get_text(strip=True) if snd else "?", text=txt,
                           code=extract_code(txt), received=_ago(t.get("datetime", ""))))
        return out

    def health(self, cand, service):
        from ..core import ago_to_seconds
        try:
            soup, ms = self._get(cand.ref)
        except Exception as e:
            return Health(ok=False, reason=f"страница недоступна ({e})")
        # статус номера на сайте («Netherlands · Active»); проверка как numberIsOnline
        # в Shelex/free-otp-api (anonymsms.com — прежний адрес asms.ai)
        st = _STATUS.search(str(soup))
        if st and st.group(1).lower() != "active":
            return Health(ok=False, reason=f"сайт пометил номер как {st.group(1)} — больше не принимает",
                          latency_ms=ms)
        sms = self._parse_sms(soup)
        if not sms:
            return Health(ok=False, reason="ни одного SMS — не подтверждено, что номер принимает", latency_ms=ms)
        ages = [ago_to_seconds(m.received) for m in sms]
        matchers = service_matchers(service)
        svc = [(m, a) for m, a in zip(sms, ages) if matchers and any(k in (m.sender + " " + m.text).lower()
                                                                     for k in matchers)]
        return Health(ok=True, last_s=ages[0], usage=sum(1 for a in ages if a is not None and a < 86400),
                      services=len({m.sender.lower() for m in sms}), service_seen=len(svc),
                      service_last_s=svc[0][1] if svc else None, latency_ms=ms, keys=[m.key for m in sms])

    def messages(self, state):
        return self._parse_sms(self._get(state["ref"])[0])
