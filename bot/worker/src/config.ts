// Настройки из wrangler.toml [vars] и секретов — как bot/oda_bot/config.py.

export interface Env {
  DB: D1Database;
  BOT_TOKEN: string;
  LICENSE_KEY: string;
  WEBHOOK_SECRET: string;
  TG_API?: string;
  PRICE_WEEK?: string;
  PRICE_MONTH?: string;
  PRICE_3M?: string;
  EXTRA_DEVICE_PCT?: string;
  FREE_MODE?: string;
  APK_URL?: string;
  SUPPORT?: string;
  ADMIN_IDS?: string;
  BOT_USERNAME?: string;
  STARS_WEEK?: string;
  STARS_MONTH?: string;
  STARS_3M?: string;
}

export interface Settings {
  priceWeek: number;
  priceMonth: number;
  price3m: number;
  extraDevicePct: number;
  freeMode: boolean;
  apkUrl: string;
  support: string;
  /** Администраторы — по Telegram ID (ADMIN_IDS в wrangler.toml): меню /admin, обращения в поддержку. */
  adminIds: number[];
  botUsername: string;
  /** Цены в Telegram Stars (0 — оплата звёздами выключена). */
  starsWeek: number;
  starsMonth: number;
  stars3m: number;
  /** Реквизиты для перевода по СБП (пусто — СБП выключена). */
  sbpPhone: string;
  sbpBank: string;
  sbpName: string;
}

/** Что администратор может поменять в боте (таблица settings): имя → поле Settings. */
export const EDITABLE: Record<string, { field: keyof Settings; kind: "int" | "text" | "bool"; title: string }> = {
  free_mode: { field: "freeMode", kind: "bool", title: "Ключи без оплаты" },
  price_week: { field: "priceWeek", kind: "int", title: "Цена «1 неделя», ₽" },
  price_month: { field: "priceMonth", kind: "int", title: "Цена «1 месяц», ₽" },
  price_q: { field: "price3m", kind: "int", title: "Цена «3 месяца», ₽" },
  stars_week: { field: "starsWeek", kind: "int", title: "Цена «1 неделя», звёзды" },
  stars_month: { field: "starsMonth", kind: "int", title: "Цена «1 месяц», звёзды" },
  stars_q: { field: "stars3m", kind: "int", title: "Цена «3 месяца», звёзды" },
  extra_pct: { field: "extraDevicePct", kind: "int", title: "Наценка за устройство, %" },
  sbp_phone: { field: "sbpPhone", kind: "text", title: "СБП: номер телефона" },
  sbp_bank: { field: "sbpBank", kind: "text", title: "СБП: банк" },
  sbp_name: { field: "sbpName", kind: "text", title: "СБП: получатель (как в банке)" },
};

const int = (v: string | undefined, d: number) => {
  const n = parseInt(v ?? "", 10);
  return Number.isFinite(n) ? n : d;
};

export function settings(env: Env): Settings {
  return {
    priceWeek: int(env.PRICE_WEEK, 0),
    priceMonth: int(env.PRICE_MONTH, 0),
    price3m: int(env.PRICE_3M, 0),
    extraDevicePct: int(env.EXTRA_DEVICE_PCT, 50),
    freeMode: (env.FREE_MODE ?? "1").trim() !== "0",
    apkUrl: (env.APK_URL ?? "").trim(),
    support: (env.SUPPORT ?? "").trim(),
    adminIds: (env.ADMIN_IDS ?? "").split(/[\s,]+/).filter((x) => /^\d+$/.test(x)).map(Number),
    botUsername: (env.BOT_USERNAME ?? "oda_connect_vpn_bot").trim().replace(/^@/, ""),
    starsWeek: int(env.STARS_WEEK, 0),
    starsMonth: int(env.STARS_MONTH, 0),
    stars3m: int(env.STARS_3M, 0),
    sbpPhone: "",
    sbpBank: "",
    sbpName: "",
  };
}

/** Настройки из wrangler.toml, поверх — то, что администратор поменял в боте (таблица settings). */
export async function loadSettings(env: Env, d1: D1Database): Promise<Settings> {
  const cfg = settings(env);
  let rows: { name: string; value: string }[] = [];
  try {
    rows = (await d1.prepare("SELECT name, value FROM settings").all<{ name: string; value: string }>()).results;
  } catch {
    // таблицы ещё нет (до миграции 0003) — только wrangler.toml
  }
  const c = cfg as unknown as Record<string, unknown>;
  for (const { name, value } of rows) {
    const e = EDITABLE[name];
    if (!e) continue;
    if (e.kind === "int") c[e.field] = int(value, 0);
    else if (e.kind === "bool") c[e.field] = value === "1";
    else c[e.field] = value;
  }
  return cfg;
}

/** Цена тарифа: рубли и звёзды за один ключ на одно устройство. */
export function basePrice(cfg: Settings, period: string): { rub: number; stars: number } {
  if (period === "week") return { rub: cfg.priceWeek, stars: cfg.starsWeek };
  if (period === "q") return { rub: cfg.price3m, stars: cfg.stars3m };
  return { rub: cfg.priceMonth, stars: cfg.starsMonth };
}
