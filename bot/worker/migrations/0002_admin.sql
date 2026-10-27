-- 0.10: сроки любой длины (минуты … бессрочно), ключи от администратора, блокировки, состояние VPN устройств,
-- диалоги (поддержка, ввод причины блокировки), журнал обращений в поддержку
ALTER TABLE keys ADD COLUMN seconds INTEGER;            -- срок ключа; NULL у старых — days*86400
UPDATE keys SET seconds = days * 86400 WHERE seconds IS NULL;
ALTER TABLE keys ADD COLUMN created_by INTEGER;          -- Telegram ID администратора, выдавшего ключ вручную
ALTER TABLE devices ADD COLUMN vpn_on INTEGER NOT NULL DEFAULT 0;     -- по последнему сигналу приложения
ALTER TABLE devices ADD COLUMN vpn_since INTEGER;
ALTER TABLE devices ADD COLUMN vpn_mode TEXT NOT NULL DEFAULT '';
ALTER TABLE devices ADD COLUMN app_version TEXT NOT NULL DEFAULT '';

-- Блокировка: одна запись на цель (аккаунт Telegram, ключ, устройство); записи одного решения — общий grp
CREATE TABLE IF NOT EXISTS bans (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    grp         TEXT NOT NULL,
    kind        TEXT NOT NULL,          -- user | key | device
    value       TEXT NOT NULL,
    reason      TEXT NOT NULL,
    until       INTEGER,                -- NULL — навсегда
    created_at  INTEGER NOT NULL,
    by          INTEGER NOT NULL,
    lifted      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS bans_by_target ON bans(kind, value);
CREATE INDEX IF NOT EXISTS bans_by_grp ON bans(grp);

CREATE TABLE IF NOT EXISTS chat_state (
    tg_id       INTEGER PRIMARY KEY,
    state       TEXT NOT NULL,
    data        TEXT NOT NULL DEFAULT '',
    updated_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS support (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    tg_id       INTEGER NOT NULL,
    text        TEXT NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS support_by_user ON support(tg_id, created_at);
