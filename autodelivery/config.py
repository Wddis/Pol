"""Настройки из переменных окружения (секреты в коде не храним)."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class Config:
    playerok_token: str
    telegram_token: str
    admin_ids: set[int]
    cards_path: str
    db_path: str
    poll_interval: float
    proxy: str | None

    @classmethod
    def from_env(cls) -> Config:
        missing = [k for k in ("PLAYEROK_TOKEN", "ADMIN_BOT_TOKEN", "ADMIN_IDS") if not os.getenv(k)]
        if missing:
            raise SystemExit(
                "Не заданы переменные окружения: " + ", ".join(missing) + "\nСм. .env.example"
            )
        return cls(
            playerok_token=os.environ["PLAYEROK_TOKEN"],
            telegram_token=os.environ["ADMIN_BOT_TOKEN"],
            admin_ids={int(x) for x in os.environ["ADMIN_IDS"].replace(" ", "").split(",") if x},
            cards_path=os.getenv("CARDS_PATH", "cards.yaml"),
            db_path=os.getenv("DB_PATH", "data/autodelivery.db"),
            poll_interval=float(os.getenv("POLL_INTERVAL", "20")),
            proxy=os.getenv("PLAYEROK_PROXY") or None,
        )
