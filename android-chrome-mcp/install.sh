#!/data/data/com.termux/files/usr/bin/env bash
# Установка в Termux одной командой:
#   curl -fsSL https://cdn.jsdelivr.net/gh/wddis/pol@1/android-chrome-mcp/install.sh | bash
set -e
VER="${ACM_VERSION:-1}"                 # последняя версия 1.x (теги v1.*)
BRANCH="${ACM_BRANCH:-ccr-53c32ce8-hewh3l}"
P="android-chrome-mcp"
MIRRORS="https://cdn.jsdelivr.net/gh/wddis/pol@$VER/$P
https://fastly.jsdelivr.net/gh/wddis/pol@$VER/$P
https://raw.githubusercontent.com/wddis/pol/$BRANCH/$P
https://github.com/wddis/pol/raw/$BRANCH/$P"
APP="$HOME/.android-chrome-mcp/app"

[ -d "${PREFIX:-/nonexistent}" ] || { echo "Запустите это в приложении Termux."; exit 1; }

echo "▶ 1/3 Обновляю Termux и ставлю нужные пакеты (пару минут)..."
export DEBIAN_FRONTEND=noninteractive
pkg update -y -o Dpkg::Options::=--force-confnew >/dev/null 2>&1 || true
pkg upgrade -y -o Dpkg::Options::=--force-confnew >/dev/null 2>&1 || true
pkg install -y nodejs android-tools cloudflared curl >/dev/null 2>&1 || pkg install -y nodejs android-tools cloudflared curl
[ "$(node -p 'process.versions.node.split(".")[0]')" -ge 22 ] || { echo "Нужен Node.js 22+. Выполните: pkg upgrade и повторите."; exit 1; }

echo "▶ 2/3 Скачиваю программу..."
mkdir -p "$APP"
fetch() { # file
  local m
  while read -r m; do
    if curl -fsSL --retry 2 -m 120 "$m/$1" -o "$APP/$1.tmp" 2>/dev/null && [ -s "$APP/$1.tmp" ]; then
      mv "$APP/$1.tmp" "$APP/$1"; return 0
    fi
  done <<<"$MIRRORS"
  echo "Не удалось скачать $1. Включите/выключите VPN и повторите."; exit 1
}
fetch server.mjs
fetch chrome-mcp
grep -q "android-chrome MCP" "$APP/server.mjs" || { echo "Файл повреждён, повторите установку."; exit 1; }
chmod +x "$APP/chrome-mcp" "$APP/server.mjs"
ln -sf "$APP/chrome-mcp" "$PREFIX/bin/chrome-mcp"

echo "▶ 3/3 Готово! Запускаю мастер настройки..."
echo
exec chrome-mcp </dev/tty
