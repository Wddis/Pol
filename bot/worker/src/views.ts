// Экраны бота — как bot/oda_bot/views.py (макет «ODA VPN — бот и ключ»): текст (HTML) + кнопки.
// Без сети: проверяются тестом. Данные кнопок — в том же виде, что у aiogram (nav:main, buy:month:1:0, key:show:ODA-…:).
import type { Settings } from "./config";
import { type Ban, type Device, type Key, isActive, secondsLeft, tariffTitle } from "./db";
import { FOREVER, MAX_DEVICES, MIN_DEVICES, TARIFFS, durationText, leftText, plural, price } from "./keys";

export interface Button {
  text: string;
  callback_data?: string;
  url?: string;
  copy_text?: { text: string };
  style?: "primary" | "success" | "danger";
}
export interface Screen {
  text: string;
  markup: { inline_keyboard: Button[][] };
}

// ---------- данные кнопок ----------

export const nav = (to: string) => `nav:${to}`;
export const buy = (period: string, devices: number, get = false) => `buy:${period}:${devices}:${get ? 1 : 0}`;
export const keyAct = (action: string, key: string, period = "") => `key:${action}:${key}:${period}`;

type Style = Button["style"];
interface BtnOpts {
  cb?: string;
  url?: string;
  copy?: string;
  style?: Style;
  back?: boolean; // «‹ » перед текстом — кнопка назад
}

function btn(text: string, o: BtnOpts): Button {
  const b: Button = { text: (o.back ? "‹ " : "") + text };
  if (o.url) b.url = o.url;
  else if (o.copy) b.copy_text = { text: o.copy };
  else b.callback_data = o.cb;
  if (o.style) b.style = o.style;
  return b;
}

const kb = (...rows: Button[][]) => ({ inline_keyboard: rows.filter((r) => r.length) });
const menuRow = () => [btn("Меню", { cb: nav("main"), back: true })];

export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#x27;");
}

/** «27.10.2026 18:05» по Москве (UTC+3 круглый год). */
export function fmtDate(ts: number): string {
  const d = new Date((ts + 3 * 3600) * 1000);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getUTCDate())}.${p(d.getUTCMonth() + 1)}.${d.getUTCFullYear()} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}

const days = (n: number) => `${n} ${plural(n, "день", "дня", "дней")}`;
const devicesText = (n: number) => `${n} ${plural(n, "устройство", "устройства", "устройств")}`;

// ---------- главное меню ----------

export function main(cfg: Settings, admin = false): Screen {
  const text =
    "<b>ODA</b>\n\n" +
    "Доступ к приложению ODA — одним ключом.\n\n" +
    "Купите ключ, введите его в приложении при первом входе — и пользуйтесь. " +
    "Срок считается с момента активации.\n\n" +
    "• 1 неделя, 1 месяц или 3 месяца\n" +
    "• от 1 до 5 устройств на ключ\n" +
    "• первый день — бесплатно";
  return {
    text,
    markup: kb(
      [btn("Купить ключ", { cb: buy("month", 1), style: "primary" }), btn("Как купить", { cb: nav("pay") })],
      [btn("Пробный день", { cb: nav("trial"), style: "success" }), btn("Профиль", { cb: nav("profile") })],
      [btn("Приложение", { cb: nav("app") }), btn("О VPN", { cb: nav("about") })],
      [btn("Как подключить", { cb: nav("howto") }), btn("Поддержка", { cb: nav("support") })],
      admin ? [btn("Админ", { cb: "adm:home", style: "danger" })] : [],
    ),
  };
}

// ---------- тарифы ----------

export function tariffs(cfg: Settings, period: string, devices: number): Screen {
  devices = Math.max(MIN_DEVICES, Math.min(MAX_DEVICES, devices));
  const base = ({ week: cfg.priceWeek, month: cfg.priceMonth, q: cfg.price3m } as Record<string, number>)[period];
  const total = price(base, devices, cfg.extraDevicePct);
  const t = TARIFFS[period];
  const lines = [
    "<b>Тариф</b>\n",
    `Срок: <b>${t.title}</b> (${days(t.days)})`,
    `Устройств на ключ: <b>${devices}</b> ` + "▰".repeat(devices) + "▱".repeat(MAX_DEVICES - devices),
    `Итого: <b>${base > 0 ? `${total} ₽` : "[ЦЕНА]"}</b>\n`,
    "Срок начнётся, когда вы введёте ключ в приложении. " +
      `Каждое устройство сверх первого — +${cfg.extraDevicePct}% к цене.`,
  ];
  lines.push("\nОплата: <b>СБП</b> или <b>Telegram Stars</b>.");
  if (cfg.freeMode) lines.push("<i>Оплата подключается — сейчас ключ выдаётся без оплаты.</i>");
  const periods = Object.keys(TARIFFS).map((p) =>
    btn((p === period ? "● " : "") + TARIFFS[p].title, { cb: buy(p, devices) }),
  );
  return {
    text: lines.join("\n"),
    markup: kb(
      periods,
      [
        btn("−", { cb: buy(period, Math.max(MIN_DEVICES, devices - 1)) }),
        btn(devicesText(devices), { cb: buy(period, devices) }),
        btn("+", { cb: buy(period, Math.min(MAX_DEVICES, devices + 1)) }),
      ],
      [btn("Получить ключ", { cb: buy(period, devices, true), style: "primary" })],
      menuRow(),
    ),
  };
}

// ---------- ключ выдан ----------

const download = (cfg: Settings, style?: Style) =>
  cfg.apkUrl ? btn("Скачать ODA", { url: cfg.apkUrl, style }) : btn("Скачать ODA", { cb: nav("app"), style });

export function keyIssued(cfg: Settings, k: Key): Screen {
  const text =
    "<b>Ключ готов</b>\n\n" +
    `<code>${k.key}</code>\n` +
    "<i>нажмите на ключ, чтобы скопировать</i>\n\n" +
    `Тариф: ${tariffTitle(k)}\n` +
    `Устройств: до ${k.devices_max}\n` +
    `Срок: ${durationText(k.seconds)} с активации\n` +
    "Статус: не активирован\n\n" +
    "1. Установите приложение ODA\n" +
    "2. При первом входе введите этот ключ\n" +
    (k.seconds >= FOREVER ? "3. Ключ бессрочный" : `3. Отсчёт срока (${durationText(k.seconds)}) начнётся с активации`);
  return {
    text,
    markup: kb(
      [btn("Копировать ключ", { copy: k.key, style: "primary" })],
      [download(cfg), btn("Мой профиль", { cb: nav("profile") })],
      menuRow(),
    ),
  };
}

export function trialUsed(): Screen {
  return {
    text: "<b>Пробный день уже использован</b>\n\nПробный ключ выдаётся один раз. Выберите тариф — от недели.",
    markup: kb([btn("Тарифы", { cb: buy("week", 1), style: "primary" })], menuRow()),
  };
}

// ---------- профиль ----------

export function statusLine(k: Key, now: number): string {
  if (k.revoked) return "отключён";
  if (k.activated_at === null) return "не активирован";
  return isActive(k, now) ? "активен" : "закончился";
}

export function profile(cfg: Settings, tgId: number, k: Key | null, devs: Device[], now: number, keysCount: number): Screen {
  if (!k) {
    return {
      text: "<b>Профиль</b>\n\n" + `ID ${tgId}\n\nКлючей пока нет. Возьмите пробный день или выберите тариф.`,
      markup: kb(
        [btn("Пробный день", { cb: nav("trial"), style: "success" }), btn("Тарифы", { cb: buy("month", 1), style: "primary" })],
        menuRow(),
      ),
    };
  }
  const lines = ["<b>Профиль</b>", `ID ${tgId}\n`, `<code>${k.key}</code> — ${statusLine(k, now)}`];
  if (k.activated_at === null) {
    lines.push(`${tariffTitle(k)}: срок пойдёт с ввода ключа в приложении`);
  } else if (isActive(k, now)) {
    const left = secondsLeft(k, now);
    const total = Math.max(1, k.expires_at! - k.activated_at);
    const used = Math.max(0, Math.min(10, Math.round(((total - left) / total) * 10)));
    lines.push(k.seconds >= FOREVER ? "<b>Бессрочно</b>" : `<b>${leftText(left)}</b> осталось · до ${fmtDate(k.expires_at!)}`);
    lines.push("▰".repeat(used) + "▱".repeat(10 - used));
    lines.push(`Тариф ${tariffTitle(k)} · активирован ${fmtDate(k.activated_at)}`);
  } else {
    lines.push(`Срок закончился ${fmtDate(k.expires_at!)}. Продлите — ключ останется тем же.`);
  }
  lines.push(`\nУстройства: ${devs.length} из ${k.devices_max}`);
  for (const d of devs) lines.push(`• ${escapeHtml(d.name || "устройство")} — был в сети ${fmtDate(d.last_seen)}`);
  if (devs.length < k.devices_max) lines.push(`• свободно: ${k.devices_max - devs.length}`);
  const rows: Button[][] = [
    [btn("Продлить", { cb: keyAct("extend", k.key), style: "success" })],
    [btn("Сменить устройство", { cb: keyAct("reset", k.key) })],
    [btn("Копировать ключ", { copy: k.key })],
  ];
  if (keysCount > 1) rows.push([btn(`Мои ключи (${keysCount})`, { cb: nav("keys") })]);
  rows.push([btn("Поддержка", { cb: nav("support") })]);
  rows.push(menuRow());
  return { text: lines.join("\n"), markup: kb(...rows) };
}

export function keysList(keys: Key[], now: number): Screen {
  const rows = keys
    .slice(0, 20)
    .map((k) => [btn(`${k.key} · ${tariffTitle(k)} · ${statusLine(k, now)}`, { cb: keyAct("show", k.key) })]);
  return { text: "<b>Мои ключи</b>\n\nВыберите ключ:", markup: kb(...rows, menuRow()) };
}

export function extendChoose(cfg: Settings, k: Key): Screen {
  let text =
    `<b>Продление</b>\n\n<code>${k.key}</code>\n\n` +
    "Срок добавится к оставшемуся (или начнётся сейчас, если закончился). Ключ останется тем же — " +
    "в приложении вводить заново не нужно.";
  if (cfg.freeMode) text += "\n\n<i>Оплата подключается — сейчас продление без оплаты.</i>";
  return {
    text,
    markup: kb(
      Object.keys(TARIFFS).map((p) => btn(TARIFFS[p].title, { cb: keyAct("extend", k.key, p) })),
      [btn("Профиль", { cb: keyAct("show", k.key), back: true })],
    ),
  };
}

export function resetConfirm(k: Key, devs: Device[]): Screen {
  const names = devs.map((d) => `• ${escapeHtml(d.name || "устройство")}`).join("\n") || "• устройств нет";
  return {
    text:
      "<b>Сменить устройство</b>\n\n" +
      `Все устройства ключа <code>${k.key}</code> будут отвязаны:\n${names}\n\n` +
      "На них ODA попросит ввести ключ заново. Срок не меняется.",
    markup: kb(
      [btn("Отвязать все", { cb: keyAct("reset_ok", k.key), style: "danger" })],
      [btn("Отмена", { cb: keyAct("show", k.key), back: true })],
    ),
  };
}

// ---------- о приложении ----------

export function appInfo(cfg: Settings): Screen {
  let text =
    "<b>Приложение ODA</b>\n\n" +
    "Одна кнопка — и телефон подключён. Способ ODA выбирает сама.\n\n" +
    "• <b>Авто</b> — несколько способов сразу, работает самый быстрый\n" +
    "• <b>Секундомер и тест скорости</b> — видно, сколько вы подключены и какая скорость\n" +
    "• <b>Приложения через VPN</b> — выберите, какие идут мимо (например, банк)\n" +
    "• <b>Плитка в шторке</b> — включение без захода в приложение\n\n" +
    "Android 7 и новее.";
  if (!cfg.apkUrl) text += "\n\n<i>Ссылка на скачивание появится здесь.</i>";
  return {
    text,
    markup: kb(
      cfg.apkUrl ? [btn("Скачать ODA (APK)", { url: cfg.apkUrl, style: "primary" })] : [],
      [btn("Тарифы", { cb: buy("month", 1) }), btn("Пробный день", { cb: nav("trial"), style: "success" })],
      menuRow(),
    ),
  };
}

export function about(cfg: Settings): Screen {
  const text =
    "<b>О VPN</b>\n\n" +
    "ODA шифрует и направляет трафик телефона выбранным способом. Ключ даёт доступ к приложению " +
    "на срок тарифа.\n\n" +
    "<b>Частые вопросы</b>\n\n" +
    "<b>Когда начинается срок?</b>\nВ момент ввода ключа в приложении, не при покупке.\n\n" +
    "<b>Новый телефон?</b>\nПрофиль → «Сменить устройство», затем введите ключ на новом.\n\n" +
    "<b>Срок заканчивается?</b>\nODA напомнит за 2 дня и за 1 день, продлить можно здесь — ключ тот же.\n\n" +
    "<b>Сколько устройств?</b>\nОт 1 до 5 — выбирается при покупке.\n\n" +
    "<b>Что вы храните?</b>\nТолько ключи и устройства, без истории сайтов — подробно в «Конфиденциальность».";
  return {
    text,
    markup: kb(
      [btn("Как подключить", { cb: nav("howto") }), btn("Если не работает", { cb: nav("trouble") })],
      [btn("Конфиденциальность", { cb: nav("privacy") }), btn("Правила", { cb: nav("rules") })],
      [btn("Приложение", { cb: nav("app") }), btn("Тарифы", { cb: buy("month", 1) })],
      menuRow(),
    ),
  };
}

export function support(cfg: Settings): Screen {
  const extra = cfg.support ? `\nЕщё можно написать: ${escapeHtml(cfg.support)}\n` : "";
  return {
    text:
      "<b>Поддержка</b>\n\n" +
      "Нажмите «Написать в поддержку» и отправьте вопрос одним сообщением — ответ придёт в этот чат.\n" +
      extra +
      "\nЕсли что-то не подключается, приложите журнал: в ODA нажмите «ЖУРНАЛ» → «КОПИРОВАТЬ» " +
      "и вставьте в сообщение. Укажите модель телефона.",
    markup: kb(
      [btn("Написать в поддержку", { cb: "sup:write", style: "primary" })],
      [btn("Если не работает", { cb: nav("trouble") }), btn("Как подключить", { cb: nav("howto") })],
      menuRow(),
    ),
  };
}

export function supportWrite(): Screen {
  return {
    text: "<b>Сообщение в поддержку</b>\n\nНапишите вопрос одним сообщением и отправьте. Ответ придёт сюда.",
    markup: kb([btn("Отмена", { cb: "sup:cancel", back: true })]),
  };
}

export function supportSent(): Screen {
  return {
    text: "<b>Сообщение отправлено</b>\n\nПоддержка ответит в этот чат.",
    markup: kb([btn("Написать ещё", { cb: "sup:write" })], menuRow()),
  };
}

export function supportLimit(): Screen {
  return {
    text: "<b>Слишком много сообщений</b>\n\nЗа сутки можно отправить до 10 сообщений. Дождитесь ответа поддержки.",
    markup: kb(menuRow()),
  };
}

/** Сообщение пользователя для администраторов. */
export function supportForAdmin(from: { id: number; username?: string }, text: string, keys: Key[], ban: Ban | null, now: number): Screen {
  const who = from.username ? `@${escapeHtml(from.username)}` : "без имени";
  const keyLines = keys.slice(0, 5).map((k) => `• <code>${k.key}</code> — ${tariffTitle(k)}, ${statusLine(k, now)}`);
  return {
    text:
      "<b>Обращение в поддержку</b>\n" +
      `От: ${who} (ID <code>${from.id}</code>)\n` +
      (keyLines.length ? `Ключи:\n${keyLines.join("\n")}\n` : "Ключей нет\n") +
      (ban ? `Заблокирован: ${escapeHtml(ban.reason)} (${banUntil(ban)})\n` : "") +
      `\n${escapeHtml(text)}`,
    markup: kb([btn("Ответить", { cb: `adm:rep:${from.id}`, style: "primary" }), btn("Пользователь", { cb: `adm:u:${from.id}` })]),
  };
}

export function supportAnswer(text: string): Screen {
  return {
    text: `<b>Ответ поддержки</b>\n\n${escapeHtml(text)}`,
    markup: kb([btn("Ответить", { cb: "sup:write" })], menuRow()),
  };
}

export const banUntil = (b: Ban) => (b.until === null ? "навсегда" : `до ${fmtDate(b.until)}`);

/** Заблокированный пользователь в боте: причина, срок, поддержка. */
export function banned(b: Ban): Screen {
  return {
    text:
      "<b>Доступ заблокирован</b>\n\n" +
      `Причина: ${escapeHtml(b.reason)}\n` +
      `Срок: ${banUntil(b)}\n\n` +
      "Пока действует блокировка, ключи и приложение ODA недоступны. Если вы считаете, что это ошибка, — " +
      "напишите в поддержку.",
    markup: kb([btn("Написать в поддержку", { cb: "sup:write", style: "primary" })]),
  };
}

// ---------- как купить ----------

export function payInfo(cfg: Settings): Screen {
  let text =
    "<b>Как купить</b>\n\n" +
    "1. «Купить ключ» → выберите срок (1 неделя, 1 месяц, 3 месяца) и число устройств.\n" +
    "2. Оплатите удобным способом:\n" +
    "• <b>СБП</b> — из приложения вашего банка по QR-коду или ссылке\n" +
    "• <b>Telegram Stars</b> — прямо в Telegram\n" +
    "3. Ключ сразу придёт в этот чат — нажмите на него, чтобы скопировать.\n" +
    "4. Откройте ODA и введите ключ. Срок пойдёт с этой минуты.\n\n" +
    "Продлить — «Профиль» → «Продлить»: ключ останется тем же, вводить заново не нужно.\n" +
    "Не уверены — начните с пробного дня.";
  if (cfg.freeMode) text += "\n\n<i>Оплата подключается — сейчас ключи выдаются без оплаты.</i>";
  return {
    text,
    markup: kb(
      [btn("Купить ключ", { cb: buy("month", 1), style: "primary" }), btn("Пробный день", { cb: nav("trial"), style: "success" })],
      menuRow(),
    ),
  };
}

// ---------- ключ, присланный боту ----------

export function keyForeign(): Screen {
  return {
    text: "<b>Ключ привязан к другому аккаунту</b>\n\nЭтот ключ получен в другом аккаунте Telegram. Пользоваться им в ODA можно, а продлевать — из того аккаунта.",
    markup: kb(menuRow()),
  };
}

// ---------- справка для VPN ----------

export function howto(cfg: Settings): Screen {
  const text =
    "<b>Как подключить</b>\n\n" +
    "1. <b>Скачайте ODA</b> (APK) и откройте файл. Android спросит разрешение на установку из этого " +
    "источника — разрешите.\n" +
    "2. <b>Введите ключ</b> при первом запуске. С этой минуты идёт срок тарифа.\n" +
    "3. <b>Нажмите большую кнопку.</b> Android спросит «Подключение VPN» — нажмите «ОК». Это разрешение " +
    "запрашивается один раз.\n" +
    "4. <b>Способ</b> — «Авто»: ODA сама выберет лучший. Подключение обычно занимает 10–30 секунд, " +
    "в новой сети — до минуты.\n\n" +
    "<b>Удобно</b>\n" +
    "• <b>Плитка в шторке</b>: шторка → карандаш «Изменить» → перетащите ODA. Включение одним касанием.\n" +
    "• <b>Приложения через VPN</b>: банки и госсервисы можно пустить мимо VPN.\n" +
    "• <b>Kill switch</b>: «KILL SWITCH» в ODA → включите «Постоянная VPN» и «Блокировать соединения без VPN» — " +
    "без VPN трафик не пойдёт.\n" +
    "• <b>Батарея</b>: если VPN отключается сам, снимите для ODA ограничения экономии заряда " +
    "(Настройки → Приложения → ODA → Батарея → «Без ограничений»).";
  return {
    text,
    markup: kb(
      [download(cfg, "primary")],
      [btn("Если не работает", { cb: nav("trouble") }), btn("Пробный день", { cb: nav("trial"), style: "success" })],
      menuRow(),
    ),
  };
}

export function trouble(cfg: Settings): Screen {
  const text =
    "<b>Если не работает</b>\n\n" +
    "1. <b>Ключ активен?</b> Профиль — срок и устройство. Закончился — «Продлить», ключ тот же.\n" +
    "2. <b>«Подключение…» дольше минуты</b> — отключите и включите снова; в «СПОСОБ» выберите «Авто».\n" +
    "3. <b>Другая сеть</b>: переключитесь с мобильной сети на Wi-Fi или наоборот — у операторов разные " +
    "ограничения.\n" +
    "4. <b>Другой VPN</b> мешает: отключите его в настройках Android (Сеть → VPN).\n" +
    "5. <b>Одно приложение не открывается</b> — проверьте «Приложения через VPN»: не стоит ли оно мимо VPN.\n" +
    "6. <b>Медленно</b> — «ТЕСТ СКОРОСТИ» в ODA покажет пинг и загрузку; пришлите итог в поддержку.\n" +
    "7. <b>«Ключ привязан к другому устройству»</b> — Профиль → «Сменить устройство», затем ключ заново.\n\n" +
    "Не помогло — «Поддержка»: приложите журнал (ODA → «ЖУРНАЛ» → «КОПИРОВАТЬ»).";
  return {
    text,
    markup: kb([btn("Профиль", { cb: nav("profile") }), btn("Поддержка", { cb: nav("support") })], menuRow()),
  };
}

export function privacy(cfg: Settings): Screen {
  const text =
    "<b>Конфиденциальность</b>\n\n" +
    "<b>Что хранится</b>\n" +
    "• Telegram ID и имя пользователя — чтобы показать ваши ключи\n" +
    "• ключи: тариф, число устройств, даты активации и окончания\n" +
    "• устройства ключа: их идентификатор в ODA, модель телефона, время последней проверки ключа\n" +
    "• включён ли сейчас VPN в ODA, каким способом и версия ODA — чтобы видеть, что подключение работает\n" +
    "• сообщения в поддержку\n\n" +
    "<b>Что не хранится</b>\n" +
    "• какие сайты и приложения вы открываете, ваш трафик и его содержимое\n" +
    "• IP-адреса: после неудачного ввода ключа адрес учитывается 10 минут только для защиты от подбора " +
    "ключей, затем стирается\n\n" +
    "<b>Как идёт трафик</b>\n" +
    "Трафик не проходит через наши серверы. Способ «Обход DPI» соединяет телефон с сайтом напрямую — " +
    "сайт видит ваш IP. Psiphon и Tor ведут трафик через свои сети: сайт видит адрес их сервера.\n\n" +
    "Удалить данные — напишите в «Поддержку».";
  return { text, markup: kb([btn("Правила", { cb: nav("rules") })], menuRow()) };
}

export function rules(cfg: Settings): Screen {
  const text =
    "<b>Правила</b>\n\n" +
    "1. Ключ — для личного использования на числе устройств тарифа. Передавать и перепродавать ключ нельзя: " +
    "за это ключ отключается.\n" +
    "2. Срок тарифа идёт с ввода ключа в приложении и не останавливается.\n" +
    "3. ODA не работает там, где оператор пропускает только «разрешённые» сайты (белые списки) — " +
    "это проверьте пробным днём.\n" +
    "4. Пользуйтесь ODA в соответствии с законами вашей страны.\n" +
    "5. Возврат и замена ключа: [УСЛОВИЯ ВОЗВРАТА].\n" +
    "6. Поддержка отвечает: [ВРЕМЯ ОТВЕТА].";
  return { text, markup: kb([btn("Конфиденциальность", { cb: nav("privacy") })], menuRow()) };
}
