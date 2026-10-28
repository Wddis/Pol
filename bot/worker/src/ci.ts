// Связь со сборкой в GitHub Actions без секретов в GitHub: сборка предъявляет одноразовый токен OIDC, который
// GitHub выдаёт только workflow этого репозитория (подпись RS256, ключи — token.actions.githubusercontent.com).
// По нему воркер отдаёт постоянный ключ подписи APK (секрет Cloudflare: ODA ставится поверх старой версии)
// и принимает сведения о выпуске (версия, ссылка на APK в GitHub Releases) для страницы /download.

export interface CiEnv {
  /** Ключ подписи APK (PKCS12, base64) — двумя секретами: лимит Cloudflare 5,1 КБ на секрет. */
  ODA_KEYSTORE_B64?: string;
  ODA_KEYSTORE_B64_2?: string;
  ODA_KEYSTORE_PASS?: string;
  /** Только для тестов: свой издатель и адрес ключей. */
  OIDC_ISSUER?: string;
  OIDC_JWKS_URL?: string;
}

export const OIDC_AUDIENCE = "oda-ci";
const ISSUER = "https://token.actions.githubusercontent.com";
/** Репозиторий ODA по номеру (не меняется при переименовании) и по имени. */
export const REPO_ID = "1225983165";
export const REPO = "Wddis/Pol";

type Jwk = JsonWebKey & { kid?: string };
let jwksCache: { url: string; at: number; keys: Jwk[] } | null = null;

const b64urlBytes = (s: string) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4)), (c) => c.charCodeAt(0));
const b64urlJson = (s: string) => JSON.parse(new TextDecoder().decode(b64urlBytes(s)));

async function jwks(url: string, force = false): Promise<Jwk[]> {
  if (!force && jwksCache && jwksCache.url === url && Date.now() - jwksCache.at < 3600_000) return jwksCache.keys;
  const r = await fetch(url);
  if (!r.ok) throw new Error(`jwks ${r.status}`);
  const keys = ((await r.json()) as { keys: Jwk[] }).keys;
  jwksCache = { url, at: Date.now(), keys };
  return keys;
}

/** Проверенные утверждения токена или причина отказа. */
export async function verifyGithubOidc(env: CiEnv, auth: string | null): Promise<{ ok: true; claims: Record<string, unknown> } | { ok: false; why: string }> {
  const token = /^Bearer\s+(.+)$/i.exec(auth ?? "")?.[1];
  if (!token) return { ok: false, why: "нет токена" };
  const parts = token.split(".");
  if (parts.length !== 3) return { ok: false, why: "не JWT" };
  let header: { alg?: string; kid?: string };
  let claims: Record<string, unknown>;
  try {
    header = b64urlJson(parts[0]);
    claims = b64urlJson(parts[1]);
  } catch {
    return { ok: false, why: "не JWT" };
  }
  if (header.alg !== "RS256") return { ok: false, why: "алгоритм" };
  const issuer = env.OIDC_ISSUER || ISSUER;
  const url = env.OIDC_JWKS_URL || `${issuer}/.well-known/jwks`;
  let keys = await jwks(url);
  let jwk = keys.find((k) => k.kid === header.kid);
  if (!jwk) {
    keys = await jwks(url, true); // ключи GitHub сменились
    jwk = keys.find((k) => k.kid === header.kid);
  }
  if (!jwk) return { ok: false, why: "неизвестный ключ" };
  const key = await crypto.subtle.importKey("jwk", { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: "RS256", ext: true }, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const good = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlBytes(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  if (!good) return { ok: false, why: "подпись" };
  const now = Math.floor(Date.now() / 1000);
  if (claims.iss !== issuer) return { ok: false, why: "издатель" };
  if (claims.aud !== OIDC_AUDIENCE) return { ok: false, why: "аудитория" };
  if (typeof claims.exp !== "number" || claims.exp < now - 30) return { ok: false, why: "истёк" };
  if (typeof claims.nbf === "number" && claims.nbf > now + 30) return { ok: false, why: "ещё не действует" };
  if (claims.repository_id !== REPO_ID || claims.repository !== REPO) return { ok: false, why: "чужой репозиторий" };
  if (claims.event_name === "pull_request" || claims.event_name === "pull_request_target") return { ok: false, why: "pull request" };
  if (!String(claims.workflow_ref ?? "").startsWith(`${REPO}/.github/workflows/build.yml@`)) return { ok: false, why: "чужой workflow" };
  return { ok: true, claims };
}

export interface ApkRelease {
  version: string;
  code: number;
  url: string;
  size: number;
  sha256: string;
  at: number;
}

/** Ссылка на APK — только из выпусков этого репозитория (воркер её проксирует). */
export function validRelease(r: Partial<ApkRelease>): r is ApkRelease {
  return (
    typeof r.version === "string" && /^\d+\.\d+\.\d+$/.test(r.version) &&
    typeof r.code === "number" && r.code > 0 &&
    typeof r.url === "string" && r.url.startsWith(`https://github.com/${REPO}/releases/download/`) && r.url.endsWith(".apk") &&
    typeof r.size === "number" && r.size > 1_000_000 && r.size < 500_000_000 &&
    typeof r.sha256 === "string" && /^[0-9a-f]{64}$/.test(r.sha256)
  );
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const mb = (n: number) => `${(n / 1048576).toFixed(0)} МБ`;

/** Страница загрузки: простая, тёмная как приложение, без внешних файлов. */
export function downloadPage(r: ApkRelease | null, bot: string): string {
  const btn = r
    ? `<a class="btn" href="/download/ODA-${esc(r.version)}.apk" download>Скачать ODA ${esc(r.version)}</a>
       <p class="meta">APK · ${mb(r.size)} · Android 7 и новее · для всех телефонов (32 и 64 бит)</p>`
    : `<p class="soon">Файл скоро появится здесь. Пока — напишите в бота <a href="https://t.me/${esc(bot)}">@${esc(bot)}</a>.</p>`;
  return `<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Скачать ODA</title><meta name="robots" content="noindex">
<style>
:root{--bg:#0b0f10;--card:#13191b;--line:#202a2d;--text:#eef3f2;--muted:#94a3a0;--accent:#35d391;--warn:#f2c14e}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,Roboto,sans-serif}
main{max-width:560px;margin:0 auto;padding:28px 16px 40px}
h1{font-weight:300;letter-spacing:.35em;font-size:34px;margin:0}
.tag{color:var(--muted);font-size:11px;letter-spacing:.2em;text-transform:uppercase;margin:4px 0 24px}
.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:18px;margin:14px 0}
.btn{display:block;text-align:center;background:var(--accent);color:#06281a;font-weight:700;text-decoration:none;border-radius:28px;padding:16px;font-size:17px;letter-spacing:.04em}
.meta{color:var(--muted);font-size:13px;text-align:center;margin:10px 0 0}
h2{font-size:16px;margin:0 0 8px}ol{margin:0;padding-left:22px}li{margin:6px 0}
.warn{border-color:#4a3d16}.warn h2{color:var(--warn)}
a{color:var(--accent)}.soon{text-align:center;color:var(--muted)}
.small{color:var(--muted);font-size:12px;word-break:break-all}
</style></head><body><main>
<h1>ODA</h1><div class="tag">Безопасный доступ к интернету</div>
<div class="card">${btn}</div>
<div class="card"><h2>Как установить</h2><ol>
<li>Нажмите «Скачать» и дождитесь загрузки.</li>
<li>Откройте файл — из уведомления или папки «Загрузки».</li>
<li>Если телефон спросит — разрешите установку из этого источника (браузер или «Файлы») и нажмите «Установить».</li>
<li>Откройте ODA и введите ключ из бота <a href="https://t.me/${esc(bot)}">@${esc(bot)}</a> — там же пробный день бесплатно.</li>
</ol></div>
<div class="card"><h2>Обновление</h2>
<p style="margin:0">Новая версия ставится поверх старой — ключ и настройки сохраняются. Если у вас версия до 0.12.3 и телефон пишет «Приложение не установлено», один раз удалите старую ODA и установите заново (сменилась подпись приложения).</p></div>
<div class="card warn"><h2>Google Play Защита может предупредить</h2>
<p style="margin:0">При установке может появиться «Небезопасное приложение» или предложение проверить его. Так бывает с приложениями не из Google Play: Защита не знает разработчика, а VPN-приложения просят управлять подключением к сети — поэтому она осторожничает. Нажмите «Подробнее» → «Всё равно установить». ODA не собирает ваши данные и не показывает рекламу; исходный код открыт: <a href="https://github.com/${REPO}">github.com/${REPO}</a>.</p></div>
${r ? `<p class="small">SHA-256: ${esc(r.sha256)}</p>` : ""}
</main></body></html>`;
}
