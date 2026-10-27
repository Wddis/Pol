#!/usr/bin/env bash
# Установка бота ODA VPN и сервера ключей на чистый VPS (Ubuntu 22.04/24.04, Debian 12), root.
#   sudo bash -c "$(curl -fsSL https://raw.githubusercontent.com/Wddis/Pol/<ветка>/bot/deploy/install.sh)"
# Повторный запуск безопасен: обновит код и перезапустит, .env и данные не трогает.
set -euo pipefail

REPO="${ODA_REPO:-https://github.com/Wddis/Pol.git}"
BRANCH="${ODA_BRANCH:-ccr-016bf782-9cd9fj}"
DIR="${ODA_DIR:-/opt/oda}"

[ "$(id -u)" = 0 ] || { echo "Запустите от root (sudo)"; exit 1; }

echo "== 1/6 Пакеты"
apt-get update -qq
apt-get install -y -qq git curl ca-certificates >/dev/null
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
    echo "   Docker…"
    curl -fsSL https://get.docker.com | sh >/dev/null
fi
systemctl enable --now docker >/dev/null

echo "== 2/6 Код ($BRANCH) → $DIR"
if [ -d "$DIR/.git" ]; then
    git -C "$DIR" fetch -q origin "$BRANCH" && git -C "$DIR" checkout -q "$BRANCH" && git -C "$DIR" pull -q --ff-only
else
    git clone -q --depth 1 --branch "$BRANCH" "$REPO" "$DIR"
fi
cd "$DIR/bot/deploy"

echo "== 3/6 Настройки (.env)"
if [ ! -f .env ]; then
    cp .env.example .env
    chmod 600 .env
    read -r -s -p "   Токен бота от BotFather: " token </dev/tty; echo
    read -r -p "   Ваш Telegram ID (узнать: @userinfobot), Enter — пропустить: " admin </dev/tty
    read -r -p "   Домен для сервера ключей (например keys.example.com), Enter — без домена: " domain </dev/tty
    read -r -p "   Контакт поддержки (например @username), Enter — пропустить: " support </dev/tty
    sed -i "s|^BOT_TOKEN=.*|BOT_TOKEN=$token|; s|^ODA_ADMIN_IDS=.*|ODA_ADMIN_IDS=$admin|" .env
    sed -i "s|^ODA_DOMAIN=.*|ODA_DOMAIN=$domain|; s|^ODA_SUPPORT=.*|ODA_SUPPORT=$support|" .env
    if [ -z "$domain" ]; then sed -i "s|^ODA_BIND=.*|ODA_BIND=0.0.0.0|" .env; fi
else
    echo "   .env уже есть — оставляю"
fi
mkdir -p data
DOMAIN="$(sed -n 's/^ODA_DOMAIN=//p' .env)"
PROFILE=()
[ -n "$DOMAIN" ] && PROFILE=(--profile https)

echo "== 4/6 Брандмауэр"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow OpenSSH >/dev/null
    if [ -n "$DOMAIN" ]; then ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; else ufw allow 8080/tcp >/dev/null; fi
    echo "   ufw: открыты порты для сервера ключей"
else
    echo "   ufw не включён — пропускаю"
fi

echo "== 5/6 Запуск"
docker compose "${PROFILE[@]}" up -d --build
for _ in $(seq 1 30); do
    curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1 && break
    sleep 2
done
curl -fsS http://127.0.0.1:8080/health >/dev/null || { echo "Сервер ключей не ответил: docker compose logs bot"; exit 1; }

echo "== 6/6 Резервные копии (каждый день в 04:17, 14 дней)"
cat > /etc/cron.d/oda-bot <<CRON
17 4 * * * root $DIR/bot/deploy/backup.sh >> /var/log/oda-backup.log 2>&1
CRON

echo
echo "Готово: бот работает круглосуточно и сам перезапускается (после сбоя и перезагрузки сервера)."
if [ -n "$DOMAIN" ]; then
    echo "Сервер ключей: https://$DOMAIN/v1/activate (сертификат Let's Encrypt появится за минуту, если домен указывает на этот сервер)"
else
    echo "Сервер ключей: http://$(curl -fsS -4 https://ifconfig.me 2>/dev/null || echo '<IP>'):8080/v1/activate (для приложения лучше домен с HTTPS)"
fi
docker compose logs bot 2>/dev/null | grep -m1 "Открытый ключ" || true
echo "Журнал: cd $DIR/bot/deploy && docker compose logs -f bot · обновить: ./update.sh · бэкап: ./backup.sh"
