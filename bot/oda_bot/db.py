"""База ключей (SQLite): пользователи, ключи, устройства. Время — unix-секунды UTC."""
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path

from .keys import ALL_TARIFFS, generate_key

DAY = 86400

SCHEMA = """
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
    days         INTEGER NOT NULL,          -- срок тарифа; отсчёт — с активации
    devices_max  INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    activated_at INTEGER,                   -- NULL — ещё не вводили в приложении
    expires_at   INTEGER,                   -- NULL до активации
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
"""


@dataclass
class Key:
    key: str
    tg_id: int
    tariff: str
    days: int
    devices_max: int
    created_at: int
    activated_at: int | None
    expires_at: int | None
    revoked: bool

    @property
    def tariff_title(self) -> str:
        t = ALL_TARIFFS.get(self.tariff)
        return t.title if t else self.tariff

    def is_active(self, now: int) -> bool:
        return not self.revoked and self.expires_at is not None and self.expires_at > now

    def seconds_left(self, now: int) -> int:
        """До конца срока; до активации — весь срок тарифа."""
        if self.expires_at is None:
            return self.days * DAY
        return max(0, self.expires_at - now)


@dataclass
class Device:
    device_id: str
    name: str
    first_seen: int
    last_seen: int


class Result:
    """Итог активации или проверки ключа приложением."""
    OK = "ok"
    NOT_FOUND = "not_found"
    REVOKED = "revoked"
    EXPIRED = "expired"
    DEVICE_LIMIT = "device_limit"
    DEVICE_REMOVED = "device_removed"


class Db:
    def __init__(self, path: Path | str):
        if str(path) != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.executescript(SCHEMA)

    # ---------- пользователи ----------

    def touch_user(self, tg_id: int, username: str | None, now: int | None = None) -> None:
        now = now or int(time.time())
        self.conn.execute(
            "INSERT INTO users(tg_id, username, created_at) VALUES(?,?,?) "
            "ON CONFLICT(tg_id) DO UPDATE SET username=excluded.username",
            (tg_id, username, now))

    def trial_used(self, tg_id: int) -> bool:
        row = self.conn.execute("SELECT trial_used FROM users WHERE tg_id=?", (tg_id,)).fetchone()
        return bool(row and row["trial_used"])

    def mark_trial_used(self, tg_id: int) -> None:
        self.conn.execute("UPDATE users SET trial_used=1 WHERE tg_id=?", (tg_id,))

    # ---------- ключи ----------

    def create_key(self, tg_id: int, tariff: str, devices: int, now: int | None = None) -> Key:
        now = now or int(time.time())
        days = ALL_TARIFFS[tariff].days
        while True:
            key = generate_key()
            try:
                self.conn.execute(
                    "INSERT INTO keys(key, tg_id, tariff, days, devices_max, created_at) VALUES(?,?,?,?,?,?)",
                    (key, tg_id, tariff, days, devices, now))
                break
            except sqlite3.IntegrityError:
                continue  # совпал с существующим — ещё раз
        return self.get_key(key)

    def get_key(self, key: str) -> Key | None:
        row = self.conn.execute("SELECT * FROM keys WHERE key=?", (key,)).fetchone()
        return _key(row) if row else None

    def user_keys(self, tg_id: int) -> list[Key]:
        rows = self.conn.execute(
            "SELECT * FROM keys WHERE tg_id=? ORDER BY created_at DESC", (tg_id,)).fetchall()
        return [_key(r) for r in rows]

    def devices(self, key: str) -> list[Device]:
        rows = self.conn.execute(
            "SELECT device_id, name, first_seen, last_seen FROM devices WHERE key=? ORDER BY first_seen",
            (key,)).fetchall()
        return [Device(r["device_id"], r["name"], r["first_seen"], r["last_seen"]) for r in rows]

    def extend(self, key: str, days: int, now: int | None = None) -> Key | None:
        """Продление тем же ключом: к сроку (или с этой минуты, если срок вышел) добавляется days."""
        now = now or int(time.time())
        k = self.get_key(key)
        if not k:
            return None
        if k.activated_at is None:
            self.conn.execute("UPDATE keys SET days=days+? WHERE key=?", (days, key))
        else:
            start = max(now, k.expires_at or now)
            self.conn.execute("UPDATE keys SET expires_at=?, days=days+? WHERE key=?",
                              (start + days * DAY, days, key))
        return self.get_key(key)

    def reset_devices(self, key: str) -> int:
        """«Сменить устройство»: все устройства ключа отвязываются, ключ можно ввести на новом телефоне."""
        return self.conn.execute("DELETE FROM devices WHERE key=?", (key,)).rowcount

    # ---------- приложение ----------

    def activate(self, key: str, device_id: str, name: str, now: int | None = None) -> tuple[str, Key | None]:
        """Ввод ключа в приложении: первая активация запускает отсчёт срока; устройство занимает место."""
        now = now or int(time.time())
        k = self.get_key(key)
        if not k:
            return Result.NOT_FOUND, None
        if k.revoked:
            return Result.REVOKED, k
        if k.activated_at is None:
            self.conn.execute("UPDATE keys SET activated_at=?, expires_at=? WHERE key=? AND activated_at IS NULL",
                              (now, now + k.days * DAY, key))
            k = self.get_key(key)
        if not k.is_active(now):
            return Result.EXPIRED, k
        known = self.conn.execute("SELECT 1 FROM devices WHERE key=? AND device_id=?", (key, device_id)).fetchone()
        if known:
            self.conn.execute("UPDATE devices SET last_seen=?, name=? WHERE key=? AND device_id=?",
                              (now, name, key, device_id))
            return Result.OK, k
        used = self.conn.execute("SELECT COUNT(*) AS n FROM devices WHERE key=?", (key,)).fetchone()["n"]
        if used >= k.devices_max:
            return Result.DEVICE_LIMIT, k
        self.conn.execute("INSERT INTO devices(key, device_id, name, first_seen, last_seen) VALUES(?,?,?,?,?)",
                          (key, device_id, name, now, now))
        return Result.OK, k

    def check(self, key: str, device_id: str, now: int | None = None) -> tuple[str, Key | None]:
        """Проверка уже активированного ключа (приложение — раз в сутки и при запуске)."""
        now = now or int(time.time())
        k = self.get_key(key)
        if not k:
            return Result.NOT_FOUND, None
        if k.revoked:
            return Result.REVOKED, k
        known = self.conn.execute("SELECT 1 FROM devices WHERE key=? AND device_id=?", (key, device_id)).fetchone()
        if not known:
            return Result.DEVICE_REMOVED, k
        self.conn.execute("UPDATE devices SET last_seen=? WHERE key=? AND device_id=?", (now, key, device_id))
        if not k.is_active(now):
            return Result.EXPIRED, k
        return Result.OK, k


def _key(row: sqlite3.Row) -> Key:
    return Key(row["key"], row["tg_id"], row["tariff"], row["days"], row["devices_max"], row["created_at"],
               row["activated_at"], row["expires_at"], bool(row["revoked"]))
