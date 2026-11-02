"""Запуск: python -m autodelivery"""

from __future__ import annotations

import asyncio
import logging

from PlayerokAPI import Account

from .admin import build_admin
from .cards import Catalog
from .config import Config
from .engine import Engine
from .storage import Storage


async def main() -> None:
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)
    cfg = Config.from_env()
    catalog = Catalog.load(cfg.cards_path)
    storage = Storage(cfg.db_path)

    async with Account(cfg.playerok_token, proxy=cfg.proxy) as account:
        sink: dict[str, object] = {}

        async def notify(text: str) -> None:
            await sink["notify"](text)

        engine = Engine(account, storage, catalog, notify, poll_interval=cfg.poll_interval)
        app, sink["notify"] = build_admin(
            cfg.telegram_token, cfg.admin_ids, engine, storage, cfg.cards_path
        )
        async with app:
            await app.start()
            await app.updater.start_polling(drop_pending_updates=True)
            try:
                await engine.run()
            finally:
                await app.updater.stop()
                await app.stop()


if __name__ == "__main__":
    asyncio.run(main())
