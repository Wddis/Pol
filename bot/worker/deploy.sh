#!/usr/bin/env bash
# Развёртывание бота ODA VPN на Cloudflare Workers (бесплатно, круглосуточно) — одна команда:
#   CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… BOT_TOKEN=… bash deploy.sh
# Что делает: база D1 (создаёт один раз) и её таблицы, воркер, секреты (токен бота, ключ подписи лицензий —
# создаётся один раз и никогда не перезаписывается, секрет webhook), webhook Telegram и меню команд.
# Повторный запуск безопасен: обновляет код и таблицы, ключи и данные не трогает.
# Необязательно: LICENSE_KEY=<32 байта base64url> — перенести ключ подписи с бота на Python
# (файл data/license_ed25519.key: base64 -w0 | tr '+/' '-_' | tr -d '=').
set -euo pipefail
cd "$(dirname "$0")"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

[ -n "${CLOUDFLARE_API_TOKEN:-}" ] || die "Нужен CLOUDFLARE_API_TOKEN (Cloudflare → My Profile → API Tokens, шаблон «Edit Cloudflare Workers» + D1 Edit)"
[ -n "${CLOUDFLARE_ACCOUNT_ID:-}" ] || die "Нужен CLOUDFLARE_ACCOUNT_ID (Cloudflare → Workers & Pages → справа «Account ID»)"
if [ -z "${BOT_TOKEN:-}" ]; then
  read -rsp "Токен бота от BotFather: " BOT_TOKEN; echo
fi
[[ "$BOT_TOKEN" =~ ^[0-9]+:[A-Za-z0-9_-]{30,}$ ]] || die "Токен бота не похож на токен BotFather"
export CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID WRANGLER_SEND_METRICS=false

[ -d node_modules ] || { say "Зависимости"; npm ci --no-audit --no-fund; }
W=(npx --no-install wrangler)

say "База D1"
DB_ID=$(grep -oP 'database_id = "\K[0-9a-f-]+' wrangler.toml)
if [ "$DB_ID" = "00000000-0000-0000-0000-000000000000" ]; then
  DB_ID=$("${W[@]}" d1 list --json 2>/dev/null | node -e '
    let s = require("fs").readFileSync(0, "utf8"); s = s.slice(s.indexOf("["));
    const d = JSON.parse(s).find((x) => x.name === "oda-vpn-bot"); console.log(d ? d.uuid : "")')
  if [ -z "$DB_ID" ]; then
    DB_ID=$("${W[@]}" d1 create oda-vpn-bot 2>&1 | grep -oP '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1)
    [ -n "$DB_ID" ] || die "Не удалось создать базу D1"
    echo "создана: $DB_ID"
  else
    echo "уже есть: $DB_ID"
  fi
  sed -i "s/00000000-0000-0000-0000-000000000000/$DB_ID/" wrangler.toml
else
  echo "из wrangler.toml: $DB_ID"
fi
"${W[@]}" d1 migrations apply DB --remote

say "Воркер"
OUT=$("${W[@]}" deploy 2>&1) || { echo "$OUT"; die "Не удалось развернуть (если просит поддомен workers.dev — выберите его в Cloudflare → Workers & Pages)"; }
echo "$OUT" | grep -vE '^\s*$' | tail -6
URL=$(echo "$OUT" | grep -oP 'https://[a-z0-9.-]+\.workers\.dev' | head -1)
[ -n "$URL" ] || die "Не нашёл адрес воркера в выводе wrangler deploy"

say "Секреты"
EXISTING=$("${W[@]}" secret list 2>/dev/null || true)
SECRETS=$(mktemp); chmod 600 "$SECRETS"; trap 'rm -f "$SECRETS"' EXIT
WEBHOOK_SECRET=$(node -e 'console.log(require("crypto").randomBytes(32).toString("base64url"))')
if echo "$EXISTING" | grep -q '"LICENSE_KEY"' && [ -z "${LICENSE_KEY:-}" ]; then
  echo "ключ подписи лицензий уже есть — не трогаю"
  LICENSE_KEY=""
elif [ -z "${LICENSE_KEY:-}" ]; then
  LICENSE_KEY=$(node -e 'console.log(require("crypto").randomBytes(32).toString("base64url"))')
  echo "ключ подписи лицензий создан (хранится только в Cloudflare)"
else
  echo "ключ подписи лицензий — из LICENSE_KEY"
fi
BOT_TOKEN="$BOT_TOKEN" WEBHOOK_SECRET="$WEBHOOK_SECRET" LICENSE_KEY="$LICENSE_KEY" node -e '
  const s = { BOT_TOKEN: process.env.BOT_TOKEN, WEBHOOK_SECRET: process.env.WEBHOOK_SECRET };
  if (process.env.LICENSE_KEY) s.LICENSE_KEY = process.env.LICENSE_KEY;
  require("fs").writeFileSync(process.argv[1], JSON.stringify(s));' "$SECRETS"
"${W[@]}" secret bulk "$SECRETS" >/dev/null
echo "BOT_TOKEN, WEBHOOK_SECRET${LICENSE_KEY:+, LICENSE_KEY} — в Cloudflare"

say "Telegram: webhook и меню"
for i in 1 2 3 4 5 6; do
  # Новые секреты расходятся по Cloudflare за несколько секунд
  RES=$(curl -fsS -X POST "$URL/setup" -H "x-setup-secret: $WEBHOOK_SECRET" 2>/dev/null) && break
  sleep $((i * 3))
done
[ -n "${RES:-}" ] || die "Воркер не ответил на /setup"
echo "$RES" | node -e '
  const r = JSON.parse(require("fs").readFileSync(0, "utf8"));
  if (!r.webhook.ok) { console.error("setWebhook:", r.webhook.description); process.exit(1); }
  console.log("webhook: ok, команды:", r.commands.ok ? "ok" : r.commands.description, "описание:", r.profile.ok ? "ok" : "ошибка");
  console.log("бот: @" + (r.me.username || "?"));
  console.log("\nОткрытый ключ лицензий (вшить в приложение): " + r.pubkey);'

say "Готово"
echo "Сервер ключей для приложения: $URL/v1/activate, $URL/v1/check (проверка: $URL/health)"
echo "Журнал воркера: npx wrangler tail"
