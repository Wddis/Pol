// Cashera (docs.cashera.cash): приём оплаты картой и по СБП на платёжной странице Cashera — покупатель не видит
// ни имени, ни номера владельца; об оплате Cashera сообщает webhook'ом, ключ уходит сразу.
// Деньги копятся на балансе мерчанта в Cashera (RUB); вывод — на карту МИР (например, Т-Банка) из меню администратора.
// Суммы — целые копейки. Приём — заголовок X-Api-Key (pk_…); выплаты — ещё подпись HMAC-SHA256 секретом (sk_…).

export interface CsTransaction {
  uuid: string;
  status: "pending" | "paid" | "canceled" | "failed" | "expired" | "refunded" | "chargeback";
  amount: number;
  currency: string;
  external_id: string;
  payment_url?: string;
  expires_at?: string | null;
}
export interface CsBalance {
  currency: string;
  available: number;
  pending: number;
  frozen: number;
  in_payout: number;
}
export interface CsCardQuote {
  source_amount: number;
  available_amount: number;
  fee_amount: number;
  net_card_rub_amount: number;
  card_payout_limits?: { min_net_amount_minor?: number; max_net_amount_minor?: number };
}
export interface CsPayout {
  uuid: string;
  external_id: string;
  status: "pending" | "processing" | "completed" | "failed";
  amount: number;
  net_amount?: number;
  card_payout?: { net_card_rub_amount?: number } | null;
  failure_reason?: string | null;
}

export class CasheraError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

const enc = new TextEncoder();
const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");

export class Cashera {
  constructor(private key: string, private secret: string, private api = "https://api.cashera.cash/api/v1") {
    this.api = api.replace(/\/$/, "");
  }

  private async req<T>(method: "GET" | "POST", path: string, body?: unknown, signed = false): Promise<T> {
    const raw = body === undefined ? "" : JSON.stringify(body);
    const headers: Record<string, string> = { "X-Api-Key": this.key, Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const url = this.api + path;
    if (signed) {
      // {timestamp}\n{METHOD}\n{путь с /api/v1, без query}\n{sha256_hex(тело)}
      const ts = String(Math.floor(Date.now() / 1000));
      const line = [ts, method, new URL(url).pathname, hex(await crypto.subtle.digest("SHA-256", enc.encode(raw)))].join("\n");
      const k = await crypto.subtle.importKey("raw", enc.encode(this.secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
      headers["X-Timestamp"] = ts;
      headers["X-Signature"] = hex(await crypto.subtle.sign("HMAC", k, enc.encode(line)));
    }
    let r: Response;
    try {
      r = await fetch(url, { method, headers, body: body === undefined ? undefined : raw });
    } catch (e) {
      throw new CasheraError(0, `сеть: ${e}`);
    }
    const text = await r.text();
    let data: unknown = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      // не JSON — ниже ошибка по коду
    }
    if (!r.ok) {
      const d = data as { message?: string; errors?: Record<string, string[]> } | null;
      const first = d?.errors ? Object.values(d.errors)[0]?.[0] : undefined;
      throw new CasheraError(r.status, first ?? d?.message ?? `HTTP ${r.status}`);
    }
    return data as T;
  }

  /** Платёж без payment_method — общая платёжная форма: карта или СБП на выбор покупателя (что включено в кабинете). */
  createPayment(kopecks: number, externalId: string, description: string, callbackUrl: string, returnUrl: string) {
    return this.req<CsTransaction>("POST", "/integration/transactions", {
      amount: kopecks,
      currency: "RUB",
      external_id: externalId,
      description: description.slice(0, 255),
      callback_url: callbackUrl,
      success_url: returnUrl,
      fail_url: returnUrl,
    });
  }

  getPayment(uuid: string) {
    return this.req<CsTransaction>("GET", `/integration/transactions/${encodeURIComponent(uuid)}`);
  }

  /** Балансы в рублях (копейки); ответ — мерчанты ключа со списком балансов. */
  async balanceRub(): Promise<CsBalance | null> {
    const r = await this.req<unknown>("GET", "/integration/balances");
    const list = (Array.isArray(r) ? r : (r as { data?: unknown[] })?.data ?? [r]) as { balances?: CsBalance[] }[];
    for (const m of list) {
      const b = m?.balances?.find((x) => x.currency === "RUB");
      if (b) return b;
    }
    return null;
  }

  /** Расчёт вывода на карту МИР: сколько придёт после комиссии. */
  quoteCard(kopecks: number, card: string) {
    return this.req<CsCardQuote>("POST", "/integration/payouts/quote", { amount: kopecks, currency: "RUB", destination: { type: "card", address: card } }, true);
  }

  /** Заявка на вывод на карту МИР; повтор с тем же external_id не создаёт вторую. */
  payoutCard(kopecks: number, card: string, externalId: string) {
    return this.req<CsPayout>("POST", "/integration/payouts", { amount: kopecks, currency: "RUB", external_id: externalId, destination: { type: "card", address: card } }, true);
  }
}

/** Ссылка на оплату для кнопки Telegram (в ответе бывает без схемы). */
export const payUrl = (u: string) => (/^https?:\/\//.test(u) ? u : `https://${u}`);

/** Webhook Cashera: заголовки X-Api-Key и X-Secret совпадают с ключом и секретом (сравнение без утечки по времени). */
export function verifyCashera(key: string, secret: string, hKey: string | null, hSecret: string | null): boolean {
  const same = (a: string | null, b: string) => {
    if (!a || !b || a.length !== b.length) return false;
    let d = 0;
    for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return d === 0;
  };
  const k = same(hKey, key);
  const s = same(hSecret, secret);
  return k && s;
}
