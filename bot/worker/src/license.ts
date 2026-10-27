// Лицензия для приложения — как bot/oda_bot/license.py: «<данные>.<подпись>», base64url без «=»,
// данные — JSON с ключами по алфавиту, подпись Ed25519. Закрытый ключ — секрет LICENSE_KEY
// (32 байта base64url, тот же формат, что файл license_ed25519.key у бота на Python).

export function b64url(data: Uint8Array): string {
  let s = "";
  for (const b of data) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function unb64url(text: string): Uint8Array {
  const s = atob(text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (text.length % 4)) % 4));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

// PKCS#8 для Ed25519 = этот заголовок + 32 байта закрытого ключа
const PKCS8_PREFIX = Uint8Array.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]);

/** JSON как json.dumps(sort_keys=True, separators=(",", ":")) — данные лицензии те же, что у Python. */
function canonical(v: unknown): string {
  if (v === null || typeof v !== "object") return JSON.stringify(v);
  if (Array.isArray(v)) return "[" + v.map(canonical).join(",") + "]";
  const o = v as Record<string, unknown>;
  return "{" + Object.keys(o).sort().map((k) => JSON.stringify(k) + ":" + canonical(o[k])).join(",") + "}";
}

// Python пишет не-ASCII как \uXXXX (ensure_ascii) — в лицензии только ASCII, но на всякий случай так же
const asciiOnly = (s: string) => s.replace(/[\u007f-￿]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"));

export class Signer {
  private constructor(private key: CryptoKey, readonly publicB64: string) {}

  static async load(secretB64: string): Promise<Signer> {
    const seed = unb64url(secretB64.trim());
    if (seed.length !== 32) throw new Error("LICENSE_KEY: нужно 32 байта base64url");
    const pkcs8 = new Uint8Array(PKCS8_PREFIX.length + 32);
    pkcs8.set(PKCS8_PREFIX);
    pkcs8.set(seed, PKCS8_PREFIX.length);
    const key = (await crypto.subtle.importKey("pkcs8", pkcs8, { name: "Ed25519" }, true, ["sign"])) as CryptoKey;
    const jwk = (await crypto.subtle.exportKey("jwk", key)) as JsonWebKey;
    return new Signer(key, jwk.x!);
  }

  async sign(payload: Record<string, unknown>): Promise<string> {
    const data = new TextEncoder().encode(asciiOnly(canonical(payload)));
    const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, this.key, data));
    return b64url(data) + "." + b64url(sig);
  }
}
