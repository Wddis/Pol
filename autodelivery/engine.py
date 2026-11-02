"""Движок авто-выдачи: ловит оплаченные сделки Playerok и выдаёт товар.

Источники сделок два, чтобы ничего не терялось:
  * WebSocket-подписки (быстро, секунды);
  * периодический опрос списка сделок (страховка на случай обрыва
    подписки или простоя бота — догоняет пропущенное).
Повторная обработка безопасна: Storage.claim выдаёт сделку один раз.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable

from PlayerokAPI import Account, Deal, DealEvent, EventType, ItemDealDirection, ItemDealStatus
from PlayerokAPI import MessageEvent

from .cards import Card, Catalog, render
from .storage import Storage

log = logging.getLogger("autodelivery")

Notify = Callable[[str], Awaitable[None]]


class Engine:
    def __init__(
        self,
        account: Account,
        storage: Storage,
        catalog: Catalog,
        notify: Notify,
        *,
        poll_interval: float = 20.0,
    ) -> None:
        self.account = account
        self.storage = storage
        self.catalog = catalog
        self.notify = notify
        self.poll_interval = poll_interval
        self._me: str | None = None
        self._deal_lock = asyncio.Lock()

    @property
    def paused(self) -> bool:
        return self.storage.get_flag("paused") == "1"

    def set_paused(self, value: bool) -> None:
        self.storage.set_flag("paused", "1" if value else "0")

    # --- запуск ------------------------------------------------------------

    async def run(self) -> None:
        me = await self.account.get_me()
        self._me = me.id
        log.info("Вошли в Playerok как %s", me.username)
        await self.notify(f"🟢 Авто-выдача запущена. Аккаунт: {me.username}")
        await asyncio.gather(self._listen_forever(), self._poll_forever())

    async def _listen_forever(self) -> None:
        while True:
            try:
                listener = self.account.listener(
                    events=[EventType.NEW_DEAL, EventType.DEAL_UPDATED, EventType.NEW_MESSAGE]
                )
                async for event in listener.events():
                    if isinstance(event, DealEvent) and event.deal:
                        await self.handle_deal(event.deal)
                    elif isinstance(event, MessageEvent) and event.message:
                        await self.handle_message(event)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                log.warning("Подписки упали (%s), переподключаюсь через 10 с", exc)
            await asyncio.sleep(10)

    async def _poll_forever(self) -> None:
        while True:
            try:
                await self.poll_once()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                log.warning("Опрос сделок не удался: %s", exc)
            await asyncio.sleep(self.poll_interval)

    async def poll_once(self) -> None:
        # Схема ItemDealFilter неофициальная: пробуем варианты и запоминаем рабочий.
        variants = [
            {"userId": self._me, "direction": ItemDealDirection.OUT.value},
            {"userId": self._me},
            {},
        ]
        if getattr(self, "_filter", None) is not None:
            variants = [self._filter]
        last_exc: Exception | None = None
        for flt in variants:
            try:
                page = await self.account.deals.search(filter=flt, first=20)
                self._filter = flt
                break
            except Exception as exc:
                last_exc = exc
        else:
            raise RuntimeError(f"ни один фильтр сделок не подошёл: {last_exc}")
        for deal in page.items:
            if deal.status == ItemDealStatus.PAID:
                await self.handle_deal(deal)

    # --- сделки ------------------------------------------------------------

    async def handle_deal(self, deal: Deal) -> None:
        if deal.direction == ItemDealDirection.IN:
            return  # это наша покупка, не продажа
        try:
            if deal.status == ItemDealStatus.PAID:
                await self.deliver(deal)
            elif deal.status == ItemDealStatus.CONFIRMED:
                await self._on_confirmed(deal)
            elif deal.status == ItemDealStatus.ROLLED_BACK:
                await self._on_rolled_back(deal)
        except Exception as exc:
            log.exception("Ошибка обработки сделки %s", deal.id)
            await self.notify(f"⚠️ Ошибка по сделке {deal.url}: {exc}")

    async def deliver(self, deal: Deal) -> None:
        if self.storage.get_delivery(deal.id) is not None:
            return  # уже обрабатывали — быстрый выход без запросов
        if self.paused:
            log.info("Пауза: сделку %s не трогаю", deal.id)
            return

        async with self._deal_lock:
            if not deal.chat_id or not deal.item:
                deal = await self.account.deals.get(deal.id)
            item_name = deal.item.name if deal.item else ""
            item_id = deal.item.id if deal.item else None
            price = deal.item.price if deal.item else None
            buyer = deal.user.username if deal.user else None
            card, candidates = self.catalog.find(item_name or "", item_id)

            if card is None:
                self.storage.record(
                    deal.id, status="unmatched", chat_id=deal.chat_id, buyer=buyer,
                    item_name=item_name, price=price,
                )
                why = (
                    "несколько карточек подходят одинаково: "
                    + ", ".join(c.id for c in candidates)
                    if candidates
                    else "ни одна карточка не подошла"
                )
                await self.notify(
                    f"❓ Продажа без авто-выдачи: «{item_name}» ({price} ₽), покупатель {buyer}\n"
                    f"Причина: {why}\n{deal.url}\n"
                    f"Выдать вручную: /give {deal.id} <id_карточки>"
                )
                return

            await self._fulfil(card, deal.id, deal.chat_id, buyer, item_name, price)

    async def _fulfil(
        self,
        card: Card,
        deal_id: str,
        chat_id: str | None,
        buyer: str | None,
        item_name: str | None,
        price: float | None,
        *,
        retry: bool = False,
    ) -> None:
        pool = card.pool if card.type == "stock" else None
        claimed = self.storage.claim(
            deal_id=deal_id, card_id=card.id, chat_id=chat_id, buyer=buyer,
            item_name=item_name, price=price, pool=pool, quantity=card.quantity,
            retry_statuses=("waiting_stock", "unmatched") if retry else (),
        )
        if claimed is None:
            return
        status, units = claimed
        deal_url = f"https://playerok.com/deal/{deal_id}"

        if status == "waiting_stock":
            if not retry and self.catalog.settings.out_of_stock_message:
                await self._send(chat_id, self.catalog.settings.out_of_stock_message)
            await self.notify(
                f"📭 Склад «{pool}» пуст: «{item_name}» ждёт {card.quantity} шт.\n"
                f"Покупатель {buyer}, {deal_url}\n"
                f"Пополните /add {pool} — заказ выдастся автоматически."
            )
            return

        text = self._render(card, units, buyer=buyer, item=item_name, deal_url=deal_url)
        try:
            await self._send(chat_id, text)
        except Exception as exc:
            self.storage.record(deal_id, status="failed", error=str(exc))
            await self.notify(
                f"⚠️ Не смог отправить товар по {deal_url}: {exc}\n"
                f"Строки уже списаны со склада. Повторить: /resend {deal_id}"
            )
            return

        if card.type == "manual":
            self.storage.record(deal_id, status="manual", message=text)
            await self.notify(
                f"✋ Ручной заказ «{item_name}» ({price} ₽) от {buyer}\n{deal_url}\n"
                "Инструкция покупателю отправлена — дождитесь данных в чате."
            )
            return

        self.storage.record(deal_id, status="delivered", message=text, error=None)
        if self.catalog.settings.mark_sent:
            await self._mark_sent(deal_id)

        left = self.storage.count_free(pool) if pool else None
        tail = f"\nОсталось в «{pool}»: {left}" if pool else ""
        await self.notify(f"✅ Выдано «{item_name}» → {buyer} ({price} ₽){tail}\n{deal_url}")
        if pool and left is not None and left <= card.low_stock:
            await self.notify(f"🔻 Мало товара в «{pool}»: {left} шт. Пополните /add {pool}")

    def _render(self, card: Card, units: list[str], **extra: object) -> str:
        parsed = [card.parse_line(u) or {"data": u} for u in units]
        values: dict[str, object] = dict(parsed[0]) if parsed else {}
        values["units"] = "\n".join(units)
        values["quantity"] = card.quantity
        values.update(extra)
        return render(card.delivery, **values).strip()

    async def _send(self, chat_id: str | None, text: str) -> None:
        if not chat_id:
            raise RuntimeError("у сделки нет чата")
        await self.account.chats.send(chat_id, text)

    async def _mark_sent(self, deal_id: str) -> None:
        try:
            await self.account.deals.update(deal_id, {"status": ItemDealStatus.SENT.value})
        except Exception as exc:
            log.warning("Не удалось отметить сделку %s отправленной: %s", deal_id, exc)
            await self.notify(
                f"ℹ️ Товар выдан, но сделку не удалось отметить «отправлено» "
                f"({exc}). Отметьте вручную: https://playerok.com/deal/{deal_id}"
            )

    async def _on_confirmed(self, deal: Deal) -> None:
        key = f"thanks:{deal.id}"
        if self.storage.get_flag(key):
            return
        self.storage.set_flag(key, "1")
        if self.storage.get_delivery(deal.id) and self.catalog.settings.thanks_message:
            chat_id = deal.chat_id or (await self.account.deals.get(deal.id)).chat_id
            await self._send(chat_id, self.catalog.settings.thanks_message.strip())

    async def _on_rolled_back(self, deal: Deal) -> None:
        key = f"rollback:{deal.id}"
        if self.storage.get_flag(key):
            return
        self.storage.set_flag(key, "1")
        units = self.storage.units_for_deal(deal.id)
        note = f"\nВыданные строки ({len(units)}) уже у покупателя — проверьте их." if units else ""
        await self.notify(f"↩️ Возврат по сделке {deal.url}{note}")

    # --- сообщения покупателей ---------------------------------------------

    async def handle_message(self, event: MessageEvent) -> None:
        msg = event.message
        if not msg or not msg.text or (msg.user and msg.user.id == self._me):
            return
        if msg.is_auto_response or not msg.user:
            return  # системные сообщения площадки
        text = msg.text.lower()
        if any(word.lower() in text for word in self.catalog.settings.alert_keywords):
            await self.notify(
                f"💬 {msg.user.username}: {msg.text[:500]}\n"
                f"Чат: https://playerok.com/chats/{msg.chat_id}"
            )
            if self.catalog.settings.help_message and ("продавец" in text or "помощь" in text):
                await self._send(msg.chat_id, self.catalog.settings.help_message.strip())

    # --- для админ-бота ----------------------------------------------------

    async def retry_waiting(self) -> int:
        """Выдать заказы, ждавшие пополнения склада. Возвращает число выданных."""
        done = 0
        async with self._deal_lock:
            for d in self.storage.deliveries_by_status("waiting_stock"):
                card = self.catalog.get(d.card_id or "")
                if card is None:
                    continue
                await self._fulfil(
                    card, d.deal_id, d.chat_id, d.buyer, d.item_name, d.price, retry=True
                )
                if (rec := self.storage.get_delivery(d.deal_id)) and rec.status == "delivered":
                    done += 1
        return done

    async def give(self, deal_id: str, card: Card) -> str:
        """Ручная выдача по сделке, которую не распознали автоматически."""
        deal = await self.account.deals.get(deal_id)
        async with self._deal_lock:
            await self._fulfil(
                card, deal.id, deal.chat_id,
                deal.user.username if deal.user else None,
                deal.item.name if deal.item else None,
                deal.item.price if deal.item else None,
                retry=True,
            )
        rec = self.storage.get_delivery(deal_id)
        return rec.status if rec else "?"

    async def resend(self, deal_id: str) -> bool:
        rec = self.storage.get_delivery(deal_id)
        if rec is None:
            return False
        text = rec.message
        if not text:
            card = self.catalog.get(rec.card_id or "")
            units = self.storage.units_for_deal(deal_id)
            if card is None or (card.type == "stock" and not units):
                return False
            text = self._render(
                card, units, buyer=rec.buyer, item=rec.item_name,
                deal_url=f"https://playerok.com/deal/{deal_id}",
            )
        await self._send(rec.chat_id, text)
        self.storage.record(deal_id, status="delivered", message=text, error=None)
        if self.catalog.settings.mark_sent:
            await self._mark_sent(deal_id)
        return True
