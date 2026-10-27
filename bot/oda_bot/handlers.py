"""Обработчики бота: команды и нажатия кнопок. Экраны — в views.py; кнопки меняют сообщение на месте."""
import time

from aiogram import F, Router
from aiogram.exceptions import TelegramBadRequest
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, Message

from . import views
from .config import Settings
from .db import Db
from .keys import MAX_DEVICES, TARIFFS, TRIAL
from .views import Buy, KeyAct, Nav, Screen


def make_router(cfg: Settings, db: Db) -> Router:
    r = Router()

    async def show(target: Message | CallbackQuery, screen: Screen) -> None:
        """Команда — новое сообщение; кнопка — то же сообщение меняется (без ленты из меню)."""
        if isinstance(target, CallbackQuery):
            try:
                await target.message.edit_text(screen.text, reply_markup=screen.markup)
            except TelegramBadRequest:
                # «message is not modified» (нажали то же самое) или сообщение слишком старое
                pass
            await target.answer()
        else:
            await target.answer(screen.text, reply_markup=screen.markup)

    def user(event: Message | CallbackQuery) -> int:
        u = event.from_user
        db.touch_user(u.id, u.username)
        return u.id

    def profile_screen(tg_id: int, key: str | None = None) -> Screen:
        now = int(time.time())
        keys = db.user_keys(tg_id)
        k = next((x for x in keys if x.key == key), None) if key else None
        if k is None and keys:
            # Сначала действующий, потом новый неактивированный, потом последний
            k = (next((x for x in keys if x.is_active(now)), None)
                 or next((x for x in keys if x.activated_at is None), None) or keys[0])
        devices = db.devices(k.key) if k else []
        return views.profile(cfg, tg_id, k, devices, now, len(keys))

    def trial_screen(tg_id: int) -> Screen:
        if db.trial_used(tg_id):
            return views.trial_used()
        db.mark_trial_used(tg_id)
        return views.key_issued(cfg, db.create_key(tg_id, TRIAL.id, 1))

    def own_key(tg_id: int, key: str):
        k = db.get_key(key)
        return k if k and k.tg_id == tg_id else None

    # ---------- команды ----------

    @r.message(CommandStart())
    async def start(m: Message):
        user(m)
        await show(m, views.main(cfg))

    @r.message(Command("buy"))
    async def buy_cmd(m: Message):
        user(m)
        await show(m, views.tariffs(cfg, "month", 1))

    @r.message(Command("trial"))
    async def trial_cmd(m: Message):
        await show(m, trial_screen(user(m)))

    @r.message(Command("profile"))
    async def profile_cmd(m: Message):
        await show(m, profile_screen(user(m)))

    @r.message(Command("help"))
    async def help_cmd(m: Message):
        user(m)
        await show(m, views.about(cfg))

    # ---------- кнопки ----------

    @r.callback_query(Nav.filter())
    async def nav(q: CallbackQuery, callback_data: Nav):
        tg_id = user(q)
        to = callback_data.to
        if to == "trial":
            screen = trial_screen(tg_id)
        elif to == "profile":
            screen = profile_screen(tg_id)
        elif to == "keys":
            screen = views.keys_list(db.user_keys(tg_id), int(time.time()))
        elif to == "app":
            screen = views.app_info(cfg)
        elif to == "about":
            screen = views.about(cfg)
        elif to == "support":
            screen = views.support(cfg)
        else:
            screen = views.main(cfg)
        await show(q, screen)

    @r.callback_query(Buy.filter())
    async def buy(q: CallbackQuery, callback_data: Buy):
        tg_id = user(q)
        period = callback_data.period if callback_data.period in TARIFFS else "month"
        devices = max(1, min(MAX_DEVICES, callback_data.devices))
        if not callback_data.get:
            await show(q, views.tariffs(cfg, period, devices))
            return
        if not cfg.free_mode:
            # Оплата появится здесь: счёт → после оплаты create_key
            await q.answer("Оплата скоро появится", show_alert=True)
            return
        await show(q, views.key_issued(cfg, db.create_key(tg_id, period, devices)))

    @r.callback_query(KeyAct.filter())
    async def key_action(q: CallbackQuery, callback_data: KeyAct):
        tg_id = user(q)
        k = own_key(tg_id, callback_data.key)
        if k is None:
            await q.answer("Ключ не найден", show_alert=True)
            return
        action = callback_data.action
        if action == "extend" and callback_data.period in TARIFFS:
            if not cfg.free_mode:
                await q.answer("Оплата скоро появится", show_alert=True)
                return
            db.extend(k.key, TARIFFS[callback_data.period].days)
            await q.answer(f"Продлено: +{TARIFFS[callback_data.period].title}")
            await show(q, profile_screen(tg_id, k.key))
        elif action == "extend":
            await show(q, views.extend_choose(cfg, k))
        elif action == "reset":
            await show(q, views.reset_confirm(k, db.devices(k.key)))
        elif action == "reset_ok":
            n = db.reset_devices(k.key)
            await q.answer(f"Отвязано устройств: {n}")
            await show(q, profile_screen(tg_id, k.key))
        else:
            await show(q, profile_screen(tg_id, k.key))

    @r.message(F.text)
    async def fallback(m: Message):
        user(m)
        await show(m, views.main(cfg))

    return r
