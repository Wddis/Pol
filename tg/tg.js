// Usage: node tg.js sendcode | node tg.js signin <code> [password] | node tg.js run <file.js>
process.env.GLOBAL_AGENT_HTTPS_PROXY = process.env.HTTPS_PROXY;
process.env.GLOBAL_AGENT_HTTP_PROXY = process.env.HTTPS_PROXY;
require("global-agent/bootstrap");
const fs = require("fs");
const path = require("path");
const { TelegramClient, Api } = require("telegram");
const { StringSession } = require("telegram/sessions");
const { computeCheck } = require("telegram/Password");
const { PromisedWebSockets } = require("telegram/extensions/PromisedWebSockets");

// The proxy only allows web.telegram.org hostnames, not raw DC IPs.
function dcHost(ip) {
  if (ip.startsWith("149.154.175.1")) return "aurora";
  if (ip.startsWith("149.154.175.")) return "pluto";
  if (ip.startsWith("149.154.167.9")) return "vesta";
  if (ip.startsWith("149.154.167.")) return "venus";
  if (ip.startsWith("91.108.56.")) return "flora";
  throw new Error("unknown DC ip " + ip);
}
PromisedWebSockets.prototype.getWebSocketLink = function (ip, port, test) {
  return `wss://${dcHost(ip)}.web.telegram.org/apiws${test ? "_test" : ""}`;
};

const API_ID = Number(process.env.TG_API_ID);
const API_HASH = process.env.TG_API_HASH;
const PHONE = process.env.TG_PHONE;
const SESSION_FILE = path.join(__dirname, "session.txt");
const STATE_FILE = path.join(__dirname, "state.json");

function save(client) {
  fs.writeFileSync(SESSION_FILE, client.session.save(), { mode: 0o600 });
}

async function getClient() {
  const s = fs.existsSync(SESSION_FILE) ? fs.readFileSync(SESSION_FILE, "utf8") : "";
  const client = new TelegramClient(new StringSession(s), API_ID, API_HASH, {
    useWSS: true,
    networkSocket: PromisedWebSockets,
    connection: require("telegram/network").ConnectionTCPObfuscated,
    connectionRetries: 2,
    deviceModel: "Claude Code",
    systemVersion: "Linux",
    appVersion: "1.0",
  });
  client.setLogLevel(process.env.TGLOG || "error");
  await client.connect();
  return client;
}

(async () => {
  const [cmd, ...args] = process.argv.slice(2);
  const client = await getClient();
  try {
    if (cmd === "sendcode") {
      const r = await client.invoke(new Api.auth.SendCode({
        phoneNumber: PHONE, apiId: API_ID, apiHash: API_HASH,
        settings: new Api.CodeSettings({}),
      }));
      fs.writeFileSync(STATE_FILE, JSON.stringify({ phoneCodeHash: r.phoneCodeHash }), { mode: 0o600 });
      console.log("code sent via:", r.type.className);
    } else if (cmd === "qr") {
      // Writes qr.png on every token refresh; waits for pass.txt if 2FA is on.
      const QR = require("qrcode");
      const user = await client.signInUserWithQrCode({ apiId: API_ID, apiHash: API_HASH }, {
        qrCode: async ({ token, expires }) => {
          const url = "tg://login?token=" + token.toString("base64url");
          await QR.toFile(path.join(__dirname, "qr.png"), url, { width: 600, margin: 4 });
          console.log("qr refreshed, expires in", expires - Math.floor(Date.now() / 1000), "s");
        },
        password: async () => {
          console.log("PASSWORD_NEEDED");
          const f = path.join(__dirname, "pass.txt");
          while (!fs.existsSync(f)) await new Promise((r) => setTimeout(r, 2000));
          const p = fs.readFileSync(f, "utf8").trim();
          fs.unlinkSync(f);
          return p;
        },
        onError: async (e) => { console.log("auth error:", e.errorMessage || e.message); return false; },
      });
      console.log("logged in as:", user.firstName, user.username ? "@" + user.username : "");
    } else if (cmd === "resend") {
      const { phoneCodeHash } = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
      const r = await client.invoke(new Api.auth.ResendCode({ phoneNumber: PHONE, phoneCodeHash }));
      fs.writeFileSync(STATE_FILE, JSON.stringify({ phoneCodeHash: r.phoneCodeHash }), { mode: 0o600 });
      console.log("code resent via:", r.type.className, "next:", r.nextType && r.nextType.className, "timeout:", r.timeout);
    } else if (cmd === "signin") {
      const { phoneCodeHash } = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
      try {
        await client.invoke(new Api.auth.SignIn({ phoneNumber: PHONE, phoneCodeHash, phoneCode: args[0] }));
      } catch (e) {
        if (e.errorMessage !== "SESSION_PASSWORD_NEEDED") throw e;
        if (!args[1]) { save(client); console.log("PASSWORD_NEEDED"); return; }
        const pwd = await client.invoke(new Api.account.GetPassword());
        await client.invoke(new Api.auth.CheckPassword({ password: await computeCheck(pwd, args[1]) }));
      }
      const me = await client.getMe();
      console.log("logged in as:", me.firstName, me.username ? "@" + me.username : "");
    } else if (cmd === "password") {
      const pwd = await client.invoke(new Api.account.GetPassword());
      await client.invoke(new Api.auth.CheckPassword({ password: await computeCheck(pwd, args[0]) }));
      const me = await client.getMe();
      console.log("logged in as:", me.firstName, me.username ? "@" + me.username : "");
    } else if (cmd === "run") {
      await require(path.resolve(args[0]))(client, Api);
    }
  } catch (e) {
    console.log("ERROR:", e.errorMessage || e.message);
  } finally {
    save(client);
    await client.disconnect();
    process.exit(0);
  }
})();
