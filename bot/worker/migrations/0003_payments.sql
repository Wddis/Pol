-- 0.12: оплата — картой / СБП (Cashera, см. 0004) и Telegram Stars; настройки из меню администратора
-- (только комментарии поправлены после применения: схема та же)
CREATE TABLE IF NOT EXISTS settings (
    name   TEXT PRIMARY KEY,           -- free_mode, price_week|month|q (₽), stars_week|month|q, extra_pct, cs_key, cs_secret, payout_card
    value  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    code        TEXT NOT NULL,          -- номер заказа для покупателя и поддержки (6 цифр)
    tg_id       INTEGER NOT NULL,
    kind        TEXT NOT NULL,          -- new — новый ключ, extend — продление ключа key
    period      TEXT NOT NULL,          -- week | month | q
    devices     INTEGER NOT NULL,
    key         TEXT,                   -- продлеваемый или выданный ключ
    method      TEXT NOT NULL,          -- card (Cashera) | stars
    amount      INTEGER NOT NULL,       -- ₽ (card) или звёзды (stars)
    status      TEXT NOT NULL,          -- new → paid | cancelled
    charge_id   TEXT,                   -- uuid платежа Cashera или telegram_payment_charge_id (Stars)
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS orders_by_status ON orders(status, created_at);
CREATE INDEX IF NOT EXISTS orders_by_user ON orders(tg_id, created_at);
CREATE INDEX IF NOT EXISTS orders_by_code ON orders(code);
