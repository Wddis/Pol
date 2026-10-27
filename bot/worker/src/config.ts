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
}

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
  };
}
