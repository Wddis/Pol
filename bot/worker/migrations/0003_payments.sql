-- 0.12: оплата — СБП по коду (подтверждает администратор) и Telegram Stars; настройки из меню администратора
CREATE TABLE IF NOT EXISTS settings (
    name   TEXT PRIMARY KEY,           -- free_mode, price_week|month|q (₽), stars_week|month|q, sbp_phone|bank|name
    value  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    code        TEXT NOT NULL,          -- код платежа для комментария к переводу СБП (6 цифр)
    tg_id       INTEGER NOT NULL,
    kind        TEXT NOT NULL,          -- new — новый ключ, extend — продление ключа key
    period      TEXT NOT NULL,          -- week | month | q
    devices     INTEGER NOT NULL,
    key         TEXT,                   -- продлеваемый или выданный ключ
    method      TEXT NOT NULL,          -- sbp | stars
    amount      INTEGER NOT NULL,       -- ₽ (sbp) или звёзды (stars)
    status      TEXT NOT NULL,          -- new → claimed (нажал «Я оплатил») → paid | rejected; cancelled
    charge_id   TEXT,                   -- telegram_payment_charge_id (Stars)
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS orders_by_status ON orders(status, created_at);
CREATE INDEX IF NOT EXISTS orders_by_user ON orders(tg_id, created_at);
CREATE INDEX IF NOT EXISTS orders_by_code ON orders(code);
