// Сервер ключей для приложения ODA — как bot/oda_bot/api.py.
// POST /v1/activate {"key","device_id","device_name"} — ввод ключа: первая активация запускает срок,
//                   устройство занимает место; ответ — подписанная лицензия.
// POST /v1/check    {"key","device_id"} — проверка раз в сутки и при запуске; ответ — свежая лицензия.
// GET  /v1/pubkey   — открытый ключ подписи (он же вшит в приложение).
// GET  /health      — жив ли сервер.
// Ошибки: {"status": "<код>", "message": "<текст для экрана приложения>"}.
import { DAY, Db, type Key, Result, type ResultCode, secondsLeft, tariffTitle } from "./db";
import { normalize } from "./keys";
import { b64url, type Signer } from "./license";

const DEVICE_ID = /^[A-Za-z0-9_\-]{8,128}$/;
// Неудачных вводов ключа с одного адреса — не больше стольких за окно: перебор ключей бессмыслен
export const FAIL_LIMIT = 10;
export const FAIL_WINDOW = 600;
const MAX_BODY = 4096;

const MESSAGES: Record<Exclude<ResultCode, "ok">, [number, string]> = {
  not_found: [404, "Ключ не найден. Проверьте буквы и цифры"],
  revoked: [403, "Ключ отключён. Напишите в поддержку бота"],
  expired: [410, "Тариф закончился. Продлите ключ в Telegram-боте или введите новый"],
  device_limit: [409, "На этом ключе уже все устройства тарифа. Освободите место: бот → Профиль → «Сменить устройство»"],
  device_removed: [401, "Это устройство отвязано от ключа. Введите ключ заново"],
};

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });

function error(status: number, code: string, message: string, k?: Key | null): Response {
  const body: Record<string, unknown> = { status: code, message };
  if (k && code === Result.EXPIRED) body.expires_at = k.expires_at;
  return json(body, status);
}

export class KeyApi {
  constructor(private db: Db, private signer: () => Promise<Signer>, private salt: string) {}

  async handle(req: Request, path: string): Promise<Response | null> {
    if (path === "/health" && req.method === "GET") return json({ status: "ok", time: Math.floor(Date.now() / 1000) });
    if (path === "/v1/pubkey" && req.method === "GET") return json({ alg: "Ed25519", key: (await this.signer()).publicB64 });
    if (path === "/v1/activate" && req.method === "POST") return this.keyRequest(req, true);
    if (path === "/v1/check" && req.method === "POST") return this.keyRequest(req, false);
    return null;
  }

  /** Адрес хранится только как хэш с солью (секрет сервера) — и только 10 минут. */
  private async ipTag(req: Request): Promise<string> {
    const ip = req.headers.get("cf-connecting-ip") ?? "?";
    const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(this.salt + "|" + ip));
    return b64url(new Uint8Array(h).slice(0, 12));
  }

  private async keyRequest(req: Request, activate: boolean): Promise<Response> {
    const now = Math.floor(Date.now() / 1000);
    const ip = await this.ipTag(req);
    if ((await this.db.fails(ip, FAIL_WINDOW, now)) >= FAIL_LIMIT) {
      return error(429, "too_many", "Слишком много попыток. Подождите 10 минут");
    }
    let body: Record<string, unknown>;
    try {
      const raw = await req.text();
      if (raw.length > MAX_BODY) throw new Error("big");
      body = JSON.parse(raw);
      if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("not object");
    } catch {
      return error(400, "bad_request", "Неверный запрос");
    }
    const key = normalize(String(body.key ?? ""));
    const deviceId = String(body.device_id ?? "");
    const name = String(body.device_name ?? "").slice(0, 64);
    if (!DEVICE_ID.test(deviceId)) return error(400, "bad_request", "Неверный запрос");
    if (key === null) {
      await this.db.addFail(ip, now);
      const [status, message] = MESSAGES.not_found;
      return error(status, Result.NOT_FOUND, message);
    }
    const [result, k] = activate ? await this.db.activate(key, deviceId, name, now) : await this.db.check(key, deviceId, now);
    if (result !== Result.OK) {
      if (result === Result.NOT_FOUND) await this.db.addFail(ip, now);
      const [status, message] = MESSAGES[result];
      return error(status, result, message, k);
    }
    return json(await this.license(k!, deviceId, now));
  }

  private async license(k: Key, deviceId: string, now: number) {
    const token = await (await this.signer()).sign({
      v: 1, key: k.key, dev: deviceId, tariff: k.tariff, devices: k.devices_max, exp: k.expires_at, iat: now,
    });
    return {
      status: Result.OK, license: token, key: k.key, tariff: k.tariff, tariff_title: tariffTitle(k),
      devices_max: k.devices_max, devices_used: (await this.db.devices(k.key)).length, activated_at: k.activated_at,
      expires_at: k.expires_at, days_left: Math.floor(secondsLeft(k, now) / DAY), server_time: now,
    };
  }
}
