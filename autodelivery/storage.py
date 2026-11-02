"""Склад и журнал выдач в SQLite.

Главное правило: одна сделка выдаётся один раз. Журнал `deliveries` хранит
deal_id с UNIQUE, а резерв строк склада и запись в журнал идут в одной
транзакции — повторное событие о той же сделке ничего не выдаст.
"""

from __future__ import annotations

import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS stock (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    pool      TEXT NOT NULL,
    payload   TEXT NOT NULL,
    status    TEXT NOT NULL DEFAULT 'free',     -- free | sold | removed
    deal_id   TEXT,
    added_at  TEXT NOT NULL,
    sold_at   TEXT,
    UNIQUE (pool, payload)
);
CREATE INDEX IF NOT EXISTS stock_pool_status ON stock (pool, status);

CREATE TABLE IF NOT EXISTS deliveries (
    deal_id    TEXT PRIMARY KEY,
    card_id    TEXT,
    chat_id    TEXT,
    buyer      TEXT,
    item_name  TEXT,
    price      REAL,
    status     TEXT NOT NULL,     -- delivered | waiting_stock | manual | unmatched | failed
    message    TEXT,
    error      TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT);
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class Delivery:
    deal_id: str
    card_id: str | None
    chat_id: str | None
    buyer: str | None
    item_name: str | None
    price: float | None
    status: str
    message: str | None
    error: str | None
    created_at: str
    updated_at: str


class Storage:
    def __init__(self, path: str | Path) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(_SCHEMA)
        self._lock = threading.Lock()

    # --- склад -------------------------------------------------------------

    def add_stock(self, pool: str, lines: list[str]) -> tuple[int, int]:
        """Добавить строки; возвращает (добавлено, дубликатов)."""
        added = dupes = 0
        with self._lock:
            self._db.execute("BEGIN")
            for line in lines:
                cur = self._db.execute(
                    "INSERT OR IGNORE INTO stock (pool, payload, added_at) VALUES (?, ?, ?)",
                    (pool, line, _now()),
                )
                if cur.rowcount:
                    added += 1
                else:
                    dupes += 1
            self._db.execute("COMMIT")
        return added, dupes

    def count_free(self, pool: str) -> int:
        row = self._db.execute(
            "SELECT COUNT(*) FROM stock WHERE pool = ? AND status = 'free'", (pool,)
        ).fetchone()
        return int(row[0])

    def counts(self) -> dict[str, tuple[int, int]]:
        """pool → (свободно, продано)."""
        rows = self._db.execute(
            "SELECT pool, SUM(status = 'free'), SUM(status = 'sold') FROM stock GROUP BY pool"
        ).fetchall()
        return {r[0]: (int(r[1] or 0), int(r[2] or 0)) for r in rows}

    def clear_free(self, pool: str) -> list[str]:
        """Снять со склада все свободные строки и вернуть их (для выгрузки)."""
        with self._lock:
            self._db.execute("BEGIN")
            rows = self._db.execute(
                "SELECT id, payload FROM stock WHERE pool = ? AND status = 'free'", (pool,)
            ).fetchall()
            self._db.execute(
                "UPDATE stock SET status = 'removed' WHERE pool = ? AND status = 'free'", (pool,)
            )
            self._db.execute("COMMIT")
        return [r["payload"] for r in rows]

    def units_for_deal(self, deal_id: str) -> list[str]:
        rows = self._db.execute(
            "SELECT payload FROM stock WHERE deal_id = ? ORDER BY id", (deal_id,)
        ).fetchall()
        return [r["payload"] for r in rows]

    # --- сделки ------------------------------------------------------------

    def get_delivery(self, deal_id: str) -> Delivery | None:
        row = self._db.execute("SELECT * FROM deliveries WHERE deal_id = ?", (deal_id,)).fetchone()
        return Delivery(**dict(row)) if row else None

    def claim(
        self,
        *,
        deal_id: str,
        card_id: str | None,
        chat_id: str | None,
        buyer: str | None,
        item_name: str | None,
        price: float | None,
        pool: str | None,
        quantity: int,
        retry_statuses: tuple[str, ...] = (),
    ) -> tuple[str, list[str]] | None:
        """Атомарно закрепить сделку и снять со склада `quantity` строк.

        Возвращает (статус, строки) или None, если сделку уже обработали.
        Статус `delivered` — строки сняты; `waiting_stock` — строк не хватило.
        Сделку в одном из `retry_statuses` можно забрать повторно (дозаправка склада).
        """
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                existing = self._db.execute(
                    "SELECT status FROM deliveries WHERE deal_id = ?", (deal_id,)
                ).fetchone()
                if existing and existing["status"] not in retry_statuses:
                    self._db.execute("ROLLBACK")
                    return None

                units: list[str] = []
                status = "delivered"
                if pool:
                    rows = self._db.execute(
                        "SELECT id, payload FROM stock WHERE pool = ? AND status = 'free' "
                        "ORDER BY id LIMIT ?",
                        (pool, quantity),
                    ).fetchall()
                    if len(rows) < quantity:
                        status = "waiting_stock"
                    else:
                        units = [r["payload"] for r in rows]
                        self._db.executemany(
                            "UPDATE stock SET status = 'sold', deal_id = ?, sold_at = ? WHERE id = ?",
                            [(deal_id, _now(), r["id"]) for r in rows],
                        )

                now = _now()
                self._db.execute(
                    "INSERT INTO deliveries (deal_id, card_id, chat_id, buyer, item_name, price, "
                    "status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(deal_id) DO UPDATE SET card_id = excluded.card_id, "
                    "status = excluded.status, updated_at = excluded.updated_at",
                    (deal_id, card_id, chat_id, buyer, item_name, price, status, now, now),
                )
                self._db.execute("COMMIT")
                return status, units
            except Exception:
                self._db.execute("ROLLBACK")
                raise

    def record(self, deal_id: str, **fields: object) -> None:
        """Создать или обновить запись о сделке."""
        fields["updated_at"] = _now()
        with self._lock:
            if self.get_delivery(deal_id) is None:
                fields.setdefault("status", "new")
                fields["created_at"] = fields["updated_at"]
                cols = ["deal_id", *fields]
                self._db.execute(
                    f"INSERT INTO deliveries ({', '.join(cols)}) VALUES ({', '.join('?' * len(cols))})",
                    (deal_id, *fields.values()),
                )
            else:
                sets = ", ".join(f"{k} = ?" for k in fields)
                self._db.execute(
                    f"UPDATE deliveries SET {sets} WHERE deal_id = ?", (*fields.values(), deal_id)
                )

    def deliveries_by_status(self, *statuses: str) -> list[Delivery]:
        marks = ", ".join("?" * len(statuses))
        rows = self._db.execute(
            f"SELECT * FROM deliveries WHERE status IN ({marks}) ORDER BY created_at", statuses
        ).fetchall()
        return [Delivery(**dict(r)) for r in rows]

    def recent(self, limit: int = 10) -> list[Delivery]:
        rows = self._db.execute(
            "SELECT * FROM deliveries ORDER BY created_at DESC LIMIT ?", (limit,)
        ).fetchall()
        return [Delivery(**dict(r)) for r in rows]

    def stats(self) -> dict[str, float]:
        row = self._db.execute(
            "SELECT COUNT(*), COALESCE(SUM(price), 0) FROM deliveries "
            "WHERE status = 'delivered' AND created_at >= date('now')"
        ).fetchone()
        total = self._db.execute(
            "SELECT COUNT(*), COALESCE(SUM(price), 0) FROM deliveries WHERE status = 'delivered'"
        ).fetchone()
        return {"today": row[0], "today_sum": row[1], "total": total[0], "total_sum": total[1]}

    # --- настройки ---------------------------------------------------------

    def get_flag(self, key: str, default: str = "") -> str:
        row = self._db.execute("SELECT value FROM kv WHERE key = ?", (key,)).fetchone()
        return row[0] if row else default

    def set_flag(self, key: str, value: str) -> None:
        with self._lock:
            self._db.execute(
                "INSERT INTO kv (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )
