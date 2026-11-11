"""Общие типы, HTTP-сессия и вспомогательные функции."""
from __future__ import annotations

import hashlib
import re
import time
import uuid
from dataclasses import asdict, dataclass, field
from typing import Callable, Optional

import requests

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0 Safari/537.36"
)
TIMEOUT = 12

# Страны, номера которых встречаются на бесплатных сайтах чаще всего («заезженные»)
COMMON_COUNTRIES = {"us", "usa", "gb", "uk", "england", "ca", "canada", "in", "india",
                    "de", "germany", "nl", "netherlands", "fr", "france"}


def http() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": USER_AGENT, "Accept-Language": "en-US,en;q=0.9"})
    return s


class ProviderError(Exception):
    pass


@dataclass
class Health:
    """Результат проверки номера на работоспособность."""

    ok: bool
    reason: str = ""
    last_s: Optional[int] = None     # секунд с последнего SMS
    usage: int = 0                   # сколько SMS видно (за 24 ч или в ленте)
    services: int = 0                # разных отправителей/сервисов
    service_seen: int = 0            # SMS от целевого сервиса
    latency_ms: int = 0
    keys: list = field(default_factory=list)  # отпечатки SMS для живой проверки


@dataclass
class Candidate:
    """Номер-кандидат, найденный у провайдера."""

    provider: str
    number: str           # для платных провайдеров до покупки — пусто
    country: str          # ISO2 или название страны
    ref: str              # URL страницы номера / оператор / код страны
    score: float = 0.0    # чем больше, тем лучше
    price: Optional[float] = None
    public: bool = True   # публичный номер: SMS видят все
    stock: Optional[int] = None
    tier: str = "regular"  # niche | premium | regular
    note: str = ""
    checks: list = field(default_factory=list)  # журнал проверок
    extra: dict = field(default_factory=dict)
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])

    def log(self, msg: str):
        self.checks.append(f"{time.strftime('%H:%M:%S')} {msg}")

    def to_dict(self) -> dict:
        return asdict(self)


Emit = Callable[[dict], None]


def noop_emit(_: dict) -> None:
    pass


@dataclass
class Sms:
    sender: str
    text: str
    code: str = ""
    received: str = ""
    fetched_at: float = field(default_factory=time.time)

    @property
    def key(self) -> str:
        return hashlib.sha1(f"{self.sender}|{self.text}".encode()).hexdigest()

    def to_dict(self) -> dict:
        d = asdict(self)
        d["key"] = self.key
        return d


_CODE_PATTERNS = [
    re.compile(r"(?<!\d)(\d{3})[- ](\d{3})(?!\d)"),  # 123-456 / 123 456
    re.compile(r"(?<![\d+])(\d{4,8})(?!\d)"),        # 4–8 цифр подряд
]


def extract_code(text: str) -> str:
    for p in _CODE_PATTERNS:
        m = p.search(text or "")
        if m:
            return "".join(m.groups())
    return ""


_AGO = re.compile(r"(\d+|an?|one)\s*(second|sec|minute|min|hour|day|week|month|year)s?\s+ago", re.I)
_UNIT = {"second": 1, "sec": 1, "minute": 60, "min": 60, "hour": 3600,
         "day": 86400, "week": 604800, "month": 2592000, "year": 31536000}


def ago_to_seconds(s: str) -> Optional[int]:
    """'5 minutes ago' -> 300. None, если не распознано."""
    if not s:
        return None
    if "just now" in s.lower():
        return 0
    m = _AGO.search(s)
    if not m:
        return None
    n = m.group(1).lower()
    n = 1 if n in ("a", "an", "one") else int(n)
    return n * _UNIT[m.group(2).lower()]


def digits(s: str) -> str:
    return re.sub(r"\D", "", s or "")


def fmt_ago(sec: Optional[int]) -> str:
    if sec is None:
        return "?"
    if sec < 3600:
        return f"{sec // 60} мин"
    if sec < 86400:
        return f"{sec // 3600} ч"
    return f"{sec // 86400} дн"


def score_free(h: Health, country: str, mode: str) -> float:
    """Оценка бесплатного номера.

    niche   — редко используемый: мало SMS и сервисов, целевой сервис на номере не
              регистрировался, редкая страна, но номер подтверждённо принимает SMS.
    popular — максимально «живой»: много свежих SMS, сервис уже доставлял сюда.
    """
    recency = 0
    if h.last_s is not None:
        recency = 30 if h.last_s <= 600 else 20 if h.last_s <= 3600 else 10 if h.last_s <= 6 * 3600 else 3
    rare = (country or "").lower() not in COMMON_COUNTRIES
    if mode == "niche":
        s = 100 - min(h.usage, 60) - min(h.services, 15) * 2
        s += 25 if h.service_seen == 0 else -20 * min(h.service_seen, 3)
        s += 15 if rare else 0
        s += recency * 0.5
        return round(s, 1)
    s = min(h.usage, 100) * 0.5 + recency + (30 if h.service_seen else 0)
    return round(s, 1)
