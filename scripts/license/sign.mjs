// Подписи для проверки License.java: тот же код подписи, что у сервера ключей (bot/worker/src/license.ts).
// node sign.mjs <license.mjs> → JSON: открытый ключ, лицензии (верная, с чужого сервера, подделанная).
import { randomBytes } from "node:crypto";
const { Signer } = await import(process.argv[2]);
const seed = randomBytes(32).toString("base64url");
const s = await Signer.load(seed);
const other = await Signer.load(randomBytes(32).toString("base64url"));
const payload = { v: 1, key: "ODA-ACDE-FGHJ-KMNP", dev: "devAbc123_-xyz", tariff: "custom", devices: 2, exp: 1800000000, iat: 1790000000 };
const good = await s.sign(payload);
const [d, sig] = good.split(".");
const forgedData = Buffer.from(Buffer.from(d, "base64url").toString().replace("1800000000", "4102444800")).toString("base64url");
console.log(JSON.stringify({
  pub: s.publicB64,
  good,
  foreign: await other.sign(payload),
  forged: `${forgedData}.${sig}`,
  forever: await s.sign({ ...payload, exp: 1790000000 + 100 * 365 * 86400 }),
}));
