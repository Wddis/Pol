// Обработчики бота: команды, нажатия кнопок, поддержка в чате, меню администратора (admin.ts).
// Кнопки меняют сообщение на месте.
import { Admin, type Out } from "./admin";
import type { Settings } from "./config";
import { Db, type Key, ORDER_TTL, type Order, isActive } from "./db";
import { MAX_DEVICES, TARIFFS, TRIAL, normalize } from "./keys";
import type { Telegram, TgCallback, TgMessage, TgPreCheckout, TgUpdate, TgUser } from "./telegram";
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
    else if (u.pre_checkout_query) await this.onPreCheckout(u.pre_checkout_query);
    else if (u.message?.successful_payment && u.message.from) await this.onPaid(u.message);
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
    if (kind === "ord" && (parts[0] === "ok" || parts[0] === "no")) {
      if (!isAdmin) return this.answer(q, "Нет доступа", true);
      return this.onOrderAdmin(q, parts[0] === "ok", Number(parts[1]));
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
    if (kind === "pay") return this.onPay(q, tgId, parts);
    if (kind === "ord") return this.onOrder(q, tgId, parts[0], Number(parts[1]));
    await this.edit(q, await this.navScreen(kind === "nav" ? parts[0] : "main", tgId, isAdmin));
  }

  private async onBuy(q: TgCallback, tgId: number, [p, d, get]: string[]): Promise<void> {
    const period = p in TARIFFS ? p : "month";
    const devices = Math.max(1, Math.min(MAX_DEVICES, parseInt(d, 10) || 1));
    if (get !== "1") return this.edit(q, views.tariffs(this.cfg, period, devices));
    if (!this.cfg.freeMode) return this.edit(q, views.payChoose(this.cfg, period, devices));
    await this.edit(q, views.keyIssued(this.cfg, await this.db.createKey(tgId, period, devices)));
  }

  private async onKey(q: TgCallback, tgId: number, [action, key, period]: string[]): Promise<void> {
    const k = key ? await this.db.getKey(key) : null;
    if (!k || k.tg_id !== tgId) return this.answer(q, "Ключ не найден", true);
    if (action === "extend" && period in TARIFFS) {
      if (!this.cfg.freeMode) return this.edit(q, views.payChoose(this.cfg, period, k.devices_max, k.key));
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

  // ---------- оплата ----------

  /** pay:<sbp|stars>:<срок>:<устройств>:<ключ для продления> — заказ и реквизиты (СБП) или счёт в звёздах. */
  private async onPay(q: TgCallback, tgId: number, [method, p, d, key = ""]: string[]): Promise<void> {
    if (this.cfg.freeMode) return this.answer(q, "Сейчас ключи выдаются без оплаты", true);
    if (!(p in TARIFFS) || (method !== "sbp" && method !== "stars")) return this.answer(q, "Устаревшая кнопка", true);
    let devices = Math.max(1, Math.min(MAX_DEVICES, parseInt(d, 10) || 1));
    let k: Key | null = null;
    if (key) {
      k = await this.db.getKey(key);
      if (!k || k.tg_id !== tgId) return this.answer(q, "Ключ не найден", true);
      devices = k.devices_max; // продление — по числу устройств ключа
    }
    const { rub, stars } = views.amounts(this.cfg, p, devices);
    const amount = method === "sbp" ? rub : stars;
    if (amount <= 0 || (method === "sbp" && !this.cfg.sbpPhone)) return this.answer(q, "Этот способ оплаты сейчас выключен", true);
    const o = await this.db.createOrder({ tg_id: tgId, kind: k ? "extend" : "new", period: p, devices, key: k?.key ?? null, method, amount });
    if (method === "sbp") return this.edit(q, views.sbpOrder(this.cfg, o));
    const r = await this.tg.call("sendInvoice", {
      chat_id: q.message?.chat.id ?? tgId,
      title: k ? `Продление ODA: ${TARIFFS[p].title}` : `Ключ ODA: ${TARIFFS[p].title}`,
      description: k ? `Ключ ${k.key}: +${TARIFFS[p].title}. Ключ останется тем же.` : `Устройств: ${devices}. Срок пойдёт с ввода ключа в приложении.`,
      payload: `order:${o.id}`,
      currency: "XTR",
      prices: [{ label: TARIFFS[p].title, amount }],
    });
    if (!r.ok) {
      await this.db.moveOrder(o.id, ["new"], "cancelled");
      return this.answer(q, "Не получилось выставить счёт. Попробуйте ещё раз или оплатите по СБП", true);
    }
    await this.answer(q, "Счёт — ниже: «Заплатить»");
  }

  /** Кнопки заказа у покупателя: ord:paid (перевёл по СБП), ord:cancel. */
  private async onOrder(q: TgCallback, tgId: number, action: string, id: number): Promise<void> {
    const o = id ? await this.db.getOrder(id) : null;
    if (!o || o.tg_id !== tgId) return this.answer(q, "Заказ не найден", true);
    if (action === "cancel") {
      if (await this.db.moveOrder(o.id, ["new"], "cancelled")) return this.edit(q, views.orderCancelled());
      return this.answer(q, o.status === "claimed" ? "Платёж уже проверяется — отменить нельзя" : "Заказ уже закрыт", true);
    }
    if (action !== "paid") return this.answer(q);
    if (o.status === "claimed") return this.edit(q, views.orderClaimed(o), "Уже проверяем");
    if (o.status !== "new") return this.answer(q, "Заказ уже закрыт", true);
    if (now() - o.created_at > ORDER_TTL) {
      await this.db.moveOrder(o.id, ["new"], "cancelled");
      return this.edit(q, views.orderCancelled(), "Заказ устарел — создайте новый", true);
    }
    if (!(await this.db.moveOrder(o.id, ["new"], "claimed"))) return this.answer(q, "Заказ уже закрыт", true);
    const forAdmin = views.orderForAdmin(o, q.from);
    let delivered = false;
    for (const a of this.cfg.adminIds) delivered = (await this.sendTo(a, forAdmin)) || delivered;
    if (!delivered) console.warn(`order ${o.id}: администраторам не доставлено`);
    await this.edit(q, views.orderClaimed(o));
  }

  /** Администратор: ord:ok — перевод найден, ключ уходит покупателю; ord:no — не найден. */
  private async onOrderAdmin(q: TgCallback, ok: boolean, id: number): Promise<void> {
    const o = id ? await this.db.getOrder(id) : null;
    if (!o) return this.answer(q, "Заказ не найден", true);
    const done = (title: string, line: string) => ({
      text: `<b>${title}</b>

Код <code>${o.code}</code>, ${o.amount} ₽, ID <code>${o.tg_id}</code>
${line}`,
      markup: { inline_keyboard: [[{ text: "‹ Оплаты", callback_data: "adm:ord" }]] },
    });
    if (!ok) {
      if (!(await this.db.moveOrder(o.id, ["new", "claimed"], "rejected"))) return this.answer(q, `Заказ уже закрыт (${o.status})`, true);
      await this.sendTo(o.tg_id, views.orderRejected(o));
      return this.edit(q, done("Отклонено", "Покупателю отправлено: платёж не найден, написать в поддержку."));
    }
    if (!(await this.db.moveOrder(o.id, ["new", "claimed"], "paid"))) return this.answer(q, `Заказ уже закрыт (${o.status})`, true);
    const k = await this.fulfill(o);
    await this.edit(q, done("Оплачено", k ? `Ключ <code>${k.key}</code> отправлен покупателю.` : "Ключ для продления не найден — выдайте вручную."));
  }

  /** Перед оплатой звёздами Telegram спрашивает, действует ли заказ (ответ — за 10 с). */
  private async onPreCheckout(p: TgPreCheckout): Promise<void> {
    const id = Number(p.invoice_payload.replace(/^order:/, ""));
    const o = id ? await this.db.getOrder(id) : null;
    const ok = !!o && o.status === "new" && o.method === "stars" && o.tg_id === p.from.id && o.amount === p.total_amount && p.currency === "XTR";
    await this.tg.call("answerPreCheckoutQuery", {
      pre_checkout_query_id: p.id,
      ok,
      ...(ok ? {} : { error_message: "Заказ устарел. Откройте «Купить ключ» и оплатите заново." }),
    });
  }

  /** Звёзды списаны: ключ или продление сразу. */
  private async onPaid(m: TgMessage): Promise<void> {
    const sp = m.successful_payment!;
    const id = Number(sp.invoice_payload.replace(/^order:/, ""));
    const o = id ? await this.db.getOrder(id) : null;
    if (o?.status === "paid" && o.charge_id === sp.telegram_payment_charge_id) return; // повтор того же обновления
    if (!o || !(await this.db.moveOrder(o.id, ["new", "cancelled"], "paid", { charge_id: sp.telegram_payment_charge_id }))) {
      console.error(`stars: оплата без заказа ${sp.invoice_payload} (${sp.telegram_payment_charge_id})`);
      for (const a of this.cfg.adminIds) {
        await this.sendTo(a, { text: `<b>Оплата звёздами без заказа</b>

ID <code>${m.from!.id}</code>, ${sp.total_amount} ⭐, ${views.escapeHtml(sp.invoice_payload)}
Проверьте и выдайте ключ вручную.`, markup: { inline_keyboard: [] } });
      }
      return;
    }
    await this.fulfill(o);
  }

  /** Оплаченный заказ: новый ключ или продление, сообщение покупателю. */
  private async fulfill(o: Order): Promise<Key | null> {
    if (o.kind === "extend" && o.key) {
      const k = await this.db.extend(o.key, TARIFFS[o.period].days);
      if (k) await this.sendTo(o.tg_id, views.extended(k, TARIFFS[o.period].title));
      return k;
    }
    const k = await this.db.createKey(o.tg_id, o.period, o.devices);
    await this.db.moveOrder(o.id, ["paid"], "paid", { key: k.key });
    await this.sendTo(o.tg_id, views.keyIssued(this.cfg, k));
    return k;
  }
}
