#!/data/data/com.termux/files/usr/bin/env bash
# Installer for Termux:
#   curl -fsSL https://raw.githubusercontent.com/wddis/pol/main/android-chrome-mcp/install.sh | bash
set -e
REPO="wddis/pol"
BRANCHES="${ACM_BRANCH:-main ccr-53c32ce8-hewh3l}"
DEST="$HOME/android-chrome-mcp"

[ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ] || { echo "Запустите это в Termux."; exit 1; }

echo "==> Устанавливаю пакеты (nodejs, adb, cloudflared)..."
pkg update -y >/dev/null 2>&1 || true
pkg install -y nodejs android-tools cloudflared curl tar >/dev/null

major="$(node -p 'process.versions.node.split(".")[0]')"
[ "$major" -ge 22 ] || { echo "Нужен Node.js 22+, установлен $(node -v). Выполните: pkg upgrade"; exit 1; }

echo "==> Скачиваю android-chrome-mcp..."
tmp="$(mktemp -d)"
got=""
for b in $BRANCHES; do
  if curl -fsSL "https://codeload.github.com/$REPO/tar.gz/refs/heads/$b" | tar -xz -C "$tmp" 2>/dev/null; then
    src="$(find "$tmp" -maxdepth 2 -type d -name android-chrome-mcp | head -n1)"
    [ -n "$src" ] && { got="$src"; break; }
    rm -rf "${tmp:?}"/*
  fi
done
[ -n "$got" ] || { echo "Не удалось скачать проект."; exit 1; }
rm -rf "$DEST"
mv "$got" "$DEST"
rm -rf "$tmp"

echo "==> Устанавливаю зависимости..."
(cd "$DEST" && npm install --omit=dev --no-audit --no-fund --loglevel=error)
chmod +x "$DEST/bin/chrome-mcp" "$DEST/src/server.js"
ln -sf "$DEST/bin/chrome-mcp" "$PREFIX/bin/chrome-mcp"

echo ""
echo "Готово. Дальше:"
echo "  chrome-mcp setup   — один раз: сопряжение телефона с самим собой"
echo "  chrome-mcp start   — запуск, выдаст адрес для Claude"
