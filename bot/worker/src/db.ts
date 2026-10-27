// База ключей (D1 = SQLite) — как bot/oda_bot/db.py: пользователи, ключи, устройства. Время — unix-секунды UTC.
import { ALL_TARIFFS, generateKey } from "./keys";

export const DAY = 86400;

export interface Key {
  key: string;
  tg_id: number;
  tariff: string;
  days: number;
  devices_max: number;
  created_at: number;
  activated_at: number | null;
  expires_at: number | null;
  revoked: boolean;
}

export interface Device {
  device_id: string;
  name: string;
  first_seen: number;
  last_seen: number;
}

/** Итог активации или проверки ключа приложением. */
export const Result = {
  OK: "ok",
  NOT_FOUND: "not_found",
  REVOKED: "revoked",
  EXPIRED: "expired",
  DEVICE_LIMIT: "device_limit",
  DEVICE_REMOVED: "device_removed",
} as const;
export type ResultCode = (typeof Result)[keyof typeof Result];

export const tariffTitle = (k: Key) => ALL_TARIFFS[k.tariff]?.title ?? k.tariff;
export const isActive = (k: Key, now: number) => !k.revoked && k.expires_at !== null && k.expires_at > now;
/** До конца срока; до активации — весь срок тарифа. */
export const secondsLeft = (k: Key, now: number) =>
  k.expires_at === null ? k.days * DAY : Math.max(0, k.expires_at - now);

const now = () => Math.floor(Date.now() / 1000);

function toKey(r: Record<string, unknown>): Key {
  return {
    key: r.key as string,
    tg_id: Number(r.tg_id),
    tariff: r.tariff as string,
    days: Number(r.days),
    devices_max: Number(r.devices_max),
    created_at: Number(r.created_at),
    activated_at: r.activated_at === null ? null : Number(r.activated_at),
    expires_at: r.expires_at === null ? null : Number(r.expires_at),
    revoked: Boolean(r.revoked),
  };
}

export class Db {
  constructor(private d1: D1Database) {}

  // ---------- пользователи ----------

  async touchUser(tgId: number, username: string | null, t = now()): Promise<void> {
    await this.d1
      .prepare(
        "INSERT INTO users(tg_id, username, created_at) VALUES(?,?,?) " +
          "ON CONFLICT(tg_id) DO UPDATE SET username=excluded.username",
      )
      .bind(tgId, username, t)
      .run();
  }

  async trialUsed(tgId: number): Promise<boolean> {
    const r = await this.d1.prepare("SELECT trial_used FROM users WHERE tg_id=?").bind(tgId).first<number>("trial_used");
    return Boolean(r);
  }

  /** Отмечает пробный день; false — уже был (проверка и отметка одним запросом: два нажатия не дадут два ключа). */
  async takeTrial(tgId: number): Promise<boolean> {
    const r = await this.d1.prepare("UPDATE users SET trial_used=1 WHERE tg_id=? AND trial_used=0").bind(tgId).run();
    return r.meta.changes > 0;
  }

  // ---------- ключи ----------

  async createKey(tgId: number, tariff: string, devices: number, t = now()): Promise<Key> {
    const days = ALL_TARIFFS[tariff].days;
    for (;;) {
      const key = generateKey();
      const r = await this.d1
        .prepare(
          "INSERT OR IGNORE INTO keys(key, tg_id, tariff, days, devices_max, created_at) VALUES(?,?,?,?,?,?)",
        )
        .bind(key, tgId, tariff, days, devices, t)
        .run();
      if (r.meta.changes > 0) return (await this.getKey(key))!; // совпал с существующим — ещё раз
    }
  }

  async getKey(key: string): Promise<Key | null> {
    const r = await this.d1.prepare("SELECT * FROM keys WHERE key=?").bind(key).first<Record<string, unknown>>();
    return r ? toKey(r) : null;
  }

  async userKeys(tgId: number): Promise<Key[]> {
    const r = await this.d1
      .prepare("SELECT * FROM keys WHERE tg_id=? ORDER BY created_at DESC, rowid DESC")
      .bind(tgId)
      .all<Record<string, unknown>>();
    return r.results.map(toKey);
  }

  async devices(key: string): Promise<Device[]> {
    const r = await this.d1
      .prepare("SELECT device_id, name, first_seen, last_seen FROM devices WHERE key=? ORDER BY first_seen")
      .bind(key)
      .all<Device>();
    return r.results;
  }

  /** Продление тем же ключом: к сроку (или с этой минуты, если срок вышел) добавляется days. */
  async extend(key: string, days: number, t = now()): Promise<Key | null> {
    const k = await this.getKey(key);
    if (!k) return null;
    if (k.activated_at === null) {
      await this.d1.prepare("UPDATE keys SET days=days+? WHERE key=?").bind(days, key).run();
    } else {
      const start = Math.max(t, k.expires_at ?? t);
      await this.d1
        .prepare("UPDATE keys SET expires_at=?, days=days+? WHERE key=?")
        .bind(start + days * DAY, days, key)
        .run();
    }
    return this.getKey(key);
  }

  /** «Сменить устройство»: все устройства ключа отвязываются, ключ можно ввести на новом телефоне. */
  async resetDevices(key: string): Promise<number> {
    const r = await this.d1.prepare("DELETE FROM devices WHERE key=?").bind(key).run();
    return r.meta.changes;
  }

  // ---------- приложение ----------

  private async known(key: string, deviceId: string): Promise<boolean> {
    return (await this.d1.prepare("SELECT 1 AS x FROM devices WHERE key=? AND device_id=?").bind(key, deviceId).first()) !== null;
  }

  /** Ввод ключа в приложении: первая активация запускает отсчёт срока; устройство занимает место. */
  async activate(key: string, deviceId: string, name: string, t = now()): Promise<[ResultCode, Key | null]> {
    let k = await this.getKey(key);
    if (!k) return [Result.NOT_FOUND, null];
    if (k.revoked) return [Result.REVOKED, k];
    if (k.activated_at === null) {
      await this.d1
        .prepare("UPDATE keys SET activated_at=?, expires_at=? WHERE key=? AND activated_at IS NULL")
        .bind(t, t + k.days * DAY, key)
        .run();
      k = (await this.getKey(key))!;
    }
    if (!isActive(k, t)) return [Result.EXPIRED, k];
    if (await this.known(key, deviceId)) {
      await this.d1
        .prepare("UPDATE devices SET last_seen=?, name=? WHERE key=? AND device_id=?")
        .bind(t, name, key, deviceId)
        .run();
      return [Result.OK, k];
    }
    // Место занимается одним запросом: два телефона разом не превысят лимит
    const r = await this.d1
      .prepare(
        "INSERT INTO devices(key, device_id, name, first_seen, last_seen) " +
          "SELECT ?,?,?,?,? WHERE (SELECT COUNT(*) FROM devices WHERE key=?) < ?",
      )
      .bind(key, deviceId, name, t, t, key, k.devices_max)
      .run();
    return r.meta.changes > 0 ? [Result.OK, k] : [Result.DEVICE_LIMIT, k];
  }

  /** Проверка уже активированного ключа (приложение — раз в сутки и при запуске). */
  async check(key: string, deviceId: string, t = now()): Promise<[ResultCode, Key | null]> {
    const k = await this.getKey(key);
    if (!k) return [Result.NOT_FOUND, null];
    if (k.revoked) return [Result.REVOKED, k];
    if (!(await this.known(key, deviceId))) return [Result.DEVICE_REMOVED, k];
    await this.d1.prepare("UPDATE devices SET last_seen=? WHERE key=? AND device_id=?").bind(t, key, deviceId).run();
    if (!isActive(k, t)) return [Result.EXPIRED, k];
    return [Result.OK, k];
  }

  // ---------- защита от подбора ----------

  /** Неудачных вводов с адреса за окно; заодно стирает старые записи. */
  async fails(ip: string, window: number, t = now()): Promise<number> {
    await this.d1.prepare("DELETE FROM fails WHERE ts <= ?").bind(t - window).run();
    return (await this.d1.prepare("SELECT COUNT(*) AS n FROM fails WHERE ip=?").bind(ip).first<number>("n")) ?? 0;
  }

  async addFail(ip: string, t = now()): Promise<void> {
    await this.d1.prepare("INSERT INTO fails(ip, ts) VALUES(?,?)").bind(ip, t).run();
  }
}
