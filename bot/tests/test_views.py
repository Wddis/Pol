import time

from oda_bot import views
from oda_bot.config import Settings
from oda_bot.db import DAY, Db


def buttons(screen):
    return [b for row in screen.markup.inline_keyboard for b in row]


def check(screen):
    assert screen.text.strip()
    for b in buttons(screen):
        assert b.text
        if b.callback_data is not None:
            assert len(b.callback_data.encode()) <= 64  # предел Telegram


def test_all_screens_render():
    cfg = Settings()
    db = Db(":memory:")
    db.touch_user(7, "u")
    now = int(time.time())
    k = db.create_key(7, "month", 3)
    check(views.main(cfg))
    for p in ("week", "month", "q"):
        for d in range(1, 6):
            check(views.tariffs(cfg, p, d))
    check(views.key_issued(cfg, k))
    check(views.profile(cfg, 7, None, [], now, 0))
    check(views.profile(cfg, 7, k, [], now, 1))
    db.activate(k.key, "device-aaaa", "Redmi <Note>", now)
    k = db.get_key(k.key)
    s = views.profile(cfg, 7, k, db.devices(k.key), now, 2)
    check(s)
    assert "&lt;Note&gt;" in s.text  # имя устройства экранируется
    check(views.profile(cfg, 7, k, db.devices(k.key), now + 40 * DAY, 2))  # закончился
    check(views.keys_list(db.user_keys(7), now))
    check(views.extend_choose(cfg, k))
    check(views.reset_confirm(k, db.devices(k.key)))
    check(views.app_info(cfg))
    check(views.about(cfg))
    check(views.support(cfg))
    check(views.trial_used())
    for screen in (views.howto(cfg), views.trouble(cfg), views.privacy(cfg), views.rules(cfg)):
        check(screen)
        assert len(screen.text) < 4096  # предел сообщения Telegram


def test_price_placeholder_and_value():
    assert "[ЦЕНА]" in views.tariffs(Settings(), "month", 1).text
    s = views.tariffs(Settings(price_month=300, extra_device_pct=50), "month", 3)
    assert "600 ₽" in s.text


def test_icons_and_styles(tmp_path):
    from oda_bot import icons
    old = icons.ICONS
    try:
        icons.ICONS = icons.Icons(tmp_path / "icons.json")
        s = views.main(Settings())
        buy = buttons(s)[0]
        assert buy.style == "primary" and buy.icon_custom_emoji_id is None
        back = buttons(views.support(Settings()))[-1]
        assert back.text == "‹ Меню"  # без иконки — стрелка в тексте
        icons.ICONS.set("key", "5409357944619802453")
        icons.ICONS.set("back", "5409357944619802454")
        assert buttons(views.main(Settings()))[0].icon_custom_emoji_id == "5409357944619802453"
        back = buttons(views.support(Settings()))[-1]
        assert back.text == "Меню" and back.icon_custom_emoji_id == "5409357944619802454"
        stripped = views.without_icons(views.main(Settings()).markup)
        assert all(b.icon_custom_emoji_id is None for row in stripped.inline_keyboard for b in row)
        # Сохранено в файл — после перезапуска те же иконки
        assert icons.Icons(tmp_path / "icons.json").get("key") == "5409357944619802453"
    finally:
        icons.ICONS = old
