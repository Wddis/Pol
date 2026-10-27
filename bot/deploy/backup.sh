#!/usr/bin/env bash
# Резервная копия базы ключей (без остановки бота) и ключа подписи; хранится 14 дней в data/backups.
# Копируйте data/backups и license_ed25519.key куда-нибудь вне сервера: без ключа подписи
# выданные лицензии не проверить — понадобится новое приложение.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p data/backups
stamp="$(date +%F)"
docker compose exec -T bot python -c "
import sqlite3
src = sqlite3.connect('/app/data/oda.db')
dst = sqlite3.connect('/app/data/backups/oda-$stamp.db')
src.backup(dst)
dst.close()
"
[ -f data/backups/license_ed25519.key ] || cp data/license_ed25519.key data/backups/
find data/backups -name 'oda-*.db' -mtime +14 -delete
echo "$(date '+%F %T') бэкап: data/backups/oda-$stamp.db"
