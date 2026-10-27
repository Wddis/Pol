// Меню администратора (только для ADMIN_IDS, команда /admin или кнопка «Админ» в меню):
// ключи любого срока (минуты … бессрочно), список ключей и устройств, кто сейчас в VPN, удаление ключей,
// блокировки (1 день, неделя, месяц, навсегда) с причиной, ответы на обращения в поддержку.
// Данные кнопок: adm:<действие>:… (≤ 64 байт). Цель блокировки t: «k<ключ>» или «u<Telegram ID>».
import type { Settings } from "./config";
import { type Ban, Db, type Device, type DeviceRow, type Key, type User, isActive, isOnline, secondsLeft, tariffTitle } from "./db";
import { FOREVER, MAX_DEVICES, UNITS, durationText, leftText, normalize, unitSeconds, unitText } from "./keys";
import { type Button, type Screen, banUntil, escapeHtml, fmtDate, statusLine, supportAnswer } from "./views";

const PAGE = 8;
const MAX_COUNT = 10;
export const BAN_REASONS = [
  "Передача ключа другим людям",
  "Перепродажа ключа",
  "Попытка обойти защиту ключа",
  "Нарушение правил ODA",
];
const BAN_TIMES: Record<string, { title: string; seconds: number | null }> = {
  "1d": { title: "1 день", seconds: 86400 },
  "7d": { title: "неделя", seconds: 7 * 86400 },
  "30d": { title: "месяц", seconds: 30 * 86400 },
  "0": { title: "навсегда", seconds: null },
};
const MODES: Record<string, string> = { bypass: "обход DPI", psiphon: "Psiphon", tor: "Tor" };

type Style = Button["style"];
const b = (text: string, cb: string, style?: Style): Button => (style ? { text, callback_data: cb, style } : { text, callback_data: cb });
const copy = (text: string, value: string, style?: Style): Button => (style ? { text, copy_text: { text: value }, style } : { text, copy_text: { text: value } });
const kb = (...rows: Button[][]) => ({ inline_keyboard: rows.filter((r) => r.length) });
const home = () => [b("‹ Админ", "adm:home")];

/** «5 мин назад», «3 ч назад», дальше — дата. */
export function ago(ts: number, now: number): string {
  const s = Math.max(0, now - ts);
  if (s < 60) return "только что";
  if (s < 3600) return `${Math.floor(s / 60)} мин назад`;
  if (s < 86400) return `${Math.floor(s / 3600)} ч назад`;
  return fmtDate(ts);
}

/** «1 ч 20 мин», «15 мин». */
export function span(s: number): string {
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? `${h} ч ${m} мин` : `${Math.max(1, m)} мин`;
}

const who = (tgId: number, username: string | null) =>
  tgId === 0 ? "не привязан (выдан вручную)" : `${username ? "@" + escapeHtml(username) : "без имени"} (ID <code>${tgId}</code>)`;

function deviceLine(d: Device, now: number): string {
  const name = escapeHtml(d.name || "устройство");
  const ver = d.app_version ? ` · ODA ${escapeHtml(d.app_version)}` : "";
  if (isOnline(d, now)) {
    const mode = MODES[d.vpn_mode] ?? escapeHtml(d.vpn_mode || "VPN");
    return `• ${name} — <b>● в VPN</b> ${span(now - (d.vpn_since ?? now))} (${mode})${ver}`;
  }
  return `• ${name} — был в сети ${ago(d.last_seen, now)}${ver}`;
}

function keyState(k: Key, now: number): string {
  if (k.activated_at === null) return "не активирован";
  if (k.seconds >= FOREVER) return isActive(k, now) ? "активен, бессрочно" : statusLine(k, now);
  if (isActive(k, now)) return `активен, осталось ${leftText(secondsLeft(k, now))} (до ${fmtDate(k.expires_at!)})`;
  return `закончился ${fmtDate(k.expires_at!)}`;
}

// ---------- экраны ----------

export function adminHome(s: Record<string, number>): Screen {
  return {
    text:
      "<b>Администратор</b>\n\n" +
      `Пользователей: ${s.users}\n` +
      `Ключей: ${s.keys} (действуют ${s.active}, не активированы ${s.fresh})\n` +
      `Устройств: ${s.devices}, сейчас в VPN: <b>${s.online}</b>\n` +
      `Блокировок: ${s.bans}\n\n` +
      "Найти: пришлите сюда ключ, @имя или Telegram ID.",
    markup: kb(
      [b("Создать ключ", "adm:new:d:7:1:1", "primary")],
      [b(`Сейчас в VPN (${s.online})`, "adm:on"), b("Устройства", "adm:dev:0")],
      [b("Ключи", "adm:keys:0"), b(`Блокировки (${s.bans})`, "adm:bans")],
      [b("‹ Меню", "nav:main")],
    ),
  };
}

export function creator(unit: string, n: number, devices: number, count: number): Screen {
  const u = UNITS[unit] ?? UNITS.d;
  unit = UNITS[unit] ? unit : "d";
  n = unit === "f" ? 1 : Math.max(u.min, Math.min(u.max, n));
  devices = Math.max(1, Math.min(MAX_DEVICES, devices));
  count = Math.max(1, Math.min(MAX_COUNT, count));
  const cb = (uu: string, nn: number, dd: number, cc: number) => `adm:new:${uu}:${nn}:${dd}:${cc}`;
  const units = Object.keys(UNITS).map((x) => b((x === unit ? "● " : "") + UNITS[x].title, cb(x, UNITS[x].quick[0] ?? 1, devices, count)));
  const rows: Button[][] = [units.slice(0, 3), units.slice(3)];
  if (unit !== "f") {
    rows.push([b("−", cb(unit, n - 1, devices, count)), b(unitText(unit, n), cb(unit, n, devices, count)), b("+", cb(unit, n + 1, devices, count))]);
    rows.push(u.quick.map((q) => b(String(q), cb(unit, q, devices, count))));
  }
  rows.push([b("Устройств −", cb(unit, n, devices - 1, count)), b(String(devices), cb(unit, n, devices, count)), b("+", cb(unit, n, devices + 1, count))]);
  rows.push([b("Ключей −", cb(unit, n, devices, count - 1)), b(String(count), cb(unit, n, devices, count)), b("+", cb(unit, n, devices, count + 1))]);
  rows.push([b(count > 1 ? `Создать ${count}` : "Создать", `adm:mk:${unit}:${n}:${devices}:${count}`, "primary")]);
  rows.push(home());
  return {
    text:
      "<b>Новый ключ</b>\n\n" +
      `Срок: <b>${unitText(unit, n)}</b>\n` +
      `Устройств на ключ: <b>${devices}</b>\n` +
      `Сколько ключей: <b>${count}</b>\n\n` +
      "Срок пойдёт с ввода ключа в приложении. Ключ не привязан к аккаунту: кто пришлёт его боту — тому он и запишется в профиль.",
    markup: kb(...rows),
  };
}

export function created(keys: Key[], again: string): Screen {
  const k0 = keys[0];
  const lines = keys.map((k) => `<code>${k.key}</code>`);
  const copyRow =
    keys.length === 1 ? [copy("Копировать ключ", k0.key, "primary")] : [copy("Копировать все", keys.map((k) => k.key).join("\n"), "primary")];
  return {
    text:
      `<b>${keys.length === 1 ? "Ключ создан" : `Создано ключей: ${keys.length}`}</b>\n\n${lines.join("\n")}\n\n` +
      `Срок: ${durationText(k0.seconds)} с активации · устройств: до ${k0.devices_max}\n` +
      "<i>Нажмите на ключ, чтобы скопировать.</i>",
    markup: kb(copyRow, [b("Ещё такие же", again), b("Ключи", "adm:keys:0")], home()),
  };
}

function pager(prefix: string, page: number, more: boolean): Button[] {
  const row: Button[] = [];
  if (page > 0) row.push(b("‹ Назад", `${prefix}:${page - 1}`));
  if (more) row.push(b("Дальше ›", `${prefix}:${page + 1}`));
  return row;
}

export function keysList(keys: Key[], page: number, now: number): Screen {
  const shown = keys.slice(0, PAGE);
  return {
    text: `<b>Ключи</b> · стр. ${page + 1}\n\nНовые — сверху. Нажмите на ключ — карточка: устройства, удаление, блокировка.`,
    markup: kb(
      ...shown.map((k) => [b(`${k.key} · ${tariffTitle(k)} · ${statusLine(k, now)}`, `adm:k:${k.key}`)]),
      pager("adm:keys", page, keys.length > PAGE),
      home(),
    ),
  };
}

export function keyCard(k: Key, owner: User | null, devs: Device[], ban: Ban | null, now: number): Screen {
  const lines = [
    `<b>Ключ</b> <code>${k.key}</code>`,
    `Владелец: ${who(k.tg_id, owner?.username ?? null)}`,
    `Срок: ${durationText(k.seconds)} · устройств до ${k.devices_max}${k.created_by ? " · выдан администратором" : ""}`,
    `Статус: ${keyState(k, now)}`,
    `Создан: ${fmtDate(k.created_at)}` + (k.activated_at ? ` · активирован: ${fmtDate(k.activated_at)}` : ""),
  ];
  if (ban) lines.push(`\n<b>Заблокирован</b> ${banUntil(ban)}: ${escapeHtml(ban.reason)}`);
  lines.push(`\nУстройства: ${devs.length} из ${k.devices_max}`);
  for (const d of devs) lines.push(deviceLine(d, now));
  const t = `k${k.key}`;
  return {
    text: lines.join("\n"),
    markup: kb(
      [ban ? b("Снять блокировку", `adm:lift:${ban.grp}`, "success") : b("Заблокировать", `adm:bn:${t}`, "danger")],
      [b("Отвязать устройства", `adm:rst:${k.key}`), b("Удалить ключ", `adm:del:${k.key}`)],
      k.tg_id ? [b("Владелец", `adm:u:${k.tg_id}`)] : [],
      [b("‹ Ключи", "adm:keys:0"), b("‹ Админ", "adm:home")],
    ),
  };
}

export function deleteConfirm(k: Key): Screen {
  return {
    text:
      `<b>Удалить ключ?</b>\n\n<code>${k.key}</code>\n\n` +
      "Ключ и его устройства удаляются насовсем. ODA на этих устройствах отключит VPN и попросит новый ключ " +
      "при следующей проверке (при включённом VPN — в течение 15 минут).",
    markup: kb([b("Удалить", `adm:delok:${k.key}`, "danger")], [b("Отмена", `adm:k:${k.key}`)]),
  };
}

function targetText(t: string): string {
  return t.startsWith("k") ? `ключ <code>${t.slice(1)}</code>, его устройства и владелец` : `пользователь ID <code>${t.slice(1)}</code>, все его ключи и устройства`;
}
const back = (t: string) => (t.startsWith("k") ? `adm:k:${t.slice(1)}` : `adm:u:${t.slice(1)}`);

export function banTime(t: string): Screen {
  return {
    text: `<b>Блокировка</b>\n\nБудут заблокированы: ${targetText(t)}.\nODA покажет экран блокировки с причиной, VPN отключится.\n\nНа сколько?`,
    markup: kb(
      [b("1 день", `adm:bd:${t}:1d`), b("Неделя", `adm:bd:${t}:7d`)],
      [b("Месяц", `adm:bd:${t}:30d`), b("Навсегда", `adm:bd:${t}:0`, "danger")],
      [b("Отмена", back(t))],
    ),
  };
}

export function banReason(t: string, dur: string): Screen {
  return {
    text: `<b>Причина</b> (${BAN_TIMES[dur]?.title ?? "?"})\n\nЕё увидит пользователь — в ODA и в боте.`,
    markup: kb(
      ...BAN_REASONS.map((r, i) => [b(r, `adm:br:${t}:${dur}:${i}`)]),
      [b("Свой текст…", `adm:br:${t}:${dur}:9`)],
      [b("Отмена", back(t))],
    ),
  };
}

export function askText(title: string, hint: string, cancel: string): Screen {
  return { text: `<b>${title}</b>\n\n${hint}`, markup: kb([b("Отмена", cancel)]) };
}

export function userCard(u: User, keys: Key[], ban: Ban | null, now: number): Screen {
  const lines = [
    "<b>Пользователь</b>",
    who(u.tg_id, u.username),
    `В боте с ${fmtDate(u.created_at)} · пробный день: ${u.trial_used ? "взят" : "не брал"}`,
  ];
  if (ban) lines.push(`\n<b>Заблокирован</b> ${banUntil(ban)}: ${escapeHtml(ban.reason)}`);
  lines.push(`\nКлючи: ${keys.length}`);
  return {
    text: lines.join("\n"),
    markup: kb(
      ...keys.slice(0, PAGE).map((k) => [b(`${k.key} · ${tariffTitle(k)} · ${statusLine(k, now)}`, `adm:k:${k.key}`)]),
      [ban ? b("Снять блокировку", `adm:lift:${ban.grp}`, "success") : b("Заблокировать", `adm:bn:u${u.tg_id}`, "danger"),
        b("Написать", `adm:rep:${u.tg_id}`)],
      home(),
    ),
  };
}

export function online(devs: DeviceRow[], now: number): Screen {
  const lines = devs.map((d) => {
    const mode = MODES[d.vpn_mode] ?? escapeHtml(d.vpn_mode || "VPN");
    const owner = d.tg_id ? (d.username ? "@" + escapeHtml(d.username) : `ID ${d.tg_id}`) : "ключ без аккаунта";
    return `• ${escapeHtml(d.name || "устройство")} · ${owner} · ${mode} · ${span(now - (d.vpn_since ?? now))}`;
  });
  return {
    text: `<b>Сейчас в VPN: ${devs.length}</b>\n\n` + (lines.join("\n") || "Никого.") +
      "\n\n<i>По сигналам ODA: при подключении, отключении и раз в 15 минут.</i>",
    markup: kb(
      ...devs.slice(0, PAGE).map((d) => [b(`${d.name || "устройство"} · ${d.key}`, `adm:k:${d.key}`)]),
      [b("Обновить", "adm:on"), b("‹ Админ", "adm:home")],
    ),
  };
}

export function devicesList(devs: DeviceRow[], page: number, now: number): Screen {
  return {
    text: `<b>Устройства</b> · стр. ${page + 1}\n\n● — сейчас в VPN. Нажмите — карточка ключа.`,
    markup: kb(
      ...devs.slice(0, PAGE).map((d) => [
        b(`${isOnline(d, now) ? "● " : ""}${d.name || "устройство"} · ${ago(d.last_seen, now)}`, `adm:k:${d.key}`),
      ]),
      pager("adm:dev", page, devs.length > PAGE),
      home(),
    ),
  };
}

export function bansList(bans: (Ban & { targets: string })[]): Screen {
  const describe = (targets: string) => {
    const parts = targets.split(" ");
    const users = parts.filter((p) => p.startsWith("user:")).map((p) => `ID ${p.slice(5)}`);
    const keys = parts.filter((p) => p.startsWith("key:")).map((p) => p.slice(4));
    const devices = parts.filter((p) => p.startsWith("device:")).length;
    return [...users, ...keys.map((k) => `<code>${k}</code>`), devices ? `устройств: ${devices}` : ""].filter(Boolean).join(", ");
  };
  const lines = bans.map((x, i) => `${i + 1}. ${escapeHtml(x.reason)} — ${banUntil(x)}\n    ${describe(x.targets)}`);
  return {
    text: `<b>Блокировки: ${bans.length}</b>\n\n` + (lines.join("\n") || "Нет действующих блокировок."),
    markup: kb(...bans.slice(0, PAGE).map((x, i) => [b(`Снять ${i + 1}: ${x.reason}`.slice(0, 60), `adm:lift:${x.grp}`)]), home()),
  };
}

// ---------- действия ----------

export interface Out {
  screen?: Screen;
  toast?: string;
  alert?: boolean;
}

export class Admin {
  constructor(
    private cfg: Settings,
    private db: Db,
    private sendTo: (chatId: number, s: Screen) => Promise<boolean>,
  ) {}

  isAdmin(tgId: number): boolean {
    return this.cfg.adminIds.includes(tgId);
  }

  async home(): Promise<Screen> {
    return adminHome(await this.db.stats());
  }

  private async keyScreen(key: string): Promise<Screen | null> {
    const k = await this.db.getKey(key);
    if (!k) return null;
    const devs = await this.db.devices(k.key);
    const owner = k.tg_id ? await this.db.getUser(k.tg_id) : null;
    const ban = await this.db.activeBan([{ kind: "key", value: k.key }, ...(k.tg_id ? [{ kind: "user", value: String(k.tg_id) }] : [])]);
    return keyCard(k, owner, devs, ban, Math.floor(Date.now() / 1000));
  }

  private async userScreen(tgId: number): Promise<Screen | null> {
    const u = await this.db.getUser(tgId);
    if (!u) return null;
    return userCard(u, await this.db.userKeys(tgId), await this.db.userBan(tgId), Math.floor(Date.now() / 1000));
  }

  /** Кнопка adm:… от администратора. */
  async callback(adminId: number, parts: string[]): Promise<Out> {
    const [action, a = "", c = "", d = "", e = ""] = parts;
    const now = Math.floor(Date.now() / 1000);
    switch (action) {
      case "new":
        return { screen: creator(a, parseInt(c, 10) || 1, parseInt(d, 10) || 1, parseInt(e, 10) || 1) };
      case "mk": {
        const unit = UNITS[a] ? a : "d";
        const n = parseInt(c, 10) || 1;
        const devices = Math.max(1, Math.min(MAX_DEVICES, parseInt(d, 10) || 1));
        const count = Math.max(1, Math.min(MAX_COUNT, parseInt(e, 10) || 1));
        const secs = unitSeconds(unit, n);
        const keys: Key[] = [];
        for (let i = 0; i < count; i++) keys.push(await this.db.createKey(0, "custom", devices, now, secs, adminId));
        return { screen: created(keys, `adm:new:${unit}:${n}:${devices}:${count}`) };
      }
      case "keys": {
        const page = Math.max(0, parseInt(a, 10) || 0);
        return { screen: keysList(await this.db.listKeys(page * PAGE, PAGE + 1), page, now) };
      }
      case "k": {
        const s = await this.keyScreen(a);
        return s ? { screen: s } : { toast: "Ключ не найден (удалён?)", alert: true };
      }
      case "del": {
        const k = await this.db.getKey(a);
        return k ? { screen: deleteConfirm(k) } : { toast: "Ключ не найден", alert: true };
      }
      case "delok": {
        const ok = await this.db.deleteKey(a);
        return { screen: await this.home(), toast: ok ? "Ключ удалён" : "Ключа уже нет" };
      }
      case "rst": {
        const n = await this.db.resetDevices(a);
        return { screen: (await this.keyScreen(a)) ?? (await this.home()), toast: `Отвязано устройств: ${n}` };
      }
      case "u": {
        const s = await this.userScreen(Number(a));
        return s ? { screen: s } : { toast: "Пользователь не найден", alert: true };
      }
      case "bn":
        return { screen: banTime(a) };
      case "bd":
        return BAN_TIMES[c] ? { screen: banReason(a, c) } : { toast: "?", alert: true };
      case "br": {
        if (!BAN_TIMES[c]) return { toast: "?", alert: true };
        const i = parseInt(d, 10);
        if (i === 9) {
          await this.db.setState(adminId, "ban", `${a}:${c}`);
          return { screen: askText("Своя причина", "Пришлите причину блокировки одним сообщением — её увидит пользователь.", back(a)) };
        }
        return this.applyBan(adminId, a, c, BAN_REASONS[i] ?? BAN_REASONS[3]);
      }
      case "lift": {
        const n = await this.db.liftBan(a);
        return { screen: bansList(await this.db.listBans()), toast: n ? "Блокировка снята" : "Уже снята" };
      }
      case "bans":
        return { screen: bansList(await this.db.listBans()) };
      case "on":
        return { screen: online(await this.db.onlineDevices(now), now) };
      case "dev": {
        const page = Math.max(0, parseInt(a, 10) || 0);
        return { screen: devicesList(await this.db.allDevices(page * PAGE, PAGE + 1), page, now) };
      }
      case "rep": {
        await this.db.setState(adminId, "reply", a);
        return { screen: askText("Ответ пользователю", `ID <code>${escapeHtml(a)}</code>. Пришлите ответ одним сообщением.`, "adm:home") };
      }
      default:
        return { screen: await this.home() };
    }
  }

  private async applyBan(adminId: number, t: string, dur: string, reason: string): Promise<Out> {
    const time = BAN_TIMES[dur];
    const until = time.seconds === null ? null : Math.floor(Date.now() / 1000) + time.seconds;
    const target = t.startsWith("k") ? { key: t.slice(1) } : { tgId: Number(t.slice(1)) };
    await this.db.ban(target, reason.slice(0, 200), until, adminId);
    const s = t.startsWith("k") ? await this.keyScreen(t.slice(1)) : await this.userScreen(Number(t.slice(1)));
    return { screen: s ?? (await this.home()), toast: `Заблокировано: ${time.title}` };
  }

  /** Текст от администратора: ожидаемый ввод (своя причина, ответ пользователю) или поиск. null — не админское. */
  async text(adminId: number, text: string): Promise<Screen | null> {
    const st = await this.db.getState(adminId);
    if (st && (st.state === "ban" || st.state === "reply")) {
      await this.db.clearState(adminId);
      if (st.state === "ban") {
        const i = st.data.lastIndexOf(":");
        const out = await this.applyBan(adminId, st.data.slice(0, i), st.data.slice(i + 1), text.trim());
        return out.screen ?? null;
      }
      if (st.state === "reply") {
        const ok = await this.sendTo(Number(st.data), supportAnswer(text.trim()));
        return askText(ok ? "Ответ отправлен" : "Не доставлено", ok ? "Пользователь получил ответ." : "Пользователь остановил бота или удалил чат.", "adm:home");
      }
    }
    const key = normalize(text);
    if (key) return (await this.keyScreen(key)) ?? askText("Ключ не найден", `<code>${key}</code> — такого ключа нет.`, "adm:home");
    if (/^@?[A-Za-z0-9_]{4,32}$/.test(text.trim())) {
      const u = await this.db.findUser(text);
      if (u) return this.userScreen(u.tg_id);
      return askText("Не найдено", "Такого пользователя бот не видел. ID — число, имя — @username.", "adm:home");
    }
    return null;
  }
}
