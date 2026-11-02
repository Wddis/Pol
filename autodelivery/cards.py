"""Карточки авто-выдачи: загрузка из cards.yaml, разбор строк склада, подбор карточки."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

CARD_TYPES = ("stock", "static", "manual")


@dataclass
class Card:
    id: str
    title: str
    type: str
    delivery: str
    pool: str | None = None
    quantity: int = 1
    fields: list[str] = field(default_factory=lambda: ["data"])
    separator: str = ":"
    match: list[list[str]] = field(default_factory=list)
    item_ids: list[str] = field(default_factory=list)
    priority: int = 0
    low_stock: int = 0
    listing: dict[str, str] = field(default_factory=dict)

    def parse_line(self, line: str) -> dict[str, str] | None:
        """Разобрать строку склада в поля; None — если формат не совпал."""
        line = line.strip()
        if not line:
            return None
        if len(self.fields) == 1:
            return {self.fields[0]: line}
        parts = [p.strip() for p in line.split(self.separator)]
        if len(parts) != len(self.fields) or not all(parts):
            return None
        return dict(zip(self.fields, parts))

    def match_score(self, item_name: str, item_id: str | None) -> tuple[int, int] | None:
        """(priority, длина правила) если товар подходит, иначе None."""
        if item_id and item_id in self.item_ids:
            return (10**6, 0)
        name = item_name.lower()
        best = None
        for rule in self.match:
            if all(word.lower() in name for word in rule):
                score = (self.priority, len(rule))
                best = score if best is None or score > best else best
        return best


@dataclass
class Settings:
    mark_sent: bool = True
    out_of_stock_message: str = ""
    alert_keywords: list[str] = field(default_factory=list)
    help_message: str = ""
    thanks_message: str = ""


class CardsError(ValueError):
    pass


@dataclass
class Catalog:
    settings: Settings
    cards: list[Card]

    @classmethod
    def load(cls, path: str | Path) -> Catalog:
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
        settings = Settings(**(raw.get("settings") or {}))
        cards = [_card(c) for c in raw.get("cards") or []]
        ids = [c.id for c in cards]
        dupes = {i for i in ids if ids.count(i) > 1}
        if dupes:
            raise CardsError(f"Повторяются id карточек: {', '.join(sorted(dupes))}")
        _check_pools(cards)
        return cls(settings, cards)

    def get(self, card_id: str) -> Card | None:
        return next((c for c in self.cards if c.id == card_id), None)

    def find(self, item_name: str, item_id: str | None = None) -> tuple[Card | None, list[Card]]:
        """Карточка для проданного товара и список претендентов (для диагностики)."""
        scored = [(s, c) for c in self.cards if (s := c.match_score(item_name, item_id))]
        if not scored:
            return None, []
        scored.sort(key=lambda sc: sc[0], reverse=True)
        top = scored[0][0]
        winners = [c for s, c in scored if s == top]
        return (winners[0] if len(winners) == 1 else None), [c for _, c in scored]

    def pools(self) -> dict[str, Card]:
        """Склад → первая карточка, задающая его формат."""
        result: dict[str, Card] = {}
        for card in self.cards:
            if card.type == "stock" and card.pool and card.pool not in result:
                result[card.pool] = card
        return result


def _card(data: dict[str, Any]) -> Card:
    known = set(Card.__dataclass_fields__)
    unknown = set(data) - known
    if unknown:
        raise CardsError(f"Карточка {data.get('id')}: неизвестные поля {', '.join(sorted(unknown))}")
    for key in ("id", "title", "type", "delivery"):
        if not data.get(key):
            raise CardsError(f"Карточка {data.get('id', '?')}: не задано поле {key}")
    card = Card(**data)
    card.match = [[str(w) for w in rule] for rule in card.match]
    card.item_ids = [str(i) for i in card.item_ids]
    if card.type not in CARD_TYPES:
        raise CardsError(f"Карточка {card.id}: type должен быть одним из {CARD_TYPES}")
    if card.type == "stock" and not card.pool:
        card.pool = card.id
    if card.quantity < 1:
        raise CardsError(f"Карточка {card.id}: quantity должен быть ≥ 1")
    if not card.match and not card.item_ids:
        raise CardsError(f"Карточка {card.id}: нужен match или item_ids")
    return card


def _check_pools(cards: list[Card]) -> None:
    """Карточки на одном складе должны одинаково понимать его строки."""
    seen: dict[str, Card] = {}
    for card in cards:
        if card.type != "stock":
            continue
        first = seen.setdefault(card.pool, card)
        if (first.fields, first.separator) != (card.fields, card.separator):
            raise CardsError(
                f"Карточки {first.id} и {card.id} используют склад {card.pool}, "
                "но у них разные fields/separator"
            )


class _Missing(dict):
    def __missing__(self, key: str) -> str:
        return "{" + key + "}"


def render(template: str, **values: Any) -> str:
    return template.format_map(_Missing(**{k: "" if v is None else v for k, v in values.items()}))
