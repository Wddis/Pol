"""Запуск: python -m oda_bot — бот (если задан BOT_TOKEN) и сервер ключей в одном процессе."""
import asyncio
import logging

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiohttp import web

from .api import KeyApi
from .config import Settings
from .db import Db
from .handlers import make_router
from .license import Signer

log = logging.getLogger("oda_bot")


async def run(cfg: Settings) -> None:
    db = Db(cfg.db_path)
    signer = Signer.load_or_create(cfg.signing_key_path)
    log.info("Открытый ключ лицензий (вшить в приложение): %s", signer.public_b64())

    runner = web.AppRunner(KeyApi(db, signer).app())
    await runner.setup()
    await web.TCPSite(runner, cfg.api_host, cfg.api_port).start()
    log.info("Сервер ключей: http://%s:%d/v1/activate", cfg.api_host, cfg.api_port)

    try:
        if not cfg.bot_token:
            log.warning("BOT_TOKEN не задан: работает только сервер ключей")
            await asyncio.Event().wait()
        bot = Bot(cfg.bot_token, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
        dp = Dispatcher()
        dp.include_router(make_router(cfg, db))
        log.info("Бот запущен%s", " (тестовый режим: ключи без оплаты)" if cfg.free_mode else "")
        await dp.start_polling(bot)
    finally:
        await runner.cleanup()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    asyncio.run(run(Settings.from_env()))


if __name__ == "__main__":
    main()
