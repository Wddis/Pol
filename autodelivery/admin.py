"""Telegram-бот администратора: склад, статистика, ручные действия."""

from __future__ import annotations

import io
import logging

from telegram import Update
from telegram.constants import ParseMode
from telegram.ext import (
    Application,
    ApplicationBuilder,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    filters,
)

from .cards import Catalog
from .engine import Engine
from .storage import Storage

log = logging.getLogger("autodelivery.admin")

HELP = """<b>Авто-выдача Playerok</b>

<b>Склад</b>
/stock — остатки по складам
/add &lt;склад&gt; — пополнить: строки следующим сообщением или .txt файлом
  (можно сразу: /add roblox_empty и строки с новой строки)
/done — закончить пополнение
/format &lt;склад&gt; — какой формат строк ждёт склад
/clear &lt;склад&gt; — снять все свободные строки (пришлю файлом)

<b>Карточки</b>
/cards — все карточки
/listing &lt;карточка&gt; — готовый текст для объявления на Playerok
/reload — перечитать cards.yaml

<b>Сделки</b>
/sales — последние выдачи и статистика
/waiting — заказы, которые ждут товар / ручной выдачи
/give &lt;deal_id&gt; &lt;карточка&gt; — выдать вручную
/resend &lt;deal_id&gt; — отправить товар повторно
/pause, /resume — остановить / включить авто-выдачу"""


def build_admin(
    token: str, admin_ids: set[int], engine: Engine, storage: Storage, cards_path: str
) -> tuple[Application, callable]:
    app = ApplicationBuilder().token(token).build()
    admin_only = filters.User(user_id=list(admin_ids))

    async def notify(text: str) -> None:
        for uid in admin_ids:
            try:
                await app.bot.send_message(uid, text, disable_web_page_preview=True)
            except Exception as exc:  # админ мог не нажать /start
                log.warning("Не отправил уведомление %s: %s", uid, exc)

    def catalog() -> Catalog:
        return engine.catalog

    async def reply(update: Update, text: str, html: bool = False) -> None:
        await update.effective_message.reply_text(
            text,
            parse_mode=ParseMode.HTML if html else None,
            disable_web_page_preview=True,
        )

    async def cmd_help(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        await reply(update, HELP, html=True)

    async def cmd_stock(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        counts = storage.counts()
        lines = ["📦 Склады (свободно / продано):"]
        for pool, card in catalog().pools().items():
            free, sold = counts.get(pool, (0, 0))
            users = [c.id for c in catalog().cards if c.pool == pool]
            mark = "🔴" if free == 0 else ("🟡" if free <= card.low_stock else "🟢")
            lines.append(f"{mark} {pool}: {free} / {sold}  ← {', '.join(users)}")
        state = "⏸ ПАУЗА" if engine.paused else "▶️ работает"
        lines.append(f"\nАвто-выдача: {state}")
        await reply(update, "\n".join(lines))

    async def cmd_cards(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        lines = []
        for c in catalog().cards:
            kind = {"stock": f"склад {c.pool} ×{c.quantity}", "static": "без склада",
                    "manual": "ручная"}[c.type]
            rules = " | ".join(" + ".join(r) for r in c.match) or "по item_ids"
            lines.append(f"• {c.id} — {c.title}\n   {kind}; слова: {rules}")
        await reply(update, "\n".join(lines) or "Карточек нет")

    async def cmd_listing(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        card = catalog().get(ctx.args[0]) if ctx.args else None
        if card is None:
            await reply(update, "Укажите карточку: /listing roblox_empty  (список — /cards)")
            return
        name = card.listing.get("name", card.title)
        desc = card.listing.get("description", "").strip()
        await reply(update, f"Название:\n{name}\n\nОписание:\n{desc}")

    async def cmd_format(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        card = catalog().pools().get(ctx.args[0]) if ctx.args else None
        if card is None:
            await reply(update, "Склады: " + ", ".join(catalog().pools()))
            return
        await reply(update, f"Формат строки «{card.pool}»:\n" + _example(card))

    async def cmd_add(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        pool = ctx.args[0] if ctx.args else None
        if pool not in catalog().pools():
            await reply(update, "Укажите склад: /add <склад>\nСклады: " + ", ".join(catalog().pools()))
            return
        ctx.user_data["add_pool"] = pool
        text = update.effective_message.text or ""
        rest = text.split("\n", 1)[1] if "\n" in text else ""
        if rest.strip():
            await _ingest(update, pool, rest)
        else:
            await reply(
                update,
                f"Жду строки для «{pool}» (сообщением или .txt). Формат:\n"
                f"{_example(catalog().pools()[pool])}\n\nЗакончить — /done",
            )

    async def cmd_done(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        ctx.user_data.pop("add_pool", None)
        await reply(update, "Пополнение закончено.")

    async def _ingest(update: Update, pool: str, text: str) -> None:
        card = catalog().pools()[pool]
        good, bad = [], []
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            (good if card.parse_line(line) else bad).append(line)
        added, dupes = storage.add_stock(pool, good)
        msg = f"➕ «{pool}»: добавлено {added}"
        if dupes:
            msg += f", дубликатов пропущено {dupes}"
        if bad:
            sample = "\n".join(bad[:5])
            msg += f"\n❌ Не по формату {len(bad)} строк, например:\n{sample}\nНужно: {_example(card)}"
        msg += f"\nТеперь свободно: {storage.count_free(pool)}"
        await reply(update, msg)
        if added:
            done = await engine.retry_waiting()
            if done:
                await reply(update, f"📬 Выданы ожидавшие заказы: {done}")

    async def on_text(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        pool = ctx.user_data.get("add_pool")
        if not pool:
            await reply(update, "Команды — /help")
            return
        await _ingest(update, pool, update.effective_message.text or "")

    async def on_document(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        pool = ctx.user_data.get("add_pool") or _pool_from_caption(update)
        if not pool or pool not in catalog().pools():
            await reply(update, "Сначала /add <склад>, затем пришлите файл (или подпись файла = склад)")
            return
        doc = update.effective_message.document
        if doc.file_size and doc.file_size > 5 * 1024 * 1024:
            await reply(update, "Файл больше 5 МБ — разбейте на части")
            return
        data = await (await doc.get_file()).download_as_bytearray()
        await _ingest(update, pool, bytes(data).decode("utf-8-sig", errors="replace"))

    async def cmd_clear(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        pool = ctx.args[0] if ctx.args else None
        if pool not in catalog().pools():
            await reply(update, "Укажите склад: /clear <склад>")
            return
        if len(ctx.args) < 2 or ctx.args[1] != "да":
            await reply(update, f"Снять ВСЕ свободные строки «{pool}»? Подтвердите: /clear {pool} да")
            return
        lines = storage.clear_free(pool)
        if not lines:
            await reply(update, "Склад и так пуст")
            return
        buf = io.BytesIO("\n".join(lines).encode())
        buf.name = f"{pool}_removed.txt"
        await update.effective_message.reply_document(buf, caption=f"Снято {len(lines)} строк")

    async def cmd_sales(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        s = storage.stats()
        lines = [
            f"📊 Сегодня: {s['today']} выдач на {s['today_sum']:.0f} ₽",
            f"Всего: {s['total']} выдач на {s['total_sum']:.0f} ₽",
            "",
        ]
        icons = {"delivered": "✅", "waiting_stock": "📭", "manual": "✋",
                 "unmatched": "❓", "failed": "⚠️"}
        for d in storage.recent(10):
            lines.append(
                f"{icons.get(d.status, '•')} {d.created_at[5:16]} {d.item_name or '?'} "
                f"→ {d.buyer or '?'} ({d.price or 0:.0f} ₽)\n   {d.deal_id}"
            )
        await reply(update, "\n".join(lines))

    async def cmd_waiting(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        rows = storage.deliveries_by_status("waiting_stock", "unmatched", "manual", "failed")
        if not rows:
            await reply(update, "Ожидающих заказов нет 👌")
            return
        lines = [
            f"[{d.status}] {d.item_name} → {d.buyer}\n  https://playerok.com/deal/{d.deal_id}"
            for d in rows[-30:]
        ]
        await reply(update, "\n".join(lines))

    async def cmd_give(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        if len(ctx.args) != 2 or catalog().get(ctx.args[1]) is None:
            await reply(update, "Использование: /give <deal_id> <карточка>")
            return
        status = await engine.give(ctx.args[0], catalog().get(ctx.args[1]))
        await reply(update, f"Готово, статус сделки: {status}")

    async def cmd_resend(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        if not ctx.args:
            await reply(update, "Использование: /resend <deal_id>")
            return
        ok = await engine.resend(ctx.args[0])
        await reply(update, "Отправлено повторно ✅" if ok else "Нечего отправлять по этой сделке")

    async def cmd_pause(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        engine.set_paused(True)
        await reply(update, "⏸ Авто-выдача на паузе. Новые оплаты будут ждать /resume.")

    async def cmd_resume(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        engine.set_paused(False)
        await reply(update, "▶️ Авто-выдача включена. Проверяю пропущенные сделки…")
        await engine.poll_once()

    async def cmd_reload(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
        try:
            engine.catalog = Catalog.load(cards_path)
        except Exception as exc:
            await reply(update, f"❌ cards.yaml не загружен, оставил старые карточки:\n{exc}")
            return
        await reply(update, f"🔄 Загружено карточек: {len(engine.catalog.cards)}")

    handlers = [
        ("start", cmd_help), ("help", cmd_help), ("stock", cmd_stock), ("cards", cmd_cards),
        ("listing", cmd_listing), ("format", cmd_format), ("add", cmd_add), ("done", cmd_done),
        ("clear", cmd_clear), ("sales", cmd_sales), ("waiting", cmd_waiting), ("give", cmd_give),
        ("resend", cmd_resend), ("pause", cmd_pause), ("resume", cmd_resume),
        ("reload", cmd_reload),
    ]
    for name, fn in handlers:
        app.add_handler(CommandHandler(name, fn, filters=admin_only))
    app.add_handler(MessageHandler(admin_only & filters.Document.ALL, on_document))
    app.add_handler(MessageHandler(admin_only & filters.TEXT & ~filters.COMMAND, on_text))
    return app, notify


def _example(card) -> str:
    sep = "TAB" if card.separator == "\t" else card.separator
    if len(card.fields) == 1:
        return f"{card.fields[0]} — одна строка = одна единица"
    return sep.join(card.fields) + "   (одна строка = одна единица)"


def _pool_from_caption(update: Update) -> str | None:
    caption = (update.effective_message.caption or "").strip()
    return caption.split()[0] if caption else None
