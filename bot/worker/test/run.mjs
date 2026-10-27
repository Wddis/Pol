// Имитация: воркер запускается локально (wrangler dev, своя D1), вместо Telegram — поддельный Bot API.
// «Пользователи» шлют боту команды и нажимают кнопки (как Telegram — webhook с секретом), «телефоны»
// вводят ключи через /v1/activate и /v1/check; проверяется всё, что видит человек и приложение.
// Запуск: npm test (нужны npm install; сеть не нужна).
import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { generateKeyPairSync, createPublicKey, verify as edVerify } from "node:crypto";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const WRANGLER = join(ROOT, "node_modules", ".bin", "wrangler");
const SECRET = "test-webhook-secret-0123456789";
const DAY = 86400;
const KEY_RE = /^ODA-[ACDEFGHJKMNPQRTUVWXYZ234679]{4}-[ACDEFGHJKMNPQRTUVWXYZ234679]{4}-[ACDEFGHJKMNPQRTUVWXYZ234679]{4}$/;

const b64url = (b) => Buffer.from(b).toString("base64url");

// ---------- поддельный Telegram ----------

const calls = [];
const lastShown = new Map();
let notModified = 0;
let msgId = 100;
const tgServer = createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const m = req.url.match(/^\/bot([^/]+)\/(\w+)$/);
    const params = body ? JSON.parse(body) : {};
    calls.push({ token: m?.[1], method: m?.[2], params });
    let result = true;
    if (m?.[2] === "sendMessage") result = { message_id: ++msgId, chat: { id: params.chat_id } };
    if (m?.[2] === "getMe") result = { id: 42, is_bot: true, username: "oda_test_bot" };
    res.setHeader("content-type", "application/json");
    // Как настоящий Telegram: правка тем же текстом и кнопками — ошибка «not modified»
    if (m?.[2] === "editMessageText") {
      const id = `${params.chat_id}:${params.message_id}`;
      const shown = JSON.stringify([params.text, params.reply_markup]);
      const same = lastShown.get(id) === shown;
      lastShown.set(id, shown);
      if (same) {
        notModified++;
        res.end(JSON.stringify({ ok: false, error_code: 400, description: "Bad Request: message is not modified" }));
        return;
      }
    }
    res.end(JSON.stringify({ ok: true, result }));
  });
});
await new Promise((r) => tgServer.listen(0, "127.0.0.1", r));
const TG_PORT = tgServer.address().port;

// ---------- воркер ----------

const { privateKey } = generateKeyPairSync("ed25519");
const seed = privateKey.export({ format: "der", type: "pkcs8" }).subarray(-32);
const expectedPub = b64url(createPublicKey(privateKey).export({ format: "der", type: "spki" }).subarray(-32));

const tmp = mkdtempSync(join(tmpdir(), "oda-worker-"));
const envFile = join(tmp, "test.env");
writeFileSync(
  envFile,
  [
    "BOT_TOKEN=123:TEST",
    `LICENSE_KEY=${b64url(seed)}`,
    `WEBHOOK_SECRET=${SECRET}`,
    `TG_API=http://127.0.0.1:${TG_PORT}`,
    "SUPPORT=@oda_support_test",
    "",
  ].join("\n"),
);
const PORT = 18700 + Math.floor(Math.random() * 200);
const BASE = `http://127.0.0.1:${PORT}`;
const persist = join(tmp, "state");
const wenv = { ...process.env, NO_PROXY: "127.0.0.1,localhost", no_proxy: "127.0.0.1,localhost", CI: "1", WRANGLER_SEND_METRICS: "false" };

execFileSync(WRANGLER, ["d1", "migrations", "apply", "DB", "--local", "--persist-to", persist], { cwd: ROOT, env: wenv, stdio: "pipe" });

const dev = spawn(
  WRANGLER,
  ["dev", "--local", "--ip", "127.0.0.1", "--port", String(PORT), "--persist-to", persist, "--env-file", envFile,
   "--show-interactive-dev-session=false"],
  { cwd: ROOT, env: wenv, stdio: ["ignore", "pipe", "pipe"] },
);
let devLog = "";
dev.stdout.on("data", (d) => (devLog += d));
dev.stderr.on("data", (d) => (devLog += d));

function d1(sql) {
  execFileSync(WRANGLER, ["d1", "execute", "DB", "--local", "--persist-to", persist, "--command", sql], { cwd: ROOT, env: wenv, stdio: "pipe" });
}

async function waitReady() {
  for (let i = 0; i < 120; i++) {
    try {
      const r = await fetch(`${BASE}/health`);
      if (r.ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("wrangler dev не запустился:\n" + devLog);
}

// ---------- «пользователи» и «телефоны» ----------

let updateId = 1;
async function update(u, secret = SECRET) {
  return fetch(`${BASE}/tg`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(secret ? { "x-telegram-bot-api-secret-token": secret } : {}) },
    body: typeof u === "string" ? u : JSON.stringify({ update_id: updateId++, ...u }),
  });
}

class User {
  constructor(id, username) {
    this.id = id;
    this.username = username;
    this.screen = null; // последнее сообщение бота: {message_id, text, markup}
  }
  from() {
    return { id: this.id, is_bot: false, first_name: this.username, username: this.username };
  }
  /** Команда или текст: бот шлёт новое сообщение. */
  async say(text) {
    const n = calls.length;
    const r = await update({ message: { message_id: 1, date: 0, chat: { id: this.id, type: "private" }, from: this.from(), text } });
    assert.equal(r.status, 200);
    const sent = calls.slice(n).filter((c) => c.method === "sendMessage");
    assert.equal(sent.length, 1, `на «${text}» — одно сообщение`);
    const p = sent[0].params;
    assert.equal(p.chat_id, this.id);
    assert.equal(p.parse_mode, "HTML");
    this.screen = { message_id: msgId, text: p.text, markup: p.reply_markup };
    checkMarkup(p.reply_markup);
    return this.screen;
  }
  /** Нажатие кнопки по данным: бот меняет то же сообщение и отвечает на нажатие ровно один раз. */
  async press(data, { expectEdit = true } = {}) {
    const n = calls.length;
    const q = { id: `q${updateId}`, from: this.from(), chat_instance: "x", data,
      message: { message_id: this.screen?.message_id ?? 1, date: 0, chat: { id: this.id, type: "private" }, text: "..." } };
    const r = await update({ callback_query: q });
    assert.equal(r.status, 200);
    const mine = calls.slice(n);
    const answers = mine.filter((c) => c.method === "answerCallbackQuery");
    assert.equal(answers.length, 1, `нажатие ${data}: один ответ на кнопку`);
    assert.equal(answers[0].params.callback_query_id, q.id);
    const edits = mine.filter((c) => c.method === "editMessageText");
    if (!expectEdit) {
      assert.equal(edits.length, 0, `нажатие ${data}: без правки`);
      return { toast: answers[0].params.text, alert: answers[0].params.show_alert };
    }
    assert.equal(edits.length, 1, `нажатие ${data}: правка сообщения`);
    const p = edits[0].params;
    assert.equal(p.chat_id, this.id);
    assert.equal(p.message_id, q.message.message_id);
    checkMarkup(p.reply_markup);
    this.screen = { message_id: q.message.message_id, text: p.text, markup: p.reply_markup };
    return { ...this.screen, toast: answers[0].params.text };
  }
  /** Нажатие кнопки по надписи на текущем экране. */
  async tap(label) {
    const b = this.button(label);
    assert.ok(b.callback_data, `«${label}» — кнопка бота`);
    return this.press(b.callback_data);
  }
  button(label) {
    const all = this.screen.markup.inline_keyboard.flat();
    const b = all.find((x) => x.text === label) ?? all.find((x) => x.text.includes(label));
    assert.ok(b, `кнопка «${label}» на экране:\n${this.screen.text}\n${all.map((x) => x.text).join(" | ")}`);
    return b;
  }
}

function checkMarkup(m) {
  assert.ok(m && Array.isArray(m.inline_keyboard) && m.inline_keyboard.length > 0, "есть кнопки");
  for (const row of m.inline_keyboard) {
    assert.ok(row.length >= 1 && row.length <= 3, "в ряду 1–3 кнопки");
    for (const b of row) {
      const kinds = ["callback_data", "url", "copy_text"].filter((k) => b[k] !== undefined);
      assert.equal(kinds.length, 1, `у кнопки «${b.text}» одно действие`);
      if (b.callback_data) assert.ok(Buffer.byteLength(b.callback_data) <= 64, "данные кнопки ≤ 64 байт");
      if (b.style) assert.ok(["primary", "success", "danger"].includes(b.style));
      assert.ok(!("icon_custom_emoji_id" in b), "без иконок custom emoji");
    }
  }
}

let ipN = 1;
async function app(path, body, ip = "198.51.100.1") {
  const r = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": ip },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
  return { status: r.status, body: await r.json() };
}

function verifyLicense(token, pub) {
  const [d, s] = token.split(".");
  const spki = Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), Buffer.from(pub, "base64url")]);
  const key = createPublicKey({ key: spki, format: "der", type: "spki" });
  assert.ok(edVerify(null, Buffer.from(d, "base64url"), key, Buffer.from(s, "base64url")), "подпись лицензии верна");
  const json = Buffer.from(d, "base64url").toString();
  // Как json.dumps(sort_keys=True, separators=(",", ":")) у Python
  const obj = JSON.parse(json);
  assert.equal(json, JSON.stringify(Object.fromEntries(Object.keys(obj).sort().map((k) => [k, obj[k]]))));
  return obj;
}

// ---------- сценарии ----------

const results = [];
async function step(name, fn) {
  try {
    await fn();
    results.push([true, name]);
    console.log(`  ✓ ${name}`);
  } catch (e) {
    results.push([false, name]);
    console.log(`  ✗ ${name}\n    ${String(e.stack || e).split("\n").slice(0, 6).join("\n    ")}`);
  }
}

try {
  await waitReady();
  console.log(`Воркер: ${BASE}, поддельный Telegram: 127.0.0.1:${TG_PORT}\n`);
  const alice = new User(1001, "alice");
  const bob = new User(1002, "bob");
  const mallory = new User(1003, "mallory");
  let aliceKey, pub;

  await step("сервер ключей жив, открытый ключ — от секрета LICENSE_KEY", async () => {
    const h = await (await fetch(`${BASE}/health`)).json();
    assert.equal(h.status, "ok");
    const pk = await (await fetch(`${BASE}/v1/pubkey`)).json();
    assert.deepEqual(pk, { alg: "Ed25519", key: expectedPub });
    pub = pk.key;
  });

  await step("webhook без секрета или с чужим — отказ, бот молчит", async () => {
    const n = calls.length;
    assert.equal((await update({ message: { text: "/start" } }, null)).status, 403);
    assert.equal((await update({ message: { text: "/start" } }, "wrong-secret-wrong-secret-wrong!")).status, 403);
    assert.equal(calls.length, n);
  });

  await step("/setup: webhook на /tg с секретом и меню команд", async () => {
    assert.equal((await fetch(`${BASE}/setup`, { method: "POST" })).status, 403);
    const n = calls.length;
    const r = await fetch(`${BASE}/setup`, { method: "POST", headers: { "x-setup-secret": SECRET } });
    assert.equal(r.status, 200);
    const body = await r.json();
    assert.equal(body.pubkey, expectedPub);
    const hook = calls.slice(n).find((c) => c.method === "setWebhook");
    assert.equal(hook.params.url, `${BASE}/tg`);
    assert.equal(hook.params.secret_token, SECRET);
    assert.equal(hook.token, "123:TEST");
    const cmds = calls.slice(n).find((c) => c.method === "setMyCommands");
    assert.deepEqual(cmds.params.commands.map((c) => c.command), ["start", "buy", "trial", "profile", "help"]);
  });

  await step("/start — главное меню с цветными кнопками", async () => {
    const s = await alice.say("/start");
    assert.ok(s.text.startsWith("<b>ODA</b>"));
    assert.ok(s.text.includes("первый день — бесплатно"));
    const rows = s.markup.inline_keyboard;
    assert.deepEqual(rows.map((r) => r.map((b) => b.text)), [
      ["Купить ключ"], ["Пробный день", "Профиль"], ["Приложение", "О VPN"], ["Как подключить", "Поддержка"]]);
    assert.equal(rows[0][0].style, "primary");
    assert.equal(rows[1][0].style, "success");
    assert.equal(rows[0][0].callback_data, "buy:month:1:0");
  });

  await step("тарифы: выбор срока и устройств, цена «[ЦЕНА]», лимиты 1–5", async () => {
    let s = await alice.tap("Купить ключ");
    assert.ok(s.text.includes("Срок: <b>1 месяц</b> (30 дней)"));
    assert.ok(s.text.includes("[ЦЕНА]"));
    assert.ok(s.text.includes("сейчас ключ выдаётся без оплаты"));
    assert.ok(alice.button("● 1 месяц"));
    s = await alice.tap("3 месяца");
    assert.ok(s.text.includes("(90 дней)"));
    s = await alice.tap("1 неделя");
    assert.ok(s.text.includes("(7 дней)"));
    s = await alice.tap("1 месяц");
    s = await alice.tap("−");
    assert.ok(s.text.includes("Устройств на ключ: <b>1</b> ▰▱▱▱▱"), "меньше 1 нельзя");
    for (let i = 0; i < 6; i++) s = await alice.tap("+");
    assert.ok(s.text.includes("Устройств на ключ: <b>5</b> ▰▰▰▰▰"), "больше 5 нельзя");
    s = await alice.tap("−");
    s = await alice.tap("−");
    s = await alice.tap("−");
    assert.ok(alice.button("2 устройства"));
  });

  await step("получить ключ: формат, копирование, без активации", async () => {
    const s = await alice.tap("Получить ключ");
    aliceKey = s.text.match(/<code>(ODA-[^<]+)<\/code>/)[1];
    assert.match(aliceKey, KEY_RE);
    assert.ok(s.text.includes("Тариф: 1 месяц"));
    assert.ok(s.text.includes("Устройств: до 2"));
    assert.ok(s.text.includes("Срок: 30 дней с активации"));
    const copy = alice.button("Копировать ключ");
    assert.deepEqual(copy.copy_text, { text: aliceKey });
    assert.equal(copy.style, "primary");
  });

  await step("профиль до активации: «не активирован», срок начнётся с ввода", async () => {
    const s = await alice.tap("Мой профиль");
    assert.ok(s.text.includes(`<code>${aliceKey}</code> — не активирован`));
    assert.ok(s.text.includes("1 месяц: 30 дней начнутся с ввода ключа в приложении"));
    assert.ok(s.text.includes("Устройства: 0 из 2"));
  });

  await step("пробный день — один раз на аккаунт (и при двух нажатиях подряд)", async () => {
    const first = await bob.say("/trial");
    assert.ok(first.text.includes("Ключ готов"));
    assert.ok(first.text.includes("Тариф: Пробный день"));
    assert.ok(first.text.includes("Срок: 1 день с активации"));
    assert.ok(first.text.includes("Устройств: до 1"));
    const again = await bob.say("/trial");
    assert.ok(again.text.includes("Пробный день уже использован"));
    await bob.say("/start");
    const viaButton = await bob.tap("Пробный день");
    assert.ok(viaButton.text.includes("уже использован"));
    // Одновременные нажатия нового пользователя: ключ один
    const carol = new User(1004, "carol");
    await carol.say("/start");
    const n = calls.length;
    await Promise.all([1, 2, 3].map(() => update({ callback_query: { id: "qc", from: carol.from(), chat_instance: "x", data: "nav:trial",
      message: { message_id: 5, date: 0, chat: { id: carol.id, type: "private" } } } })));
    const keys = calls.slice(n).filter((c) => c.method === "editMessageText" && c.params.text.includes("Ключ готов"));
    assert.equal(keys.length, 1, "три одновременных нажатия — один пробный ключ");
  });

  let lic;
  await step("приложение: активация ключа (ввод как угодно) → подписанная лицензия", async () => {
    const typed = aliceKey.toLowerCase().replace(/-/g, " ").replace(/^oda /, "");
    const r = await app("/v1/activate", { key: typed, device_id: "phone-A-0001", device_name: "Pixel <7>" });
    assert.equal(r.status, 200, JSON.stringify(r.body));
    assert.equal(r.body.status, "ok");
    assert.equal(r.body.key, aliceKey);
    assert.equal(r.body.tariff, "month");
    assert.equal(r.body.tariff_title, "1 месяц");
    assert.equal(r.body.devices_max, 2);
    assert.equal(r.body.devices_used, 1);
    assert.equal(r.body.days_left, 30);
    assert.equal(r.body.expires_at - r.body.activated_at, 30 * DAY);
    lic = verifyLicense(r.body.license, pub);
    assert.deepEqual(Object.keys(lic).sort(), ["dev", "devices", "exp", "iat", "key", "tariff", "v"]);
    assert.equal(lic.v, 1);
    assert.equal(lic.key, aliceKey);
    assert.equal(lic.dev, "phone-A-0001");
    assert.equal(lic.exp, r.body.expires_at);
  });

  await step("подделанная лицензия не проходит проверку", async () => {
    const r = await app("/v1/check", { key: aliceKey, device_id: "phone-A-0001" });
    const [d, s] = r.body.license.split(".");
    const forged = b64url(Buffer.from(Buffer.from(d, "base64url").toString().replace(/"exp":\d+/, '"exp":4102444800')));
    assert.throws(() => verifyLicense(`${forged}.${s}`, pub));
  });

  await step("устройства: второе — да, третье — «все устройства тарифа», повторный ввод — без нового места", async () => {
    assert.equal((await app("/v1/activate", { key: aliceKey, device_id: "phone-B-0002", device_name: "Galaxy" })).status, 200);
    const c = await app("/v1/activate", { key: aliceKey, device_id: "phone-C-0003", device_name: "Redmi" });
    assert.equal(c.status, 409);
    assert.equal(c.body.status, "device_limit");
    assert.ok(c.body.message.includes("Сменить устройство"));
    const again = await app("/v1/activate", { key: aliceKey, device_id: "phone-A-0001", device_name: "Pixel 7" });
    assert.equal(again.status, 200);
    assert.equal(again.body.devices_used, 2);
  });

  await step("два новых телефона одновременно не превышают лимит", async () => {
    await bob.say("/start"); // у Боба новый ключ на 1 устройство
    await bob.tap("Купить ключ");
    const issued = await bob.tap("Получить ключ");
    const k = issued.text.match(/<code>(ODA-[^<]+)<\/code>/)[1];
    const rs = await Promise.all([1, 2, 3, 4].map((i) => app("/v1/activate", { key: k, device_id: `race-dev-000${i}` })));
    assert.equal(rs.filter((r) => r.status === 200).length, 1, rs.map((r) => r.status).join(","));
    assert.equal(rs.filter((r) => r.status === 409).length, 3);
  });

  await step("проверка раз в сутки: своё устройство — свежая лицензия, чужое — «отвязано»", async () => {
    const ok = await app("/v1/check", { key: aliceKey, device_id: "phone-B-0002" });
    assert.equal(ok.status, 200);
    assert.equal(verifyLicense(ok.body.license, pub).dev, "phone-B-0002");
    const no = await app("/v1/check", { key: aliceKey, device_id: "phone-Z-9999" });
    assert.equal(no.status, 401);
    assert.equal(no.body.status, "device_removed");
  });

  await step("профиль после активации: срок, полоса, устройства (имя экранировано)", async () => {
    const s = await alice.say("/profile");
    assert.ok(s.text.includes(`<code>${aliceKey}</code> — активен`));
    assert.ok(s.text.includes("<b>30 дней</b> осталось · до "));
    assert.ok(s.text.includes("▱▱▱▱▱▱▱▱▱▱"));
    assert.ok(s.text.includes("Устройства: 2 из 2"));
    assert.ok(s.text.includes("• Pixel 7 — был в сети "));
    assert.ok(!s.text.includes("Pixel <7>"));
    assert.ok(!s.text.includes("свободно"));
    assert.equal(alice.button("Продлить").style, "success");
  });

  await step("имя устройства с HTML экранируется", async () => {
    await app("/v1/check", { key: aliceKey, device_id: "phone-A-0001" });
    await app("/v1/activate", { key: aliceKey, device_id: "phone-A-0001", device_name: "<b>x</b> & \"y\"" });
    const s = await alice.say("/profile");
    assert.ok(s.text.includes("• &lt;b&gt;x&lt;/b&gt; &amp; &quot;y&quot; — был в сети"));
  });

  await step("чужой ключ: кнопки Мэллори с ключом Алисы не работают", async () => {
    await mallory.say("/start");
    for (const data of [`key:reset_ok:${aliceKey}:`, `key:extend:${aliceKey}:q`, `key:show:${aliceKey}:`]) {
      const r = await mallory.press(data, { expectEdit: false });
      assert.equal(r.toast, "Ключ не найден");
      assert.equal(r.alert, true);
    }
    assert.equal((await app("/v1/check", { key: aliceKey, device_id: "phone-A-0001" })).status, 200, "устройства Алисы на месте");
  });

  await step("продление тем же ключом: +1 неделя к оставшемуся, одна всплывающая надпись", async () => {
    await alice.say("/profile");
    let s = await alice.tap("Продлить");
    assert.ok(s.text.includes("<b>Продление</b>"));
    assert.ok(s.text.includes("сейчас продление без оплаты"));
    s = await alice.tap("1 неделя");
    assert.equal(s.toast, "Продлено: +1 неделя");
    assert.ok(s.text.includes("<b>37 дней</b> осталось"));
    const r = await app("/v1/check", { key: aliceKey, device_id: "phone-A-0001" });
    assert.ok([36, 37].includes(r.body.days_left), "37 суток минус прошедшие секунды");
  });

  await step("сменить устройство: подтверждение (красная кнопка), отвязка, ввод на новом телефоне", async () => {
    let s = await alice.tap("Сменить устройство");
    assert.ok(s.text.includes("будут отвязаны"));
    assert.ok(s.text.includes("• Galaxy"));
    assert.equal(alice.button("Отвязать все").style, "danger");
    s = await alice.tap("Отмена");
    assert.ok(s.text.includes("<b>Профиль</b>"));
    await alice.tap("Сменить устройство");
    s = await alice.tap("Отвязать все");
    assert.equal(s.toast, "Отвязано устройств: 2");
    assert.ok(s.text.includes("Устройства: 0 из 2"));
    assert.ok(s.text.includes("• свободно: 2"));
    const old = await app("/v1/check", { key: aliceKey, device_id: "phone-A-0001" });
    assert.equal(old.status, 401);
    assert.equal((await app("/v1/activate", { key: aliceKey, device_id: "phone-C-0003", device_name: "Redmi" })).status, 200);
  });

  await step("срок вышел: приложение — «тариф закончился», профиль — «закончился», продление — с текущей минуты", async () => {
    const s = await alice.say("/trial");
    const k = s.text.match(/<code>(ODA-[^<]+)<\/code>/)[1];
    assert.equal((await app("/v1/activate", { key: k, device_id: "trial-dev-01" })).status, 200);
    const past = Math.floor(Date.now() / 1000) - 3600;
    d1(`UPDATE keys SET activated_at=${past - DAY}, expires_at=${past} WHERE key='${k}'`);
    const chk = await app("/v1/check", { key: k, device_id: "trial-dev-01" });
    assert.equal(chk.status, 410);
    assert.equal(chk.body.status, "expired");
    assert.equal(chk.body.expires_at, past);
    const act = await app("/v1/activate", { key: k, device_id: "trial-dev-02" });
    assert.equal(act.status, 410);
    let p = await alice.say("/profile");
    // Профиль показывает действующий ключ (месячный), список — оба
    assert.ok(p.text.includes(aliceKey));
    p = await alice.tap("Мои ключи (2)");
    const row = alice.button(k);
    assert.ok(row.text.endsWith("Пробный день · закончился"));
    p = await alice.press(row.callback_data);
    assert.ok(p.text.includes(`<code>${k}</code> — закончился`));
    assert.ok(p.text.includes("Продлите — ключ останется тем же"));
    await alice.tap("Продлить");
    p = await alice.tap("1 неделя");
    assert.ok(p.text.includes(`<code>${k}</code> — активен`));
    assert.ok(p.text.includes("<b>7 дней</b> осталось"));
    const again = await app("/v1/check", { key: k, device_id: "trial-dev-01" });
    assert.equal(again.status, 200);
    assert.ok([6, 7].includes(again.body.days_left), "7 суток с этой минуты");
  });

  await step("отключённый ключ: «напишите в поддержку», профиль — «отключён»", async () => {
    d1(`UPDATE keys SET revoked=1 WHERE key='${aliceKey}'`);
    const r = await app("/v1/check", { key: aliceKey, device_id: "phone-C-0003" });
    assert.equal(r.status, 403);
    assert.equal(r.body.status, "revoked");
    await alice.say("/profile");
    await alice.tap("Мои ключи");
    const p = await alice.press(alice.button(aliceKey).callback_data);
    assert.ok(p.text.includes("— отключён"));
    d1(`UPDATE keys SET revoked=0 WHERE key='${aliceKey}'`);
  });

  await step("неверные запросы приложения — 400, неизвестный ключ — 404", async () => {
    assert.equal((await app("/v1/activate", "{not json", "203.0.113.5")).status, 400);
    assert.equal((await app("/v1/activate", "[1,2]", "203.0.113.5")).status, 400);
    assert.equal((await app("/v1/activate", { key: aliceKey, device_id: "short" }, "203.0.113.5")).status, 400);
    assert.equal((await app("/v1/activate", { key: aliceKey, device_id: "bad id with spaces" }, "203.0.113.5")).status, 400);
    const nf = await app("/v1/activate", { key: "ODA-AAAA-AAAA-AAAA", device_id: "phone-X-0001" }, "203.0.113.5");
    assert.equal(nf.status, 404);
    assert.equal(nf.body.message, "Ключ не найден. Проверьте буквы и цифры");
    const bad = await app("/v1/activate", { key: "hello", device_id: "phone-X-0001" }, "203.0.113.5");
    assert.equal(bad.status, 404);
    assert.equal((await fetch(`${BASE}/nope`)).status, 404);
  });

  await step("подбор ключей: после 10 неудач с адреса — 429, другой адрес и верный ключ работают", async () => {
    const ip = "192.0.2.77";
    for (let i = 0; i < 10; i++) {
      const r = await app("/v1/activate", { key: `ODA-AAAA-AAAA-AA${"CDEFGHJKMN"[i]}${"C"}`, device_id: "brute-0000" }, ip);
      assert.equal(r.status, 404);
    }
    const blocked = await app("/v1/activate", { key: aliceKey, device_id: "phone-C-0003" }, ip);
    assert.equal(blocked.status, 429);
    assert.equal(blocked.body.status, "too_many");
    assert.equal((await app("/v1/check", { key: aliceKey, device_id: "phone-C-0003" }, "192.0.2.78")).status, 200);
  });

  await step("справка: все разделы открываются и ведут обратно в меню", async () => {
    const titles = {
      "nav:app": "<b>Приложение ODA</b>", "nav:about": "<b>О VPN</b>", "nav:support": "<b>Поддержка</b>",
      "nav:howto": "<b>Как подключить</b>", "nav:trouble": "<b>Если не работает</b>",
      "nav:privacy": "<b>Конфиденциальность</b>", "nav:rules": "<b>Правила</b>", "nav:main": "<b>ODA</b>",
    };
    await bob.say("/start");
    for (const [data, title] of Object.entries(titles)) {
      const s = await bob.press(data);
      assert.ok(s.text.startsWith(title), data);
      if (data !== "nav:main") assert.equal(s.markup.inline_keyboard.at(-1)[0].text, "‹ Меню");
    }
    const sup = await bob.press("nav:support");
    assert.ok(sup.text.includes("Напишите: @oda_support_test"));
    const app_ = await bob.press("nav:app");
    assert.ok(app_.text.includes("Ссылка на скачивание появится здесь"));
    const priv = await bob.press("nav:privacy");
    assert.ok(priv.text.includes("Трафик не проходит через наши серверы"));
  });

  await step("/help — «О VPN», любой текст и неизвестная кнопка — меню", async () => {
    assert.ok((await bob.say("/help")).text.startsWith("<b>О VPN</b>"));
    assert.ok((await bob.say("/buy")).text.startsWith("<b>Тариф</b>"));
    assert.ok((await bob.say("привет")).text.startsWith("<b>ODA</b>"));
    assert.ok((await bob.say("/start@oda_test_bot")).text.startsWith("<b>ODA</b>"));
    assert.ok((await bob.press("garbage:data")).text.startsWith("<b>ODA</b>"));
    assert.ok((await bob.press("buy:zzz:99:0")).text.includes("Устройств на ключ: <b>5</b>"));
  });

  await step("профиль без ключей и «нажали то же самое» (not modified) — без ошибок", async () => {
    const dave = new User(1005, "dave");
    const s = await dave.say("/profile");
    assert.ok(s.text.includes("Ключей пока нет"));
    assert.ok(s.text.includes("ID 1005"));
    const before = notModified;
    await dave.press("nav:profile");
    await dave.press("nav:profile");
    assert.ok(notModified > before, "Telegram ответил «not modified»");
  });

  await step("сломанные обновления Telegram — ответ 200, без повтора", async () => {
    assert.equal((await update("{broken")).status, 200);
    assert.equal((await update({ edited_message: { text: "x" } })).status, 200);
    assert.equal((await update({ message: { message_id: 1, chat: { id: -5, type: "group" }, from: { id: 9 }, text: "/start" } })).status, 200);
  });

  await step("база: пользователи и ключи на месте (D1)", async () => {
    const out = execFileSync(WRANGLER, ["d1", "execute", "DB", "--local", "--persist-to", persist, "--json", "--command",
      "SELECT (SELECT COUNT(*) FROM users) AS u, (SELECT COUNT(*) FROM keys) AS k, (SELECT COUNT(*) FROM fails) AS f"],
      { cwd: ROOT, env: wenv }).toString();
    const row = JSON.parse(out.slice(out.indexOf("[")))[0].results[0];
    assert.ok(row.u >= 5, JSON.stringify(row));
    assert.ok(row.k >= 5, JSON.stringify(row));
    assert.ok(row.f >= 10, "неудачные вводы учитываются");
  });
} finally {
  dev.kill("SIGTERM");
  tgServer.close();
  await new Promise((r) => setTimeout(r, 300));
  rmSync(tmp, { recursive: true, force: true });
}

const failed = results.filter(([ok]) => !ok);
const errors = devLog.replace(/\x1b\[[0-9;]*m/g, "").split("\n").filter((l) => /\[ERROR\]|Uncaught/.test(l));
if (errors.length) console.log("\nОшибки воркера:\n" + errors.join("\n"));
console.log(`\n${results.length - failed.length}/${results.length} сценариев прошли`);
process.exit(failed.length || errors.length ? 1 : 0);
