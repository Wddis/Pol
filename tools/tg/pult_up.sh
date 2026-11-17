#!/bin/bash
# Поднять пульт Telegram после перезапуска контейнера одной командой (17.11: контейнер перезапускался дважды,
# процессы гибнут, файлы живут). Идемпотентно: если пульт жив и оба аккаунта вошли — ничего не делает.
# usage: tools/tg/pult_up.sh <scratchpad>      (там: rb_token, tg_session.json; файлы входа — только в scratchpad)
set -u
S="${1:?scratchpad}"; R="$(cd "$(dirname "$0")/../.." && pwd)"; T="$R/tools/tg"
ok() { node "$T/tg_web.js" me 2>/dev/null | grep -q "аккаунт 1" && node "$T/tg_web.js" me --account 2 2>/dev/null | grep -q "аккаунт 2"; }
if curl -s -o /dev/null "http://127.0.0.1:9222/json/version" && ok; then echo "пульт уже работает, оба аккаунта вошли"; exit 0; fi
pkill -f remote_browser.js 2>/dev/null; pkill -f remote-debugging-port=9222 2>/dev/null; rm -f /tmp/tg_pult.lock; sleep 2
[ -f "$S/empty_cookies.txt" ] || : > "$S/empty_cookies.txt"
( cd "$S" && RB_TOKEN="$(cat rb_token)" setsid nohup node "$R/tools/yt/remote_browser.js" empty_cookies.txt 8765 9222 \
  "https://web.telegram.org/k/" > rb.log 2>&1 < /dev/null & )
for i in $(seq 1 30); do curl -s -o /dev/null "http://127.0.0.1:9222/json/version" && break; sleep 1; done
node "$T/tg_web.js" session-restore "$S/tg_session.json" || exit 1
for i in 1 2 3; do ok && { echo "✓ пульт поднят, оба аккаунта вошли"; node "$T/tg_doctor.js" --token-file "$S/rb_token" | grep -E "^✗" ; exit 0; }; sleep 10; done
echo "✗ аккаунты не вошли — нужен новый QR (docs/telegram.md)"; exit 1
