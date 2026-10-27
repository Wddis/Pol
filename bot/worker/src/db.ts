// База (D1 = SQLite): пользователи, ключи, устройства, блокировки, диалоги, обращения в поддержку.
// Время — unix-секунды UTC.
import { ALL_TARIFFS, durationText, generateKey } from "./keys";

export const DAY = 86400;
/** Устройство «в VPN», если приложение сообщало об этом не раньше стольких секунд назад (сигнал — раз в 15 мин). */
export const ONLINE_WINDOW = 20 * 60;

export interface Key {
  key: string;
  tg_id: number; // 0 — ключ выдан администратором и ещё не привязан к аккаунту
  tariff: string; // week | month | q | trial | custom
  days: number;
  seconds: number; // срок; отсчёт — с активации
  devices_max: number;
  created_at: number;
  activated_at: number | null;
  expires_at: number | null;
  revoked: boolean;
  created_by: number | null;
}

export interface Device {
  device_id: string;
  name: string;
  first_seen: number;
  last_seen: number;
  vpn_on: boolean;
  vpn_since: number | null;
  vpn_mode: string;
  app_version: string;
}

export interface DeviceRow extends Device {
  key: string;
  tg_id: number;
  username: string | null;
}

export interface User {
  tg_id: number;
  username: string | null;
  created_at: number;
  trial_used: boolean;
}

export interface Order {
  id: number;
  code: string;
  tg_id: number;
  kind: "new" | "extend";
  period: string;
  devices: number;
  key: string | null;
  method: "crypto" | "stars";
  amount: number;
  status: "new" | "paid" | "cancelled";
  /** Счёт: invoice_id Crypto Pay (с создания) или telegram_payment_charge_id (Stars, после оплаты). */
  charge_id: string | null;
  created_at: number;
  updated_at: number;
}

/** Неоплаченный заказ живёт сутки (счёт CryptoBot — час). */
export const ORDER_TTL = 86400;

export interface Ban {
  grp: string;
  kind: string;
  value: string;
  reason: string;
  until: number | null; // null — навсегда
  created_at: number;
  by: number;
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

export const tariffTitle = (k: Key) => ALL_TARIFFS[k.tariff]?.title ?? durationText(k.seconds);
export const isActive = (k: Key, now: number) => !k.revoked && k.expires_at !== null && k.expires_at > now;
/** До конца срока; до активации — весь срок. */
export const secondsLeft = (k: Key, now: number) =>
  k.expires_at === null ? k.seconds : Math.max(0, k.expires_at - now);
export const isOnline = (d: Device, now: number) => d.vpn_on && d.last_seen > now - ONLINE_WINDOW;

const now = () => Math.floor(Date.now() / 1000);
const num = (v: unknown) => (v === null || v === undefined ? null : Number(v));

function toKey(r: Record<string, unknown>): Key {
  const days = Number(r.days);
  return {
    key: r.key as string,
    tg_id: Number(r.tg_id),
    tariff: r.tariff as string,
    days,
    seconds: num(r.seconds) ?? days * DAY,
    devices_max: Number(r.devices_max),
    created_at: Number(r.created_at),
    activated_at: num(r.activated_at),
    expires_at: num(r.expires_at),
    revoked: Boolean(r.revoked),
    created_by: num(r.created_by),
  };
}

function toDevice(r: Record<string, unknown>): Device {
  return {
    device_id: r.device_id as string,
    name: (r.name as string) ?? "",
    first_seen: Number(r.first_seen),
    last_seen: Number(r.last_seen),
    vpn_on: Boolean(r.vpn_on),
    vpn_since: num(r.vpn_since),
    vpn_mode: (r.vpn_mode as string) ?? "",
    app_version: (r.app_version as string) ?? "",
  };
}

const toBan = (r: Record<string, unknown>): Ban => ({
  grp: r.grp as string,
  kind: r.kind as string,
  value: r.value as string,
  reason: r.reason as string,
  until: num(r.until),
  created_at: Number(r.created_at),
  by: Number(r.by),
});

const ACTIVE_BAN = "lifted=0 AND (until IS NULL OR until > ?)";

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

  async getUser(tgId: number): Promise<User | null> {
    const r = await this.d1.prepare("SELECT * FROM users WHERE tg_id=?").bind(tgId).first<Record<string, unknown>>();
    return r ? { tg_id: Number(r.tg_id), username: r.username as string | null, created_at: Number(r.created_at), trial_used: Boolean(r.trial_used) } : null;
  }

  /** По @имени (без учёта регистра) или числовому ID. */
  async findUser(query: string): Promise<User | null> {
    const q = query.trim().replace(/^@/, "");
    if (/^\d{3,15}$/.test(q)) return this.getUser(Number(q));
    const r = await this.d1.prepare("SELECT tg_id FROM users WHERE lower(username)=lower(?)").bind(q).first<number>("tg_id");
    return r ? this.getUser(Number(r)) : null;
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

  /** Новый ключ: срок тарифа или seconds (ключ от администратора). */
  async createKey(tgId: number, tariff: string, devices: number, t = now(), seconds?: number, createdBy?: number): Promise<Key> {
    const secs = seconds ?? ALL_TARIFFS[tariff].days * DAY;
    const days = Math.max(1, Math.ceil(secs / DAY));
    for (;;) {
      const key = generateKey();
      const r = await this.d1
        .prepare(
          "INSERT OR IGNORE INTO keys(key, tg_id, tariff, days, seconds, devices_max, created_at, created_by) VALUES(?,?,?,?,?,?,?,?)",
        )
        .bind(key, tgId, tariff, days, secs, devices, t, createdBy ?? null)
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

  async listKeys(offset: number, limit: number): Promise<Key[]> {
    const r = await this.d1
      .prepare("SELECT * FROM keys ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?")
      .bind(limit, offset)
      .all<Record<string, unknown>>();
    return r.results.map(toKey);
  }

  /** Ключ от администратора (tg_id = 0) привязывается к аккаунту, приславшему его боту. */
  async claimKey(key: string, tgId: number): Promise<boolean> {
    const r = await this.d1.prepare("UPDATE keys SET tg_id=? WHERE key=? AND tg_id=0").bind(tgId, key).run();
    return r.meta.changes > 0;
  }

  /** Удаление ключа: приложение при следующей проверке попросит новый ключ. */
  async deleteKey(key: string): Promise<boolean> {
    const [, r] = await this.d1.batch([
      this.d1.prepare("DELETE FROM devices WHERE key=?").bind(key),
      this.d1.prepare("DELETE FROM keys WHERE key=?").bind(key),
    ]);
    return r.meta.changes > 0;
  }

  async devices(key: string): Promise<Device[]> {
    const r = await this.d1
      .prepare("SELECT * FROM devices WHERE key=? ORDER BY first_seen")
      .bind(key)
      .all<Record<string, unknown>>();
    return r.results.map(toDevice);
  }

  private async deviceRows(where: string, binds: unknown[], limit: number, offset = 0): Promise<DeviceRow[]> {
    const r = await this.d1
      .prepare(
        "SELECT d.*, k.tg_id AS tg_id, u.username AS username FROM devices d JOIN keys k ON k.key=d.key " +
          `LEFT JOIN users u ON u.tg_id=k.tg_id ${where} ORDER BY d.last_seen DESC LIMIT ? OFFSET ?`,
      )
      .bind(...binds, limit, offset)
      .all<Record<string, unknown>>();
    return r.results.map((x) => ({ ...toDevice(x), key: x.key as string, tg_id: Number(x.tg_id), username: x.username as string | null }));
  }

  /** Все устройства, последние в сети — первыми. */
  allDevices(offset: number, limit: number): Promise<DeviceRow[]> {
    return this.deviceRows("", [], limit, offset);
  }

  /** Сейчас в VPN (приложение сообщало за последние 20 минут). */
  onlineDevices(t = now(), limit = 50): Promise<DeviceRow[]> {
    return this.deviceRows("WHERE d.vpn_on=1 AND d.last_seen > ?", [t - ONLINE_WINDOW], limit);
  }

  /** Продление тем же ключом: к сроку (или с этой минуты, если срок вышел) добавляется days. */
  async extend(key: string, days: number, t = now()): Promise<Key | null> {
    const k = await this.getKey(key);
    if (!k) return null;
    const add = days * DAY;
    if (k.activated_at === null) {
      await this.d1.prepare("UPDATE keys SET days=days+?, seconds=seconds+? WHERE key=?").bind(days, add, key).run();
    } else {
      const start = Math.max(t, k.expires_at ?? t);
      await this.d1
        .prepare("UPDATE keys SET expires_at=?, days=days+?, seconds=seconds+? WHERE key=?")
        .bind(start + add, days, add, key)
        .run();
    }
    return this.getKey(key);
  }

  /** «Сменить устройство»: все устройства ключа отвязываются, ключ можно ввести на новом телефоне. */
  async resetDevices(key: string): Promise<number> {
    const r = await this.d1.prepare("DELETE FROM devices WHERE key=?").bind(key).run();
    return r.meta.changes;
  }

  async stats(t = now()) {
    const r = await this.d1
      .prepare(
        "SELECT (SELECT COUNT(*) FROM users) AS users, (SELECT COUNT(*) FROM keys) AS keys, " +
          "(SELECT COUNT(*) FROM keys WHERE revoked=0 AND expires_at > ?) AS active, " +
          "(SELECT COUNT(*) FROM keys WHERE activated_at IS NULL) AS fresh, " +
          "(SELECT COUNT(*) FROM devices) AS devices, " +
          "(SELECT COUNT(*) FROM devices WHERE vpn_on=1 AND last_seen > ?) AS online, " +
          `(SELECT COUNT(DISTINCT grp) FROM bans WHERE ${ACTIVE_BAN}) AS bans, ` +
          "(SELECT COUNT(*) FROM orders WHERE status='paid') AS paid",
      )
      .bind(t, t - ONLINE_WINDOW, t)
      .first<Record<string, number>>();
    return r!;
  }

  // ---------- приложение ----------

  private async known(key: string, deviceId: string): Promise<boolean> {
    return (await this.d1.prepare("SELECT 1 AS x FROM devices WHERE key=? AND device_id=?").bind(key, deviceId).first()) !== null;
  }

  /** Ввод ключа в приложении: первая активация запускает отсчёт срока; устройство занимает место. */
  async activate(key: string, deviceId: string, name: string, t = now(), version = ""): Promise<[ResultCode, Key | null]> {
    let k = await this.getKey(key);
    if (!k) return [Result.NOT_FOUND, null];
    if (k.revoked) return [Result.REVOKED, k];
    if (k.activated_at === null) {
      await this.d1
        .prepare("UPDATE keys SET activated_at=?, expires_at=? WHERE key=? AND activated_at IS NULL")
        .bind(t, t + k.seconds, key)
        .run();
      k = (await this.getKey(key))!;
    }
    if (!isActive(k, t)) return [Result.EXPIRED, k];
    if (await this.known(key, deviceId)) {
      await this.d1
        .prepare("UPDATE devices SET last_seen=?, name=?, app_version=? WHERE key=? AND device_id=?")
        .bind(t, name, version, key, deviceId)
        .run();
      return [Result.OK, k];
    }
    // Место занимается одним запросом: два телефона разом не превысят лимит
    const r = await this.d1
      .prepare(
        "INSERT INTO devices(key, device_id, name, first_seen, last_seen, app_version) " +
          "SELECT ?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM devices WHERE key=?) < ?",
      )
      .bind(key, deviceId, name, t, t, version, key, k.devices_max)
      .run();
    return r.meta.changes > 0 ? [Result.OK, k] : [Result.DEVICE_LIMIT, k];
  }

  /** Проверка (при запуске, раз в 6 часов) и сигнал состояния VPN (при подключении, отключении, раз в 15 минут). */
  async check(
    key: string,
    deviceId: string,
    t = now(),
    vpn?: { on: boolean; mode: string; version: string },
  ): Promise<[ResultCode, Key | null]> {
    const k = await this.getKey(key);
    if (!k) return [Result.NOT_FOUND, null];
    if (k.revoked) return [Result.REVOKED, k];
    if (!(await this.known(key, deviceId))) return [Result.DEVICE_REMOVED, k];
    if (vpn) {
      await this.d1
        .prepare(
          "UPDATE devices SET last_seen=?, vpn_on=?, vpn_mode=?, app_version=?, " +
            "vpn_since=CASE WHEN ?=0 THEN NULL WHEN vpn_on=1 AND vpn_since IS NOT NULL THEN vpn_since ELSE ? END " +
            "WHERE key=? AND device_id=?",
        )
        .bind(t, vpn.on ? 1 : 0, vpn.mode, vpn.version, vpn.on ? 1 : 0, t, key, deviceId)
        .run();
    } else {
      await this.d1.prepare("UPDATE devices SET last_seen=? WHERE key=? AND device_id=?").bind(t, key, deviceId).run();
    }
    if (!isActive(k, t)) return [Result.EXPIRED, k];
    return [Result.OK, k];
  }

  // ---------- блокировки ----------

  /** Действующая блокировка любой из целей (самая долгая: навсегда — первой). */
  async activeBan(targets: { kind: string; value: string }[], t = now()): Promise<Ban | null> {
    if (!targets.length) return null;
    const cond = targets.map(() => "(kind=? AND value=?)").join(" OR ");
    const r = await this.d1
      .prepare(`SELECT * FROM bans WHERE ${ACTIVE_BAN} AND (${cond}) ORDER BY until IS NULL DESC, until DESC LIMIT 1`)
      .bind(t, ...targets.flatMap((x) => [x.kind, x.value]))
      .first<Record<string, unknown>>();
    return r ? toBan(r) : null;
  }

  /** Блокировка аккаунта: доступно только для пользователей Telegram. */
  userBan(tgId: number, t = now()): Promise<Ban | null> {
    return this.activeBan([{ kind: "user", value: String(tgId) }], t);
  }

  /** Блокировка для приложения: устройство, ключ и владелец ключа. */
  async appBan(deviceId: string, k: Key | null, t = now()): Promise<Ban | null> {
    const targets = [{ kind: "device", value: deviceId }];
    if (k) {
      targets.push({ kind: "key", value: k.key });
      if (k.tg_id) targets.push({ kind: "user", value: String(k.tg_id) });
    }
    return this.activeBan(targets, t);
  }

  /**
   * Блокирует сразу всё, через что можно продолжить: ключ, его устройства и владельца — или аккаунт со всеми
   * его ключами и устройствами. Возвращает код решения (для «Снять»).
   */
  async ban(target: { key?: string; tgId?: number }, reason: string, until: number | null, by: number, t = now()): Promise<string> {
    const grp = generateKey().slice(4, 13).replace("-", ""); // 8 случайных символов
    const rows: [string, string][] = [];
    let keys: Key[] = [];
    if (target.key) {
      const k = await this.getKey(target.key);
      if (k) {
        keys = [k];
        if (k.tg_id) rows.push(["user", String(k.tg_id)]);
      }
      rows.push(["key", target.key]);
    }
    if (target.tgId) {
      rows.push(["user", String(target.tgId)]);
      keys = await this.userKeys(target.tgId);
    }
    for (const k of keys) {
      if (k.key !== target.key) rows.push(["key", k.key]);
      for (const d of await this.devices(k.key)) rows.push(["device", d.device_id]);
    }
    const seen = new Set<string>();
    const stmts = rows
      .filter(([kind, value]) => !seen.has(kind + value) && seen.add(kind + value))
      .map(([kind, value]) =>
        this.d1
          .prepare("INSERT INTO bans(grp, kind, value, reason, until, created_at, by) VALUES(?,?,?,?,?,?,?)")
          .bind(grp, kind, value, reason, until, t, by),
      );
    if (keys.length) {
      stmts.push(
        this.d1.prepare(`UPDATE devices SET vpn_on=0, vpn_since=NULL WHERE key IN (${keys.map(() => "?").join(",")})`).bind(...keys.map((k) => k.key)),
      );
    }
    await this.d1.batch(stmts);
    return grp;
  }

  async liftBan(grp: string): Promise<number> {
    const r = await this.d1.prepare("UPDATE bans SET lifted=1 WHERE grp=? AND lifted=0").bind(grp).run();
    return r.meta.changes;
  }

  /** Действующие решения о блокировке (по одному на grp) с их целями. */
  async listBans(t = now(), limit = 30): Promise<(Ban & { targets: string })[]> {
    const r = await this.d1
      .prepare(
        "SELECT grp, MIN(kind) AS kind, MIN(value) AS value, reason, until, created_at, by, " +
          "group_concat(kind || ':' || value, ' ') AS targets " +
          `FROM bans WHERE ${ACTIVE_BAN} GROUP BY grp ORDER BY created_at DESC LIMIT ?`,
      )
      .bind(t, limit)
      .all<Record<string, unknown>>();
    return r.results.map((x) => ({ ...toBan(x), targets: x.targets as string }));
  }

  // ---------- диалоги и поддержка ----------

  async getState(tgId: number, maxAge = 3600, t = now()): Promise<{ state: string; data: string } | null> {
    const r = await this.d1
      .prepare("SELECT state, data FROM chat_state WHERE tg_id=? AND updated_at > ?")
      .bind(tgId, t - maxAge)
      .first<{ state: string; data: string }>();
    return r ?? null;
  }

  async setState(tgId: number, state: string, data = "", t = now()): Promise<void> {
    await this.d1
      .prepare(
        "INSERT INTO chat_state(tg_id, state, data, updated_at) VALUES(?,?,?,?) " +
          "ON CONFLICT(tg_id) DO UPDATE SET state=excluded.state, data=excluded.data, updated_at=excluded.updated_at",
      )
      .bind(tgId, state, data, t)
      .run();
  }

  async clearState(tgId: number): Promise<void> {
    await this.d1.prepare("DELETE FROM chat_state WHERE tg_id=?").bind(tgId).run();
  }

  /** Обращений за последние сутки (не больше SUPPORT_PER_DAY). */
  async supportCount(tgId: number, t = now()): Promise<number> {
    return (await this.d1.prepare("SELECT COUNT(*) AS n FROM support WHERE tg_id=? AND created_at > ?").bind(tgId, t - DAY).first<number>("n")) ?? 0;
  }

  async addSupport(tgId: number, text: string, t = now()): Promise<void> {
    await this.d1.prepare("INSERT INTO support(tg_id, text, created_at) VALUES(?,?,?)").bind(tgId, text, t).run();
  }

  // ---------- настройки и оплата ----------

  async setSetting(name: string, value: string): Promise<void> {
    await this.d1
      .prepare("INSERT INTO settings(name, value) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value")
      .bind(name, value)
      .run();
  }

  /** Новый заказ; номер для покупателя и поддержки — 6 цифр, не повторяется среди незакрытых заказов. */
  async createOrder(o: Pick<Order, "tg_id" | "kind" | "period" | "devices" | "key" | "method" | "amount">, t = now()): Promise<Order> {
    for (let i = 0; i < 20; i++) {
      const n = crypto.getRandomValues(new Uint32Array(1))[0] % 900000 + 100000;
      const code = String(n);
      const busy = await this.d1
        .prepare("SELECT 1 AS x FROM orders WHERE code=? AND status='new' AND created_at > ?")
        .bind(code, t - ORDER_TTL)
        .first();
      if (busy) continue;
      const r = await this.d1
        .prepare(
          "INSERT INTO orders(code, tg_id, kind, period, devices, key, method, amount, status, created_at, updated_at) " +
            "VALUES(?,?,?,?,?,?,?,?,'new',?,?)",
        )
        .bind(code, o.tg_id, o.kind, o.period, o.devices, o.key, o.method, o.amount, t, t)
        .run();
      return (await this.getOrder(Number(r.meta.last_row_id)))!;
    }
    throw new Error("нет свободного кода заказа");
  }

  async getOrder(id: number): Promise<Order | null> {
    return (await this.d1.prepare("SELECT * FROM orders WHERE id=?").bind(id).first<Order>()) ?? null;
  }

  /** Смена статуса только из ожидаемого: повтор webhook или «Проверить оплату» не выдаст второй ключ. */
  async moveOrder(id: number, from: Order["status"][], to: Order["status"], extra: { key?: string; charge_id?: string } = {}, t = now()): Promise<boolean> {
    const r = await this.d1
      .prepare(
        `UPDATE orders SET status=?, updated_at=?, key=COALESCE(?, key), charge_id=COALESCE(?, charge_id) ` +
          `WHERE id=? AND status IN (${from.map(() => "?").join(",")})`,
      )
      .bind(to, t, extra.key ?? null, extra.charge_id ?? null, id, ...from)
      .run();
    return r.meta.changes > 0;
  }

  // ---------- защита от подбора ----------

  /** Неудачных вводов с отпечатка (адрес или устройство) за окно; заодно стирает старые записи. */
  async fails(tag: string, window: number, t = now()): Promise<number> {
    await this.d1.prepare("DELETE FROM fails WHERE ts <= ?").bind(t - window).run();
    return (await this.d1.prepare("SELECT COUNT(*) AS n FROM fails WHERE ip=?").bind(tag).first<number>("n")) ?? 0;
  }

  async addFail(tag: string, t = now()): Promise<void> {
    await this.d1.prepare("INSERT INTO fails(ip, ts) VALUES(?,?)").bind(tag, t).run();
  }
}
