// Сервер ключей для приложения ODA.
// POST /v1/activate {"key","device_id","device_name","version"} — ввод ключа: первая активация запускает срок,
//                   устройство занимает место; ответ — подписанная лицензия.
// POST /v1/check    {"key","device_id", "vpn"?, "mode"?, "version"?} — проверка при запуске и раз в 6 часов,
//                   и сигнал состояния VPN (подключение, отключение, раз в 15 минут); ответ — свежая лицензия.
// GET  /v1/pubkey   — открытый ключ подписи (он же вшит в приложение).
// GET  /health      — жив ли сервер.
// Ошибки: {"status": "<код>", "message": "<текст для экрана приложения>"}; блокировка — "banned" с reason и until.
import type { Settings } from "./config";
import { DAY, Db, type Key, Result, type ResultCode, secondsLeft, tariffTitle } from "./db";
import { FOREVER, normalize } from "./keys";
import { b64url, type Signer } from "./license";

const DEVICE_ID = /^[A-Za-z0-9_\-]{8,128}$/;
// Неудачных вводов ключа с одного адреса и с одного устройства — не больше стольких за окно
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
  constructor(private db: Db, private signer: () => Promise<Signer>, private salt: string, private cfg: Settings) {}

  async handle(req: Request, path: string): Promise<Response | null> {
    if (path === "/health" && req.method === "GET") return json({ status: "ok", time: Math.floor(Date.now() / 1000) });
    if (path === "/v1/pubkey" && req.method === "GET") return json({ alg: "Ed25519", key: (await this.signer()).publicB64 });
    if (path === "/v1/activate" && req.method === "POST") return this.keyRequest(req, true);
    if (path === "/v1/check" && req.method === "POST") return this.keyRequest(req, false);
    return null;
  }

  /** Отпечаток (адрес или устройство) хранится только как хэш с солью (секрет сервера) — и только 10 минут. */
  private async tag(kind: string, value: string): Promise<string> {
    const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${this.salt}|${kind}|${value}`));
    return b64url(new Uint8Array(h).slice(0, 12));
  }

  private async keyRequest(req: Request, activate: boolean): Promise<Response> {
    const now = Math.floor(Date.now() / 1000);
    let body: Record<string, unknown>;
    try {
      const raw = await req.text();
      if (raw.length > MAX_BODY) throw new Error("big");
      body = JSON.parse(raw);
      if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("not object");
    } catch {
      return error(400, "bad_request", "Неверный запрос");
    }
    const deviceId = String(body.device_id ?? "");
    if (!DEVICE_ID.test(deviceId)) return error(400, "bad_request", "Неверный запрос");
    const ipTag = await this.tag("ip", req.headers.get("cf-connecting-ip") ?? "?");
    const devTag = await this.tag("dev", deviceId);
    // Подбор ключей: и с одного адреса, и с одного телефона (смена сети не помогает)
    if (Math.max(await this.db.fails(ipTag, FAIL_WINDOW, now), await this.db.fails(devTag, FAIL_WINDOW, now)) >= FAIL_LIMIT) {
      return error(429, "too_many", "Слишком много попыток. Подождите 10 минут");
    }
    const key = normalize(String(body.key ?? ""));
    const k = key ? await this.db.getKey(key) : null;
    // Заблокированные устройство, ключ или владелец — отказ до всего остального: новый ключ на том же телефоне не поможет
    const ban = await this.db.appBan(deviceId, k, now);
    if (ban) {
      return json({
        status: "banned",
        message: "Доступ к ODA заблокирован",
        reason: ban.reason,
        until: ban.until,
        support: `https://t.me/${this.cfg.botUsername}?start=support`,
        server_time: now,
      }, 403);
    }
    if (key === null || k === null) {
      await this.db.addFail(ipTag, now);
      await this.db.addFail(devTag, now);
      const [status, message] = MESSAGES.not_found;
      return error(status, Result.NOT_FOUND, message);
    }
    const name = String(body.device_name ?? "").slice(0, 64);
    const version = String(body.version ?? "").slice(0, 32);
    let result: ResultCode;
    let kk: Key | null;
    if (activate) {
      [result, kk] = await this.db.activate(key, deviceId, name, now, version);
    } else {
      const vpn = typeof body.vpn === "boolean" ? { on: body.vpn, mode: String(body.mode ?? "").slice(0, 16), version } : undefined;
      [result, kk] = await this.db.check(key, deviceId, now, vpn);
    }
    if (result !== Result.OK) {
      const [status, message] = MESSAGES[result];
      return error(status, result, message, kk);
    }
    return json(await this.license(kk!, deviceId, now));
  }

  private async license(k: Key, deviceId: string, now: number) {
    const token = await (await this.signer()).sign({
      v: 1, key: k.key, dev: deviceId, tariff: k.tariff, devices: k.devices_max, exp: k.expires_at, iat: now,
    });
    const left = secondsLeft(k, now);
    return {
      status: Result.OK, license: token, key: k.key, tariff: k.tariff, tariff_title: tariffTitle(k),
      devices_max: k.devices_max, devices_used: (await this.db.devices(k.key)).length, activated_at: k.activated_at,
      expires_at: k.expires_at, days_left: Math.floor(left / DAY), seconds_left: left, forever: k.seconds >= FOREVER,
      server_time: now, bot: `https://t.me/${this.cfg.botUsername}`,
    };
  }
}
