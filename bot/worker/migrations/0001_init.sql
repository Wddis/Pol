-- Та же схема, что у бота на Python (bot/oda_bot/db.py) + журнал неудачных вводов ключа (защита от подбора)
CREATE TABLE IF NOT EXISTS users (
    tg_id       INTEGER PRIMARY KEY,
    username    TEXT,
    created_at  INTEGER NOT NULL,
    trial_used  INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS keys (
    key          TEXT PRIMARY KEY,
    tg_id        INTEGER NOT NULL,
    tariff       TEXT NOT NULL,
    days         INTEGER NOT NULL,
    devices_max  INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    activated_at INTEGER,
    expires_at   INTEGER,
    revoked      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS keys_by_user ON keys(tg_id);
CREATE TABLE IF NOT EXISTS devices (
    key         TEXT NOT NULL,
    device_id   TEXT NOT NULL,
    name        TEXT NOT NULL DEFAULT '',
    first_seen  INTEGER NOT NULL,
    last_seen   INTEGER NOT NULL,
    PRIMARY KEY (key, device_id)
);
-- Адрес — хэш с солью, хранится 10 минут
CREATE TABLE IF NOT EXISTS fails (
    ip  TEXT NOT NULL,
    ts  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS fails_by_ip ON fails(ip, ts);
