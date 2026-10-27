// Crypto Pay API (@CryptoBot): счёт в рублях, оплата криптовалютой внутри Telegram — анонимно для обеих сторон
// (покупатель не видит владельца, владелец — данных покупателя), подтверждение — webhook, сразу.
// Деньги копятся на балансе приложения в @CryptoBot; вывод — transfer владельцу в @CryptoBot из меню администратора.
// Документация: help.crypt.bot/crypto-pay-api. Без библиотек: GET с параметрами в строке запроса.

export interface CpInvoice {
  invoice_id: number;
  status: "active" | "paid" | "expired";
  amount: string;
  fiat?: string;
  payload?: string;
  bot_invoice_url: string;
  mini_app_invoice_url?: string;
  paid_asset?: string;
  paid_amount?: string;
}
export interface CpBalance {
  currency_code: string;
  available: string;
  onhold: string;
}

export class CryptoPayError extends Error {}

export class CryptoPay {
  constructor(private token: string, private api: string) {}

  /** Адрес API: настоящая сеть, тестовая (testnet — ненастоящие деньги, для проверки) или подмена в тестах. */
  static endpoint(testnet: boolean, override?: string): string {
    if (override) return override.replace(/\/$/, "");
    return testnet ? "https://testnet-pay.crypt.bot/api" : "https://pay.crypt.bot/api";
  }

  async call<T>(method: string, params: Record<string, string | number | boolean> = {}): Promise<T> {
    const qs = new URLSearchParams(Object.entries(params).map(([k, v]): [string, string] => [k, String(v)])).toString();
    let body: { ok: boolean; result?: T; error?: { code?: number; name?: string } };
    try {
      const r = await fetch(`${this.api}/${method}${qs ? "?" + qs : ""}`, { headers: { "Crypto-Pay-API-Token": this.token } });
      body = await r.json();
    } catch (e) {
      throw new CryptoPayError(`сеть: ${e}`);
    }
    if (!body.ok) throw new CryptoPayError(body.error?.name ?? "UNKNOWN");
    return body.result as T;
  }

  getMe() {
    return this.call<{ app_id: number; name: string; payment_processing_bot_username: string }>("getMe");
  }

  /** Счёт в рублях: покупатель платит любой криптовалютой из @CryptoBot по курсу на момент оплаты. */
  createInvoice(rub: number, description: string, payload: string, botUrl: string) {
    return this.call<CpInvoice>("createInvoice", {
      currency_type: "fiat",
      fiat: "RUB",
      amount: String(rub),
      description: description.slice(0, 1024),
      payload,
      expires_in: 3600,
      paid_btn_name: "openBot",
      paid_btn_url: botUrl,
      allow_comments: false,
    });
  }

  async getInvoice(id: number): Promise<CpInvoice | null> {
    const r = await this.call<{ items: CpInvoice[] }>("getInvoices", { invoice_ids: String(id) });
    return r.items?.[0] ?? null;
  }

  getBalance() {
    return this.call<CpBalance[]>("getBalance");
  }

  /** Перевод с баланса приложения пользователю @CryptoBot (вывод владельцу). spend_id — защита от повтора. */
  transfer(userId: number, asset: string, amount: string, spendId: string) {
    return this.call<{ transfer_id: number; amount: string; asset: string }>("transfer", {
      user_id: userId,
      asset,
      amount,
      spend_id: spendId,
      comment: "ODA: вывод",
    });
  }
}

/** Подпись webhook: HMAC-SHA256(тело, ключ = SHA256(токен)) в hex, заголовок crypto-pay-api-signature. */
export async function verifyWebhook(token: string, body: string, signature: string | null): Promise<boolean> {
  if (!token || !signature) return false;
  const enc = new TextEncoder();
  const secret = await crypto.subtle.digest("SHA-256", enc.encode(token));
  const key = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(body)));
  const hex = [...mac].map((b) => b.toString(16).padStart(2, "0")).join("");
  if (hex.length !== signature.length) return false;
  let d = 0;
  for (let i = 0; i < hex.length; i++) d |= hex.charCodeAt(i) ^ signature.toLowerCase().charCodeAt(i);
  return d === 0;
}
