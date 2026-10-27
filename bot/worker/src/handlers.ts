// Обработчики бота: команды, нажатия кнопок, поддержка в чате, меню администратора (admin.ts).
// Кнопки меняют сообщение на месте.
import { Admin, type Out } from "./admin";
import type { Settings } from "./config";
import { Db, isActive } from "./db";
import { MAX_DEVICES, TARIFFS, TRIAL, normalize } from "./keys";
import type { Telegram, TgCallback, TgMessage, TgUpdate, TgUser } from "./telegram";
import * as views from "./views";
import type { Screen } from "./views";

export const COMMANDS = [
  { command: "start", description: "Главное меню" },
  { command: "buy", description: "Купить ключ" },
  { command: "trial", description: "Пробный день" },
  { command: "profile", description: "Мой профиль" },
  { command: "help", description: "О приложении и поддержка" },
];
export const ADMIN_COMMANDS = [...COMMANDS, { command: "admin", description: "Меню администратора" }];

// Профиль бота: описание — в пустом чате до «Старт», короткое — в профиле бота
export const DESCRIPTION =
  "ODA — приложение для Android: одна кнопка, и телефон подключён. Способ подключения ODA выбирает сама.\n\nЗдесь — ключ доступа к приложению:\n• пробный день — бесплатно\n• 1 неделя, 1 месяц или 3 месяца\n• от 1 до 5 устройств на ключ\n• срок идёт с ввода ключа в приложении\n\nИнструкция, ответы на вопросы и поддержка — внутри. Нажмите «Старт».";
export const SHORT_DESCRIPTION = "Ключ доступа к приложению ODA: пробный день бесплатно, тарифы от недели, до 5 устройств. Инструкция и поддержка.";

/** Обращений в поддержку от одного пользователя за сутки. */
export const SUPPORT_PER_DAY = 10;

const now = () => Math.floor(Date.now() / 1000);

export class Bot {
  private admin: Admin;

  constructor(private cfg: Settings, private db: Db, private tg: Telegram) {
    this.admin = new Admin(cfg, db, (chatId, s) => this.sendTo(chatId, s));
  }

  async handle(u: TgUpdate): Promise<void> {
    if (u.callback_query) await this.onCallback(u.callback_query);
    else if (u.message?.from && u.message.chat.type === "private") await this.onMessage(u.message);
  }

  // ---------- вывод ----------

  private async sendTo(chatId: number, s: Screen): Promise<boolean> {
    const r = await this.tg.call("sendMessage", { chat_id: chatId, text: s.text, parse_mode: "HTML", reply_markup: s.markup });
    return r.ok;
  }

  /** Кнопка — то же сообщение меняется (без ленты из меню); toast — надпись поверх чата, ответ на нажатие один. */
  private async edit(q: TgCallback, s: Screen | undefined, toast?: string, alert = false): Promise<void> {
    if (s && q.message) {
      // «message is not modified» (нажали то же) и слишком старое сообщение — не ошибка, просто ничего не меняется
      await this.tg.call("editMessageText", {
        chat_id: q.message.chat.id,
        message_id: q.message.message_id,
        text: s.text,
        parse_mode: "HTML",
        reply_markup: s.markup,
      });
    }
    await this.answer(q, toast, alert);
  }

  private async answer(q: TgCallback, text?: string, alert = false): Promise<void> {
    await this.tg.call("answerCallbackQuery", { callback_query_id: q.id, ...(text ? { text, show_alert: alert } : {}) });
  }

  private async user(u: TgUser): Promise<number> {
    await this.db.touchUser(u.id, u.username ?? null);
    return u.id;
  }

  // ---------- экраны с данными ----------

  private async profileScreen(tgId: number, key?: string): Promise<Screen> {
    const t = now();
    const keys = await this.db.userKeys(tgId);
    let k = key ? (keys.find((x) => x.key === key) ?? null) : null;
    if (!k && keys.length) {
      // Сначала действующий, потом новый неактивированный, потом последний
      k = keys.find((x) => isActive(x, t)) ?? keys.find((x) => x.activated_at === null) ?? keys[0];
    }
    const devs = k ? await this.db.devices(k.key) : [];
    return views.profile(this.cfg, tgId, k, devs, t, keys.length);
  }

  private async trialScreen(tgId: number): Promise<Screen> {
    if (!(await this.db.takeTrial(tgId))) return views.trialUsed();
    return views.keyIssued(this.cfg, await this.db.createKey(tgId, TRIAL.id, 1));
  }

  private async navScreen(to: string, tgId: number, isAdmin: boolean): Promise<Screen> {
    const cfg = this.cfg;
    switch (to) {
      case "trial": return this.trialScreen(tgId);
      case "profile": return this.profileScreen(tgId);
      case "keys": return views.keysList(await this.db.userKeys(tgId), now());
      case "app": return views.appInfo(cfg);
      case "about": return views.about(cfg);
      case "support": return views.support(cfg);
      case "howto": return views.howto(cfg);
      case "trouble": return views.trouble(cfg);
      case "privacy": return views.privacy(cfg);
      case "rules": return views.rules(cfg);
      case "pay": return views.payInfo(cfg);
      case "buy": return views.tariffs(cfg, "month", 1);
      default: return views.main(cfg, isAdmin);
    }
  }

  // ---------- сообщения ----------

  private async onMessage(m: TgMessage): Promise<void> {
    const tgId = await this.user(m.from!);
    const isAdmin = this.admin.isAdmin(tgId);
    const text = (m.text ?? "").trim();
    const [word, payload = ""] = text.split(/\s+/);
    const cmd = word.startsWith("/") ? word.split("@")[0].toLowerCase() : "";

    // Команда отменяет ожидаемый ввод (сообщение в поддержку, причина блокировки, ответ)
    if (cmd) await this.db.clearState(tgId);

    if (isAdmin && !cmd && text) {
      const s = await this.admin.text(tgId, text);
      if (s) return void (await this.sendTo(m.chat.id, s));
    }
    if (!cmd) {
      const st = await this.db.getState(tgId);
      if (st?.state === "support") return this.supportMessage(m, text);
    }

    const ban = isAdmin ? null : await this.db.userBan(tgId);
    if (ban) return void (await this.sendTo(m.chat.id, views.banned(ban)));

    let s: Screen;
    if (cmd === "/admin" && isAdmin) s = await this.admin.home();
    else if (cmd === "/start" && payload) s = await this.deepLink(payload, tgId, isAdmin);
    else if (cmd === "/buy") s = views.tariffs(this.cfg, "month", 1);
    else if (cmd === "/trial") s = await this.trialScreen(tgId);
    else if (cmd === "/profile") s = await this.profileScreen(tgId);
    else if (cmd === "/help") s = views.about(this.cfg);
    else if (!cmd && normalize(text)) s = await this.keyMessage(normalize(text)!, tgId);
    else s = views.main(this.cfg, isAdmin); // /start и любой текст
    await this.sendTo(m.chat.id, s);
  }

  /** Ссылки из приложения ODA: t.me/<бот>?start=support|buy|trial|profile|renew|pay|howto. */
  private async deepLink(payload: string, tgId: number, isAdmin: boolean): Promise<Screen> {
    if (payload === "renew") {
      const keys = await this.db.userKeys(tgId);
      const k = keys.find((x) => isActive(x, now())) ?? keys[0];
      return k ? views.extendChoose(this.cfg, k) : views.tariffs(this.cfg, "month", 1);
    }
    return this.navScreen(payload, tgId, isAdmin);
  }

  /** Пользователь прислал ключ: ключ от администратора записывается ему в профиль, свой — показывается. */
  private async keyMessage(key: string, tgId: number): Promise<Screen> {
    const k = await this.db.getKey(key);
    if (!k) return { text: "<b>Ключ не найден</b>\n\nПроверьте буквы и цифры.", markup: views.main(this.cfg).markup };
    if (k.tg_id === tgId || (k.tg_id === 0 && (await this.db.claimKey(key, tgId)))) return this.profileScreen(tgId, key);
    return views.keyForeign();
  }

  private async supportMessage(m: TgMessage, text: string): Promise<void> {
    const tgId = m.from!.id;
    if (!text) {
      await this.sendTo(m.chat.id, { text: "Пока принимаем только текст — опишите вопрос словами.", markup: views.supportWrite().markup });
      return;
    }
    if ((await this.db.supportCount(tgId)) >= SUPPORT_PER_DAY) {
      await this.db.clearState(tgId);
      await this.sendTo(m.chat.id, views.supportLimit());
      return;
    }
    await this.db.clearState(tgId);
    await this.db.addSupport(tgId, text.slice(0, 3500));
    const forAdmin = views.supportForAdmin(m.from!, text.slice(0, 3500), await this.db.userKeys(tgId), await this.db.userBan(tgId), now());
    let delivered = false;
    for (const id of this.cfg.adminIds) delivered = (await this.sendTo(id, forAdmin)) || delivered;
    if (!delivered) console.warn("support: не доставлено администраторам");
    await this.sendTo(m.chat.id, views.supportSent());
  }

  // ---------- кнопки ----------

  private async onCallback(q: TgCallback): Promise<void> {
    const tgId = await this.user(q.from);
    const isAdmin = this.admin.isAdmin(tgId);
    const [kind, ...parts] = (q.data ?? "").split(":");

    if (kind === "adm") {
      if (!isAdmin) return this.answer(q, "Нет доступа", true);
      const out: Out = await this.admin.callback(tgId, parts);
      return this.edit(q, out.screen, out.toast, out.alert);
    }
    if (kind === "sup") {
      if (parts[0] === "write") {
        await this.db.setState(tgId, "support");
        return this.edit(q, views.supportWrite());
      }
      await this.db.clearState(tgId);
      const ban = isAdmin ? null : await this.db.userBan(tgId);
      return this.edit(q, ban ? views.banned(ban) : views.support(this.cfg));
    }
    const ban = isAdmin ? null : await this.db.userBan(tgId);
    if (ban) return this.edit(q, views.banned(ban));
    if (kind === "buy") return this.onBuy(q, tgId, parts);
    if (kind === "key") return this.onKey(q, tgId, parts);
    await this.edit(q, await this.navScreen(kind === "nav" ? parts[0] : "main", tgId, isAdmin));
  }

  private async onBuy(q: TgCallback, tgId: number, [p, d, get]: string[]): Promise<void> {
    const period = p in TARIFFS ? p : "month";
    const devices = Math.max(1, Math.min(MAX_DEVICES, parseInt(d, 10) || 1));
    if (get !== "1") return this.edit(q, views.tariffs(this.cfg, period, devices));
    if (!this.cfg.freeMode) {
      // Оплата появится здесь: счёт (СБП или Stars) → после оплаты createKey
      return this.answer(q, "Оплата скоро появится", true);
    }
    await this.edit(q, views.keyIssued(this.cfg, await this.db.createKey(tgId, period, devices)));
  }

  private async onKey(q: TgCallback, tgId: number, [action, key, period]: string[]): Promise<void> {
    const k = key ? await this.db.getKey(key) : null;
    if (!k || k.tg_id !== tgId) return this.answer(q, "Ключ не найден", true);
    if (action === "extend" && period in TARIFFS) {
      if (!this.cfg.freeMode) return this.answer(q, "Оплата скоро появится", true);
      await this.db.extend(k.key, TARIFFS[period].days);
      return this.edit(q, await this.profileScreen(tgId, k.key), `Продлено: +${TARIFFS[period].title}`);
    }
    if (action === "extend") return this.edit(q, views.extendChoose(this.cfg, k));
    if (action === "reset") return this.edit(q, views.resetConfirm(k, await this.db.devices(k.key)));
    if (action === "reset_ok") {
      const n = await this.db.resetDevices(k.key);
      return this.edit(q, await this.profileScreen(tgId, k.key), `Отвязано устройств: ${n}`);
    }
    await this.edit(q, await this.profileScreen(tgId, k.key));
  }
}
