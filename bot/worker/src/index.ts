// Точка входа Cloudflare Worker: webhook Telegram (/tg), сервер ключей (/v1/*, /health) и настройка (/setup).
import { KeyApi, json } from "./api";
import { type Env, loadSettings, settings } from "./config";
import { type CsPayout, type CsTransaction, verifyCashera } from "./cashera";
import { type ApkRelease, downloadPage, validRelease, verifyGithubOidc } from "./ci";
import { Db } from "./db";
import { ADMIN_COMMANDS, Bot, COMMANDS, DESCRIPTION, SHORT_DESCRIPTION } from "./handlers";
import { Signer } from "./license";
import { Telegram, type TgUpdate } from "./telegram";

// Ключ подписи загружается один раз на экземпляр воркера
let signer: { secret: string; p: Promise<Signer> } | null = null;
function getSigner(env: Env): Promise<Signer> {
  if (!signer || signer.secret !== env.LICENSE_KEY) signer = { secret: env.LICENSE_KEY, p: Signer.load(env.LICENSE_KEY) };
  return signer.p;
}

/** Сравнение секрета без утечки по времени. */
function sameSecret(a: string | null, b: string): boolean {
  if (!a || !b || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const db = new Db(env.DB);
    const tg = new Telegram(env.BOT_TOKEN, env.TG_API || undefined);

    if (url.pathname === "/tg" && req.method === "POST") {
      // Telegram присылает секрет, заданный в setWebhook: чужие запросы сюда не пройдут
      if (!sameSecret(req.headers.get("x-telegram-bot-api-secret-token"), env.WEBHOOK_SECRET)) {
        return new Response("forbidden", { status: 403 });
      }
      let update: TgUpdate;
      try {
        update = (await req.json()) as TgUpdate;
      } catch {
        console.warn("update: не JSON");
        return new Response("ok");
      }
      try {
        await new Bot(await loadSettings(env, env.DB, url.origin), db, tg).handle(update);
      } catch (e) {
        // Ответ 200 и при ошибке: иначе Telegram будет присылать то же обновление снова и снова
        console.error("update:", e instanceof Error ? e.stack : e);
      }
      return new Response("ok");
    }

    // ---------- APK: страница загрузки и файл (из GitHub Releases, через воркер — один адрес для всех) ----------
    if (url.pathname === "/download" || url.pathname === "/download/") {
      const raw = await db.getSetting("apk_release");
      const rel = raw ? (JSON.parse(raw) as ApkRelease) : null;
      return new Response(downloadPage(rel, settings(env).botUsername), {
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-cache" },
      });
    }
    if (url.pathname.startsWith("/download/") && url.pathname.endsWith(".apk") && (req.method === "GET" || req.method === "HEAD")) {
      const raw = await db.getSetting("apk_release");
      if (!raw) return new Response("APK ещё не выпущен", { status: 404 });
      const rel = JSON.parse(raw) as ApkRelease;
      const up = await fetch(rel.url, { method: req.method, redirect: "follow" });
      if (!up.ok) {
        console.error(`apk: GitHub ${up.status}`);
        return Response.redirect(rel.url, 302); // запасной путь — прямо с GitHub
      }
      return new Response(up.body, {
        headers: {
          "content-type": "application/vnd.android.package-archive",
          "content-disposition": `attachment; filename="ODA-${rel.version}.apk"`,
          "content-length": String(rel.size),
          "cache-control": "public, max-age=300",
        },
      });
    }

    // ---------- сборка в GitHub Actions (токен OIDC вместо секретов в публичном репозитории) ----------
    if ((url.pathname === "/ci/signing-key" || url.pathname === "/ci/release") && req.method === "POST") {
      const v = await verifyGithubOidc(env, req.headers.get("authorization"));
      if (!v.ok) {
        console.warn(`ci: отказ — ${v.why}`);
        return json({ status: "forbidden", message: v.why }, 403);
      }
      if (url.pathname === "/ci/signing-key") {
        if (!env.ODA_KEYSTORE_B64 || !env.ODA_KEYSTORE_PASS) return json({ status: "error", message: "ключ подписи не задан" }, 500);
        return json({ keystore: env.ODA_KEYSTORE_B64, password: env.ODA_KEYSTORE_PASS, alias: "oda" });
      }
      let rel: Partial<ApkRelease>;
      try {
        rel = (await req.json()) as Partial<ApkRelease>;
      } catch {
        return json({ status: "error", message: "не JSON" }, 400);
      }
      rel.at = Math.floor(Date.now() / 1000);
      if (!validRelease(rel)) return json({ status: "error", message: "неверные данные выпуска" }, 400);
      await db.setSetting("apk_release", JSON.stringify(rel));
      return json({ status: "ok", download: `${url.origin}/download` });
    }

    if (url.pathname === "/cashera" && req.method === "POST") {
      // Webhook Cashera: заголовки X-Api-Key и X-Secret — ключ и секрет мерчанта. Неизвестные события — 200
      const cfg = await loadSettings(env, env.DB, url.origin);
      if (!verifyCashera(cfg.csKey, cfg.csSecret, req.headers.get("x-api-key"), req.headers.get("x-secret"))) {
        return new Response("unauthorized", { status: 401 });
      }
      try {
        const u = (await req.json()) as { event?: string; transaction?: CsTransaction; payout?: CsPayout };
        const bot = new Bot(cfg, db, tg);
        if (u.event === "transaction.status_updated" && u.transaction?.status === "paid") await bot.cardPaid(u.transaction);
        if (u.event === "payout.status_updated" && u.payout) await bot.payoutUpdated(u.payout);
      } catch (e) {
        // 500 — Cashera повторит (до 3 раз); выдача ключа защищена от повтора
        console.error("cashera:", e instanceof Error ? e.stack : e);
        return new Response("error", { status: 500 });
      }
      return new Response("ok");
    }

    if (url.pathname === "/setup" && req.method === "POST") {
      // Подключение бота к этому адресу: webhook с секретом, меню команд и описание бота (вызывает deploy.sh)
      if (!sameSecret(req.headers.get("x-setup-secret"), env.WEBHOOK_SECRET)) return new Response("forbidden", { status: 403 });
      const hook = await tg.call("setWebhook", {
        url: `${url.origin}/tg`,
        secret_token: env.WEBHOOK_SECRET,
        allowed_updates: ["message", "callback_query", "pre_checkout_query"],
        drop_pending_updates: true,
      });
      const commands = await tg.call("setMyCommands", { commands: COMMANDS });
      // Администраторам — ещё /admin в меню команд (только в их чате)
      for (const id of settings(env).adminIds) {
        await tg.call("setMyCommands", { commands: ADMIN_COMMANDS, scope: { type: "chat", chat_id: id } });
      }
      const profile = [
        await tg.call("setMyDescription", { description: DESCRIPTION }),
        await tg.call("setMyShortDescription", { short_description: SHORT_DESCRIPTION }),
      ];
      const me = await tg.call("getMe", {});
      return json({ webhook: hook, commands, profile: { ok: profile.every((r) => r.ok) }, me: me.result ?? me, pubkey: (await getSigner(env)).publicB64 });
    }

    try {
      const res = await new KeyApi(db, () => getSigner(env), env.WEBHOOK_SECRET, settings(env)).handle(req, url.pathname);
      if (res) return res;
    } catch (e) {
      console.error("api:", e instanceof Error ? e.stack : e);
      return json({ status: "error", message: "Сервер ключей временно недоступен. Попробуйте позже" }, 500);
    }
    if (url.pathname === "/" && req.method === "GET") return new Response("ODA VPN: бот и сервер ключей\n");
    return json({ status: "not_found", message: "Нет такого адреса" }, 404);
  },
} satisfies ExportedHandler<Env>;
