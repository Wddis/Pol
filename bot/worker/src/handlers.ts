// Обработчики бота: команды, нажатия кнопок, поддержка в чате, меню администратора (admin.ts).
// Кнопки меняют сообщение на месте.
import { Admin, type Out } from "./admin";
import { type Settings, cardReady } from "./config";
import { Cashera, CasheraError, type CsPayout, type CsTransaction, payUrl } from "./cashera";
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
    this.admin = new Admin(cfg, db, (chatId, s) => this.sendTo(chatId, s), (method, params) => this.tg.call(method, params));
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
      const s = await this.admin.text(tgId, text, async () => {
        await this.tg.call("deleteMessage", { chat_id: m.chat.id, message_id: m.message_id });
      });
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

  private cashera(): Cashera {
    return new Cashera(this.cfg.csKey, this.cfg.csSecret, this.cfg.csApi);
  }

  /** pay:<card|stars>:<срок>:<устройств>:<ключ для продления> — ссылка Cashera или счёт в звёздах. */
  private async onPay(q: TgCallback, tgId: number, [method, p, d, key = ""]: string[]): Promise<void> {
    if (this.cfg.freeMode) return this.answer(q, "Сейчас ключи выдаются без оплаты", true);
    if (!(p in TARIFFS) || (method !== "card" && method !== "stars")) return this.answer(q, "Устаревшая кнопка", true);
    let devices = Math.max(1, Math.min(MAX_DEVICES, parseInt(d, 10) || 1));
    let k: Key | null = null;
    if (key) {
      k = await this.db.getKey(key);
      if (!k || k.tg_id !== tgId) return this.answer(q, "Ключ не найден", true);
      devices = k.devices_max; // продление — по числу устройств ключа
    }
    const { rub, stars } = views.amounts(this.cfg, p, devices);
    const amount = method === "card" ? rub : stars;
    if (amount <= 0 || (method === "card" && !cardReady(this.cfg))) return this.answer(q, "Этот способ оплаты сейчас выключен", true);

    if (method === "card") {
      // Не плодить платежи: та же ссылка, пока действует
      const open = await this.db.openCardOrder(tgId, p, devices, k?.key ?? null);
      if (open) return this.edit(q, views.cardOrder(open, now(), true));
      return this.newCardOrder(q, tgId, p, devices, k);
    }

    const o = await this.db.createOrder({ tg_id: tgId, kind: k ? "extend" : "new", period: p, devices, key: k?.key ?? null, method, amount });
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
      return this.answer(q, "Не получилось выставить счёт. Попробуйте ещё раз", true);
    }
    await this.edit(q, views.starsSent(o));
  }

  /** Новый платёж Cashera: заказ, ссылка на платёжную страницу (карта или СБП). */
  private async newCardOrder(q: TgCallback, tgId: number, p: string, devices: number, k: Key | null): Promise<void> {
    const { rub } = views.amounts(this.cfg, p, devices);
    const o = await this.db.createOrder({ tg_id: tgId, kind: k ? "extend" : "new", period: p, devices, key: k?.key ?? null, method: "card", amount: rub });
    const what = k ? `Продление ключа ODA на ${TARIFFS[p].title}` : `Ключ ODA на ${TARIFFS[p].title}, устройств: ${devices}`;
    let tx: CsTransaction;
    try {
      tx = await this.cashera().createPayment(rub * 100, `oda-${o.id}`, `${what}. Заказ №${o.code}`, `${this.cfg.origin}/cashera`, `https://t.me/${this.cfg.botUsername}`);
    } catch (e) {
      console.error(`cashera createPayment: ${e instanceof CasheraError ? `${e.status} ${e.message}` : e}`);
      await this.db.moveOrder(o.id, ["new"], "cancelled");
      return this.answer(q, "Не получилось создать оплату. Попробуйте через минуту или оплатите звёздами", true);
    }
    const exp = tx.expires_at ? Math.floor(Date.parse(tx.expires_at) / 1000) : NaN;
    const o2 = { ...o, charge_id: tx.uuid, pay_url: payUrl(tx.payment_url ?? ""), expires_at: Number.isFinite(exp) ? exp : now() + 600 };
    await this.db.setPayment(o.id, o2.charge_id, o2.pay_url, o2.expires_at);
    await this.edit(q, views.cardOrder(o2, now()));
  }

  /** Кнопки заказа: ord:check — спросить Cashera (если webhook не дошёл), ord:renew — новая ссылка, ord:cancel. */
  private async onOrder(q: TgCallback, tgId: number, action: string, id: number): Promise<void> {
    const o = id ? await this.db.getOrder(id) : null;
    if (!o || o.tg_id !== tgId) return this.answer(q, "Заказ не найден", true);
    if (action === "cancel") {
      if (await this.db.moveOrder(o.id, ["new"], "cancelled")) return this.edit(q, views.orderCancelled());
      return this.answer(q, o.status === "paid" ? "Заказ уже оплачен — ключ в чате" : "Заказ уже закрыт", true);
    }
    if (action === "renew") {
      if (o.status === "paid") return this.answer(q, "Заказ уже оплачен — ключ в чате", true);
      await this.db.moveOrder(o.id, ["new"], "cancelled");
      const k = o.key ? await this.db.getKey(o.key) : null;
      return this.newCardOrder(q, tgId, o.period, o.devices, k);
    }
    if (action !== "check") return this.answer(q);
    if (o.status === "paid") return this.answer(q, "Оплачено — ключ уже в чате", true);
    if (o.status !== "new" || o.method !== "card" || !o.charge_id) return this.answer(q, "Заказ закрыт — создайте новый", true);
    let tx: CsTransaction;
    try {
      tx = await this.cashera().getPayment(o.charge_id);
    } catch (e) {
      console.error(`cashera getPayment: ${e}`);
      return this.answer(q, "Платёжный сервис не отвечает — попробуйте через минуту", true);
    }
    if (tx.status === "paid") {
      await this.cardPaid(tx);
      return this.answer(q, "Оплата найдена — ключ отправлен");
    }
    if (tx.status !== "pending") {
      await this.db.moveOrder(o.id, ["new"], "cancelled");
      return this.edit(q, views.orderCancelled(), "Оплата не прошла или ссылка истекла — создайте новую", true);
    }
    await this.edit(q, views.orderNotPaid(o, now()));
  }

  /** Платёж Cashera оплачен (webhook или «Проверить оплату»): сверка суммы — и ключ сразу. */
  async cardPaid(tx: CsTransaction): Promise<void> {
    const id = Number(/^oda-(\d+)$/.exec(tx.external_id ?? "")?.[1]);
    const o = id ? await this.db.getOrder(id) : null;
    if (!o || o.method !== "card" || tx.currency !== "RUB" || tx.amount !== o.amount * 100) {
      console.warn(`cashera: оплата не сходится с заказом ${tx.uuid} ${tx.external_id} ${tx.amount} ${tx.currency}`);
      for (const a of this.cfg.adminIds) {
        await this.sendTo(a, {
          text: `<b>Оплата Cashera не сходится с заказом</b>\n\nПлатёж <code>${views.escapeHtml(tx.uuid)}</code>, ${views.escapeHtml(tx.external_id ?? "")}, ${tx.amount / 100} ${views.escapeHtml(tx.currency ?? "")}\nПроверьте в кабинете Cashera и выдайте ключ вручную.`,
          markup: { inline_keyboard: [] },
        });
      }
      return;
    }
    // Оплатили после отмены или истечения ссылки — всё равно выдаём: деньги пришли
    if (!(await this.db.moveOrder(o.id, ["new", "cancelled"], "paid", { charge_id: tx.uuid }))) return; // повтор
    await this.fulfill(o);
  }

  /** Итог вывода на карту — администраторам. */
  async payoutUpdated(p: CsPayout): Promise<void> {
    if (p.status !== "completed" && p.status !== "failed") return;
    const net = (p.card_payout?.net_card_rub_amount ?? p.net_amount ?? p.amount) / 100;
    const text = p.status === "completed"
      ? `<b>Вывод выполнен</b>\n\nНа карту: ${net.toLocaleString("ru-RU")} ₽ (заявка ${views.escapeHtml(p.external_id)}).`
      : `<b>Вывод не выполнен</b>\n\n${views.escapeHtml(p.failure_reason ?? "причина не указана")}. Деньги вернулись на баланс Cashera.`;
    for (const a of this.cfg.adminIds) await this.sendTo(a, { text, markup: { inline_keyboard: [[{ text: "Баланс и вывод", callback_data: "adm:bal" }]] } });
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
