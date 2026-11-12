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
    max_check = 50

    def __init__(self):
        super().__init__()
        self.s = http()

    def _get(self, path: str) -> tuple[BeautifulSoup, int]:
        t0 = time.time()
        r = self.s.get(urljoin(BASE, path), timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"HTTP {r.status_code}")
        return BeautifulSoup(r.text, "html.parser"), int((time.time() - t0) * 1000)

    def listing(self, country, mode):
        iso = country.lower().strip() if country and len(country.strip()) == 2 else ""
        pages = ([f"/free-phone-numbers?country={iso}"] if iso else
                 ["/new-numbers", "/free-phone-numbers", "/free-phone-numbers?page=2", "/new-numbers?page=2"])
        seen, out = set(), []
        for path in pages:
            try:
                soup, _ = self._get(path)
            except Exception:
                continue
            for a in soup.select("a[href^='/number/']"):
                m = _NUM.match(a.get("href", ""))
                if not m or m.group(1) in seen:
                    continue
                num = m.group(1)
                seen.add(num)
                ciso = _iso_for(num)
                if not country_matches(country, ciso, COUNTRIES.get(ciso, ("",))[0]):
                    continue
                out.append(Candidate(provider=self.name, number="+" + num, country=ciso.upper(),
                                     ref=m.group(0), price=0.0, public=True,
                                     extra={"fresh_list": path.startswith("/new-numbers")}))
        if not out and not country:
            raise ProviderError(f"{self.name}: не удалось получить список номеров")
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
