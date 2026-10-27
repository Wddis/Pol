// Обработчики бота — как bot/oda_bot/handlers.py: команды и нажатия кнопок; кнопки меняют сообщение на месте.
import type { Settings } from "./config";
import { Db, isActive } from "./db";
import { MAX_DEVICES, TARIFFS, TRIAL } from "./keys";
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

// Профиль бота (как в oda_bot/__main__.py): описание — в пустом чате до «Старт», короткое — в профиле бота
export const DESCRIPTION =
  "ODA — приложение для Android: одна кнопка, и телефон подключён. Способ подключения ODA выбирает сама.\n\nЗдесь — ключ доступа к приложению:\n• пробный день — бесплатно\n• 1 неделя, 1 месяц или 3 месяца\n• от 1 до 5 устройств на ключ\n• срок идёт с ввода ключа в приложении\n\nИнструкция, ответы на вопросы и поддержка — внутри. Нажмите «Старт».";
export const SHORT_DESCRIPTION = "Ключ доступа к приложению ODA: пробный день бесплатно, тарифы от недели, до 5 устройств. Инструкция и поддержка.";

const now = () => Math.floor(Date.now() / 1000);

export class Bot {
  constructor(private cfg: Settings, private db: Db, private tg: Telegram) {}

  async handle(u: TgUpdate): Promise<void> {
    if (u.callback_query) await this.onCallback(u.callback_query);
    else if (u.message?.text && u.message.from && u.message.chat.type === "private") await this.onMessage(u.message);
  }

  // ---------- вывод ----------

  /** Команда — новое сообщение. */
  private async send(m: TgMessage, s: Screen): Promise<void> {
    await this.tg.call("sendMessage", { chat_id: m.chat.id, text: s.text, parse_mode: "HTML", reply_markup: s.markup });
  }

  /** Кнопка — то же сообщение меняется (без ленты из меню); toast — надпись поверх чата, ответ на нажатие один. */
  private async edit(q: TgCallback, s: Screen, toast?: string): Promise<void> {
    if (q.message) {
      // «message is not modified» (нажали то же) и слишком старое сообщение — не ошибка, просто ничего не меняется
      await this.tg.call("editMessageText", {
        chat_id: q.message.chat.id,
        message_id: q.message.message_id,
        text: s.text,
        parse_mode: "HTML",
        reply_markup: s.markup,
      });
    }
    await this.answer(q, toast);
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

  // ---------- команды ----------

  private async onMessage(m: TgMessage): Promise<void> {
    const tgId = await this.user(m.from!);
    const cmd = (m.text ?? "").trim().split(/\s+/)[0].split("@")[0].toLowerCase();
    let s: Screen;
    if (cmd === "/buy") s = views.tariffs(this.cfg, "month", 1);
    else if (cmd === "/trial") s = await this.trialScreen(tgId);
    else if (cmd === "/profile") s = await this.profileScreen(tgId);
    else if (cmd === "/help") s = views.about(this.cfg);
    else s = views.main(this.cfg); // /start и любой текст
    await this.send(m, s);
  }

  // ---------- кнопки ----------

  private async onCallback(q: TgCallback): Promise<void> {
    const tgId = await this.user(q.from);
    const [kind, ...parts] = (q.data ?? "").split(":");
    if (kind === "buy") return this.onBuy(q, tgId, parts);
    if (kind === "key") return this.onKey(q, tgId, parts);
    const to = kind === "nav" ? parts[0] : "main";
    const cfg = this.cfg;
    const screens: Record<string, () => Screen | Promise<Screen>> = {
      trial: () => this.trialScreen(tgId),
      profile: () => this.profileScreen(tgId),
      keys: async () => views.keysList(await this.db.userKeys(tgId), now()),
      app: () => views.appInfo(cfg),
      about: () => views.about(cfg),
      support: () => views.support(cfg),
      howto: () => views.howto(cfg),
      trouble: () => views.trouble(cfg),
      privacy: () => views.privacy(cfg),
      rules: () => views.rules(cfg),
    };
    await this.edit(q, await (screens[to] ?? (() => views.main(cfg)))());
  }

  private async onBuy(q: TgCallback, tgId: number, [p, d, get]: string[]): Promise<void> {
    const period = p in TARIFFS ? p : "month";
    const devices = Math.max(1, Math.min(MAX_DEVICES, parseInt(d, 10) || 1));
    if (get !== "1") return this.edit(q, views.tariffs(this.cfg, period, devices));
    if (!this.cfg.freeMode) {
      // Оплата появится здесь: счёт → после оплаты createKey
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
