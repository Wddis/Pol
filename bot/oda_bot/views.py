"""Экраны бота по макету «ODA VPN — бот и ключ»: текст (HTML) + кнопки. Без сети — проверяются тестами."""
from dataclasses import dataclass
from datetime import datetime
from html import escape
from zoneinfo import ZoneInfo

from aiogram.filters.callback_data import CallbackData
from aiogram.types import CopyTextButton, InlineKeyboardButton, InlineKeyboardMarkup

from .config import Settings
from . import icons
from .db import DAY, Device, Key
from .keys import MAX_DEVICES, MIN_DEVICES, TARIFFS, TRIAL, plural, price

TZ = ZoneInfo("Europe/Moscow")


class Nav(CallbackData, prefix="nav"):
    to: str  # main, buy, trial, profile, keys, app, about, support


class Buy(CallbackData, prefix="buy"):
    period: str
    devices: int
    get: bool = False


class KeyAct(CallbackData, prefix="key"):
    action: str  # show, extend, reset, reset_ok
    key: str
    period: str = ""


@dataclass
class Screen:
    text: str
    markup: InlineKeyboardMarkup


def _btn(text: str, cb: CallbackData | None = None, url: str | None = None,
         copy: str | None = None, icon: str | None = None, style: str | None = None) -> InlineKeyboardButton:
    """Кнопка; icon — имя иконки из icons.NAMES, style — primary (синяя), success (зелёная), danger (красная)."""
    emoji_id = icons.ICONS.get(icon)
    if icon and not emoji_id:
        text = icons.FALLBACK.get(icon, "") + text
    extra = {"icon_custom_emoji_id": emoji_id, "style": style}
    if url:
        return InlineKeyboardButton(text=text, url=url, **extra)
    if copy:
        return InlineKeyboardButton(text=text, copy_text=CopyTextButton(text=copy), **extra)
    return InlineKeyboardButton(text=text, callback_data=cb.pack(), **extra)


def without_icons(markup: InlineKeyboardMarkup) -> InlineKeyboardMarkup:
    """Те же кнопки без иконок — если Telegram их не принял."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [b.model_copy(update={"icon_custom_emoji_id": None}) for b in row] for row in markup.inline_keyboard])


def _kb(*rows: list[InlineKeyboardButton]) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[r for r in rows if r])


def _menu_row() -> list[InlineKeyboardButton]:
    return [_btn("Меню", Nav(to="main"), icon="back")]


def _date(ts: int) -> str:
    return datetime.fromtimestamp(ts, TZ).strftime("%d.%m.%Y %H:%M")


def _days(n: int) -> str:
    return f"{n} {plural(n, 'день', 'дня', 'дней')}"


def _devices(n: int) -> str:
    return f"{n} {plural(n, 'устройство', 'устройства', 'устройств')}"


# ---------- главное меню ----------

def main(cfg: Settings) -> Screen:
    text = (
        "<b>ODA</b>\n\n"
        "Доступ к приложению ODA — одним ключом.\n\n"
        "Купите ключ, введите его в приложении при первом входе — и пользуйтесь. "
        "Срок считается с момента активации.\n\n"
        "• 1 неделя, 1 месяц или 3 месяца\n"
        "• от 1 до 5 устройств на ключ\n"
        "• первый день — бесплатно"
    )
    return Screen(text, _kb(
        [_btn("Купить ключ", Buy(period="month", devices=1), icon="key", style="primary")],
        [_btn("Пробный день", Nav(to="trial"), icon="trial", style="success"),
         _btn("Профиль", Nav(to="profile"), icon="profile")],
        [_btn("Приложение", Nav(to="app"), icon="app"), _btn("О VPN", Nav(to="about"), icon="about")],
        [_btn("Поддержка", Nav(to="support"), icon="support")],
    ))


# ---------- тарифы ----------

def tariffs(cfg: Settings, period: str, devices: int) -> Screen:
    devices = max(MIN_DEVICES, min(MAX_DEVICES, devices))
    base = {"week": cfg.price_week, "month": cfg.price_month, "q": cfg.price_3m}[period]
    total = price(base, devices, cfg.extra_device_pct)
    t = TARIFFS[period]
    lines = [
        "<b>Тариф</b>\n",
        f"Срок: <b>{t.title}</b> ({_days(t.days)})",
        f"Устройств на ключ: <b>{devices}</b> " + "▰" * devices + "▱" * (MAX_DEVICES - devices),
        f"Итого: <b>{f'{total} ₽' if base > 0 else '[ЦЕНА]'}</b>\n",
        "Срок начнётся, когда вы введёте ключ в приложении. "
        f"Каждое устройство сверх первого — +{cfg.extra_device_pct}% к цене.",
    ]
    if cfg.free_mode:
        lines.append("\n<i>Оплата подключается позже — сейчас ключ выдаётся без оплаты.</i>")
    periods = [_btn(("● " if p == period else "") + TARIFFS[p].title, Buy(period=p, devices=devices))
               for p in TARIFFS]
    return Screen("\n".join(lines), _kb(
        periods,
        [_btn("−", Buy(period=period, devices=max(MIN_DEVICES, devices - 1))),
         _btn(_devices(devices), Buy(period=period, devices=devices)),
         _btn("+", Buy(period=period, devices=min(MAX_DEVICES, devices + 1)))],
        [_btn("Получить ключ", Buy(period=period, devices=devices, get=True), icon="key", style="primary")],
        _menu_row(),
    ))


# ---------- ключ выдан ----------

def key_issued(cfg: Settings, k: Key) -> Screen:
    text = (
        "<b>Ключ готов</b>\n\n"
        f"<code>{k.key}</code>\n"
        "<i>нажмите на ключ, чтобы скопировать</i>\n\n"
        f"Тариф: {k.tariff_title}\n"
        f"Устройств: до {k.devices_max}\n"
        f"Срок: {_days(k.days)} с активации\n"
        "Статус: не активирован\n\n"
        "1. Установите приложение ODA\n"
        "2. При первом входе введите этот ключ\n"
        f"3. Отсчёт {_days(k.days)} начнётся с активации"
    )
    return Screen(text, _kb(
        [_btn("Копировать ключ", copy=k.key, icon="copy", style="primary")],
        [_btn("Скачать ODA", url=cfg.apk_url, icon="download") if cfg.apk_url
         else _btn("Скачать ODA", Nav(to="app"), icon="download"),
         _btn("Мой профиль", Nav(to="profile"), icon="profile")],
        _menu_row(),
    ))


def trial_used() -> Screen:
    return Screen(
        "<b>Пробный день уже использован</b>\n\nПробный ключ выдаётся один раз. Выберите тариф — от недели.",
        _kb([_btn("Тарифы", Buy(period="week", devices=1), icon="key", style="primary")], _menu_row()))


# ---------- профиль ----------

def status_line(k: Key, now: int) -> str:
    if k.revoked:
        return "отключён"
    if k.activated_at is None:
        return "не активирован"
    return "активен" if k.is_active(now) else "закончился"


def profile(cfg: Settings, tg_id: int, k: Key | None, devices: list[Device], now: int,
            keys_count: int) -> Screen:
    if k is None:
        return Screen(
            "<b>Профиль</b>\n\n"
            f"ID {tg_id}\n\nКлючей пока нет. Возьмите пробный день или выберите тариф.",
            _kb([_btn("Пробный день", Nav(to="trial"), icon="trial", style="success"),
                 _btn("Тарифы", Buy(period="month", devices=1), icon="key", style="primary")],
                _menu_row()))
    lines = ["<b>Профиль</b>", f"ID {tg_id}\n", f"<code>{k.key}</code> — {status_line(k, now)}"]
    if k.activated_at is None:
        lines.append(f"{k.tariff_title}: {_days(k.days)} начнутся с ввода ключа в приложении")
    elif k.is_active(now):
        left = k.seconds_left(now)
        days_left = -(-left // DAY)  # вверх: «остался 1 день», пока не прошли сутки
        used = max(0, min(10, round((k.days * DAY - left) / (k.days * DAY) * 10)))
        lines.append(f"<b>{_days(days_left)}</b> осталось · до {_date(k.expires_at)}")
        lines.append("▰" * used + "▱" * (10 - used))
        lines.append(f"Тариф {k.tariff_title} · активирован {_date(k.activated_at)}")
    else:
        lines.append(f"Срок закончился {_date(k.expires_at)}. Продлите — ключ останется тем же.")
    lines.append(f"\nУстройства: {len(devices)} из {k.devices_max}")
    for d in devices:
        lines.append(f"• {escape(d.name or 'устройство')} — был в сети {_date(d.last_seen)}")
    if len(devices) < k.devices_max:
        free = k.devices_max - len(devices)
        lines.append(f"• свободно: {free}")
    rows = [
        [_btn("Продлить", KeyAct(action="extend", key=k.key), icon="extend", style="success")],
        [_btn("Сменить устройство", KeyAct(action="reset", key=k.key), icon="devices")],
        [_btn("Копировать ключ", copy=k.key, icon="copy")],
    ]
    if keys_count > 1:
        rows.append([_btn(f"Мои ключи ({keys_count})", Nav(to="keys"), icon="keys")])
    rows.append([_btn("Поддержка", Nav(to="support"), icon="support")])
    rows.append(_menu_row())
    return Screen("\n".join(lines), _kb(*rows))


def keys_list(keys: list[Key], now: int) -> Screen:
    rows = [[_btn(f"{k.key} · {k.tariff_title} · {status_line(k, now)}", KeyAct(action="show", key=k.key))]
            for k in keys[:20]]
    return Screen("<b>Мои ключи</b>\n\nВыберите ключ:", _kb(*rows, _menu_row()))


def extend_choose(cfg: Settings, k: Key) -> Screen:
    text = (f"<b>Продление</b>\n\n<code>{k.key}</code>\n\n"
            "Срок добавится к оставшемуся (или начнётся сейчас, если закончился). Ключ останется тем же — "
            "в приложении вводить заново не нужно.")
    if cfg.free_mode:
        text += "\n\n<i>Оплата подключается позже — сейчас продление без оплаты.</i>"
    return Screen(text, _kb(
        [_btn(TARIFFS[p].title, KeyAct(action="extend", key=k.key, period=p)) for p in TARIFFS],
        [_btn("Профиль", KeyAct(action="show", key=k.key), icon="back")],
    ))


def reset_confirm(k: Key, devices: list[Device]) -> Screen:
    names = "\n".join(f"• {escape(d.name or 'устройство')}" for d in devices) or "• устройств нет"
    return Screen(
        "<b>Сменить устройство</b>\n\n"
        f"Все устройства ключа <code>{k.key}</code> будут отвязаны:\n{names}\n\n"
        "На них ODA попросит ввести ключ заново. Срок не меняется.",
        _kb([_btn("Отвязать все", KeyAct(action="reset_ok", key=k.key), icon="devices", style="danger")],
            [_btn("Отмена", KeyAct(action="show", key=k.key), icon="back")]))


# ---------- о приложении ----------

def app_info(cfg: Settings) -> Screen:
    text = (
        "<b>Приложение ODA</b>\n\n"
        "Одна кнопка — и телефон подключён. Способ ODA выбирает сама.\n\n"
        "• <b>Авто</b> — несколько способов сразу, работает самый быстрый\n"
        "• <b>Секундомер и тест скорости</b> — видно, сколько вы подключены и какая скорость\n"
        "• <b>Приложения через VPN</b> — выберите, какие идут мимо (например, банк)\n"
        "• <b>Плитка в шторке</b> — включение без захода в приложение\n\n"
        "Android 7 и новее."
    )
    if not cfg.apk_url:
        text += "\n\n<i>Ссылка на скачивание появится здесь.</i>"
    return Screen(text, _kb(
        [_btn("Скачать ODA (APK)", url=cfg.apk_url, icon="download", style="primary")] if cfg.apk_url else [],
        [_btn("Тарифы", Buy(period="month", devices=1), icon="key"),
         _btn("Пробный день", Nav(to="trial"), icon="trial", style="success")],
        _menu_row(),
    ))


def about(cfg: Settings) -> Screen:
    text = (
        "<b>О VPN</b>\n\n"
        "ODA шифрует и направляет трафик телефона выбранным способом. Ключ даёт доступ к приложению "
        "на срок тарифа.\n\n"
        "<b>Частые вопросы</b>\n\n"
        "<b>Когда начинается срок?</b>\nВ момент ввода ключа в приложении, не при покупке.\n\n"
        "<b>Новый телефон?</b>\nПрофиль → «Сменить устройство», затем введите ключ на новом.\n\n"
        "<b>Срок заканчивается?</b>\nODA напомнит за 2 дня и за 1 день, продлить можно здесь — ключ тот же.\n\n"
        "<b>Сколько устройств?</b>\nОт 1 до 5 — выбирается при покупке."
    )
    return Screen(text, _kb(
        [_btn("Приложение", Nav(to="app"), icon="app"), _btn("Тарифы", Buy(period="month", devices=1), icon="key")],
        _menu_row(),
    ))


def support(cfg: Settings) -> Screen:
    contact = escape(cfg.support) if cfg.support else "[КОНТАКТ ПОДДЕРЖКИ]"
    return Screen(
        "<b>Поддержка</b>\n\n"
        f"Напишите: {contact}\n\n"
        "Если что-то не подключается, приложите журнал: в ODA нажмите «ЖУРНАЛ» → «КОПИРОВАТЬ» "
        "и вставьте в сообщение.",
        _kb(_menu_row()))
