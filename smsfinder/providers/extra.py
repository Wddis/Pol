"""Ещё 10 бесплатных сайтов с публичными номерами — один настраиваемый провайдер.

Как отбирались (ноябрь 2026). Кандидаты — из GitHub-проектов и списков:
iP1SMS/disposable-phone-numbers (source.csv, 569 адресов), Shelex/free-otp-api,
c0dejump/scripts (sms4me.py), mishakorzik/VirtualPhoneNumber — и из проверки похожих доменов.
Каждый кандидат проверен с сервера:

* отвечает без защиты Cloudflare и отдаёт SMS прямо в HTML (без JavaScript);
* robots.txt не запрещает страницы номеров для обычных клиентов (User-agent: *);
* SMS не замаскированы и не зашифрованы, и на номерах есть свежие SMS.

Отброшены, в частности: receive-sms-online.info и sms-receive.net (SMS выдаются только
с одноразовым ключом — защита от скрапинга), mytempsms.com (цифры зашифрованы шрифтом),
freereceivesms.com (тексты зашифрованы), tempsmss.com (коды скрыты «••••••»),
smss.net (robots.txt запрещает /number/), zusms.com и storytrain.info (номера не обновлялись
месяцами), temp-number.com, lothelper.com, quackr.io (номера подгружает JavaScript),
sms24.me, receive-sms.cc, smstome.com, 7sim и др. (Cloudflare 403).

Сайт описывается конфигом: где взять список номеров, как из ссылки достать номер
и какие селекторы у отправителя, времени и текста SMS.
"""
from __future__ import annotations

import re
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

from ..core import (TIMEOUT, Candidate, Health, ProviderError, Sms, ago_str, ago_to_seconds,
                    extract_code, http)
from ..fast import Soup
from ..services import COUNTRIES, country_matches, service_matchers
from .asms import _iso_for
from .base import FreeProvider


class HtmlSite(FreeProvider):
    """Провайдер для сайта «список номеров → страница номера с лентой SMS»."""

    base = ""
    start = ["/"]          # страницы, с которых собираются номера
    follow = None          # regex ссылок на страницы стран/списков, найденных на start
    follow_max = 6         # сколько таких страниц открывать (параллельно)
    num_re = r""           # regex для href ссылки на номер; группа 1 — цифры номера
    item = ""              # селектор одного SMS
    sender = ""            # селекторы внутри SMS
    when = ""
    text = ""
    when_attr = ""         # время как unix-метка в атрибуте элемента SMS (точнее, чем «5 min ago»)
    strip = ()             # хвосты, которые сайт дописывает в текст SMS
    max_check = 40

    def __init__(self):
        super().__init__()
        self.s = http()
        self._num = re.compile(self.num_re)

    def _get(self, path):
        t0 = time.time()
        r = self.s.get(urljoin(self.base + "/", path), timeout=TIMEOUT)
        if r.status_code != 200:
            raise ProviderError(f"HTTP {r.status_code}")
        return Soup(r.text), int((time.time() - t0) * 1000)

    def _numbers_on(self, soup, seen, out, country):
        for a in soup.select("a[href]"):
            href = a.get("href", "")
            m = self._num.search(href)
            if not m:
                continue
            num = re.sub(r"\D", "", m.group(1))
            if len(num) < 8 or num in seen:
                continue
            seen.add(num)
            iso = _iso_for(num)
            if not country_matches(country, iso, COUNTRIES.get(iso, ("",))[0]):
                continue
            out.append(Candidate(provider=self.name, number="+" + num, country=iso.upper(),
                                 ref=urljoin(self.base + "/", href), price=0.0, public=True))

    def listing(self, country, mode):
        def fetch(path):
            try:
                return self._get(path)[0]
            except Exception:
                return None
        with ThreadPoolExecutor(max_workers=max(1, len(self.start))) as ex:
            first = list(ex.map(fetch, self.start))
        seen, out = set(), []
        more = []
        for soup in first:
            if soup is None:
                continue
            self._numbers_on(soup, seen, out, country)
            if self.follow:
                rx = re.compile(self.follow)
                for a in soup.select("a[href]"):
                    h = a.get("href", "")
                    if rx.search(h) and h not in more and h not in self.start:
                        more.append(h)
        if more:
            with ThreadPoolExecutor(max_workers=self.follow_max) as ex:
                for soup in ex.map(fetch, more[: self.follow_max]):
                    if soup is not None:
                        self._numbers_on(soup, seen, out, country)
        if not out and not country:
            raise ProviderError(f"{self.name}: не удалось получить список номеров")
        return out

    def _clean(self, s: str) -> str:
        for tail in self.strip:
            s = s.replace(tail, "")
        return re.sub(r"\s+", " ", s).strip()

    def parse_sms(self, soup) -> list[Sms]:
        out = []
        now = time.time()
        for it in soup.select(self.item):
            body = it.select_one(self.text) if self.text else it
            if body is None:
                continue
            txt = self._clean(body.get_text(" ", strip=True))
            if not txt or txt.lower() in ("message", "сообщение"):
                continue  # строка-заголовок таблицы или реклама
            snd = it.select_one(self.sender) if self.sender else None
            sender = self._clean(snd.get_text(" ", strip=True)) if snd else "?"
            received = ""
            if self.when_attr and it.get(self.when_attr, "").isdigit():
                received = ago_str(now - int(it.get(self.when_attr)))
            elif self.when:
                w = it.select_one(self.when)
                received = w.get_text(" ", strip=True) if w else ""
            out.append(Sms(sender=sender or "?", text=txt, code=extract_code(txt), received=received))
        return out

    def health(self, cand, service):
        try:
            soup, ms = self._get(cand.ref)
        except Exception as e:
            return Health(ok=False, reason=f"страница недоступна ({e})")
        sms = self.parse_sms(soup)
        if not sms:
            return Health(ok=False, reason="ни одного SMS — не подтверждено, что номер принимает", latency_ms=ms)
        ages = [ago_to_seconds(m.received) for m in sms]
        known = [a for a in ages if a is not None]
        matchers = service_matchers(service)
        svc = [a for m, a in zip(sms, ages)
               if matchers and any(k in (m.sender + " " + m.text).lower() for k in matchers)]
        svc_known = [a for a in svc if a is not None]
        return Health(ok=True, last_s=min(known) if known else None,
                      usage=sum(1 for a in known if a < 86400) or len(sms),
                      services=len({m.sender.lower() for m in sms}), service_seen=len(svc),
                      service_last_s=min(svc_known) if svc_known else None, latency_ms=ms,
                      keys=[m.key for m in sms])

    def messages(self, state):
        return self.parse_sms(self._get(state["ref"])[0])


# --- движок «yunjiema»: строки div.row.border-bottom.table-hover ------------------
class _Yunjiema(HtmlSite):
    item = "div.row.border-bottom.table-hover"
    sender = "div.mobile_hide"                  # первый столбец (в мобильной версии — «From …»)
    when = "div.col-md-2:nth-child(2)"
    text = "div.col-md-8"


class YunjiemaNet(_Yunjiema):
    name = "yunjiema"
    title = "yunjiema.net (бесплатно, публично)"
    base = "https://yunjiema.net"
    follow = r"^/[a-z]+haoma/$"                 # страницы стран: /meiguohaoma/ (США), /yingguohaoma/ ...
    num_re = r"/[a-z]+haoma/(\d{8,15})/?$"


class YunjiemaTop(_Yunjiema):
    name = "yunjiematop"
    title = "yunjiema.top (бесплатно, публично)"
    base = "https://www.yunjiema.top"
    follow = r"^/country/[a-z_]+/1\.html$"
    num_re = r"^/message/(\d{8,15})\.html$"


class FreeSmsReceive(_Yunjiema):
    name = "freesmsreceive"
    title = "free-sms-receive.com (бесплатно, публично)"
    base = "https://www.free-sms-receive.com"
    follow = r"^/country/[a-z_]+/1\.html$"
    num_re = r"^/message/(\d{8,15})\.html$"


class SuperCloudSms(HtmlSite):
    name = "supercloudsms"
    title = "supercloudsms.com (бесплатно, публично)"
    base = "https://www.supercloudsms.com"
    start = ["/en/"]
    num_re = r"^/en/message/(\d{8,15})\.html$"
    item = "div.card.mb-3"
    sender = ".card-header .float-end"
    when = ".card-header .badge"
    text = ".card-body"


class MianfeiJiema(HtmlSite):
    name = "mianfeijiema"
    title = "mianfeijiema.com (бесплатно, публично)"
    base = "https://mianfeijiema.com"
    num_re = r"^/sms/(\d{8,15})$"
    item = "div.message_details"
    sender = ".sender"
    when = ".time"
    text = ".msg"


class ReceiveSmssLive(HtmlSite):
    name = "receivesmsslive"
    title = "receive-smss.live (бесплатно, публично)"
    base = "https://receive-smss.live"
    follow = r"^/sms/[a-z]{2}$"                 # страницы стран /sms/us, /sms/de ...
    follow_max = 8
    num_re = r"^/sms/[a-z]{2}/(\d{8,15})$"
    item = "li"
    sender = "div.min-w-0 > p:nth-child(1)"
    when = "div.min-w-0 > p:nth-child(2)"
    text = "div.p-4 p"

    def parse_sms(self, soup):  # в <li> есть и пункты меню — берём только карточки SMS
        return [m for m in super().parse_sms(soup) if m.received]


class TempNumbersNet(HtmlSite):
    name = "tempnumbers"
    title = "temp-numbers.net (бесплатно, публично)"
    base = "https://temp-numbers.net"
    start = ["/countries/united-states", "/countries/united-kingdom", "/countries/canada", "/countries/sweden",
             "/countries/poland", "/countries/india", "/countries/australia", "/countries/brazil"]
    num_re = r"^/countries/[a-z-]+/(\d{8,15})$"
    item = "div.min-w-0.flex-1"
    sender = "h4"
    when = "span.shrink-0"
    text = "p"

    def parse_sms(self, soup):  # такой же блок есть в шапке страницы (с <h1>) — у SMS всегда <h4>
        return [m for m in super().parse_sms(soup) if m.sender != "?"]


class ReceiveSmsNet(HtmlSite):
    name = "receivesmsnet"
    title = "receivesms.net (бесплатно, публично, Румыния)"
    base = "https://www.receivesms.net"
    num_re = r"^\+?(\d{8,15})\.php$"
    item = "tbody tr"
    sender = "td:nth-child(2)"
    when = "td:nth-child(3)"
    text = "td:nth-child(4)"
    strip = ("receivesms.net",)


class ReceiveSmsIo(HtmlSite):
    name = "receivesmsio"
    title = "receive-sms.io (бесплатно, публично, точное время SMS)"
    base = "https://receive-sms.io"
    follow = r"^https://receive-sms\.io/temporary-numbers/[a-z-]+/$"
    follow_max = 6
    num_re = r"/temporary-numbers/[a-z-]+/(\d{8,15})/?$"
    item = ".sms-item"
    sender = ".left"
    text = ".center"
    when_attr = "data-time"


class TempSmsOrg(HtmlSite):
    """Лента — плоская последовательность блоков: отправитель, время, текст. Перед видимым
    текстом стоит скрытая (display:none) копия с IP-адресом — её пропускаем."""

    name = "tempsmsorg"
    title = "temp-sms.org (бесплатно, публично; много старых номеров)"
    base = "https://temp-sms.org"
    num_re = r"^/sms/(\d{8,15})$"

    def parse_sms(self, soup):
        box = soup.select_one("#messages")
        if box is None:
            return []
        out, sender, when = [], "?", ""
        for div in box.select("div"):
            cls = div.get("class", "")
            if "date-padding" in cls:
                when = div.get_text(" ", strip=True).split(" - ")[0]
            elif "msg-padding" in cls:
                if "display:none" in (div.get("style", "") or "").replace(" ", ""):
                    continue
                txt = div.get_text(" ", strip=True)
                if txt:
                    out.append(Sms(sender=sender, text=txt, code=extract_code(txt), received=when))
            else:
                b = div.select_one("b")
                if b is not None and "col-sm-10" in cls:
                    sender = b.get_text(strip=True)
        return out


EXTRA = (YunjiemaNet, YunjiemaTop, FreeSmsReceive, SuperCloudSms, MianfeiJiema, ReceiveSmssLive,
         TempNumbersNet, ReceiveSmsNet, ReceiveSmsIo, TempSmsOrg)
