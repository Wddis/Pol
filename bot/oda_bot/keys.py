"""Ключи ODA и тарифы: формат ключа, срок тарифа, цена с учётом устройств."""
import re
import secrets
from dataclasses import dataclass

# Без похожих символов (0/O, 1/I/L, 5/S, 8/B): ключ переписывают руками с экрана
ALPHABET = "ACDEFGHJKMNPQRTUVWXYZ234679"
GROUPS = 3
GROUP_LEN = 4
PREFIX = "ODA"
MIN_DEVICES = 1
MAX_DEVICES = 5


@dataclass(frozen=True)
class Tariff:
    id: str
    title: str
    days: int


TARIFFS = {
    "week": Tariff("week", "1 неделя", 7),
    "month": Tariff("month", "1 месяц", 30),
    "q": Tariff("q", "3 месяца", 90),
}
TRIAL = Tariff("trial", "Пробный день", 1)
ALL_TARIFFS = {**TARIFFS, TRIAL.id: TRIAL}


def generate_key() -> str:
    """«ODA-7K2Q-M9XD-4TPA»: 12 случайных символов, ~57 бит — перебором не подобрать."""
    groups = ["".join(secrets.choice(ALPHABET) for _ in range(GROUP_LEN)) for _ in range(GROUPS)]
    return "-".join([PREFIX, *groups])


_SPLIT = re.compile(r"[\s\-_]+")


def normalize(text: str) -> str | None:
    """Ключ, как его ввели (пробелы, без дефисов, строчные, без «ODA»), — в канонический вид; None — не ключ."""
    raw = _SPLIT.sub("", text or "").upper()
    if raw.startswith(PREFIX):
        raw = raw[len(PREFIX):]
    if len(raw) != GROUPS * GROUP_LEN or any(c not in ALPHABET for c in raw):
        return None
    return "-".join([PREFIX] + [raw[i:i + GROUP_LEN] for i in range(0, len(raw), GROUP_LEN)])


def price(base: int, devices: int, extra_pct: int) -> int:
    """Цена тарифа на devices устройств: каждое сверх первого — +extra_pct% к базовой."""
    devices = max(MIN_DEVICES, min(MAX_DEVICES, devices))
    return round(base * (1 + (devices - 1) * extra_pct / 100))


def plural(n: int, one: str, few: str, many: str) -> str:
    """plural(2, "день", "дня", "дней") → «дня»."""
    n = abs(n) % 100
    if 11 <= n <= 14:
        return many
    n %= 10
    return one if n == 1 else few if 2 <= n <= 4 else many
