#!/usr/bin/env bash
# Обновить бота до последней версии ветки и перезапустить (данные и .env остаются).
set -euo pipefail
cd "$(dirname "$0")"
git pull -q --ff-only
PROFILE=()
grep -q '^ODA_DOMAIN=.\+' .env && PROFILE=(--profile https)
docker compose "${PROFILE[@]}" up -d --build
docker image prune -f >/dev/null
echo "Обновлено: $(git log --oneline -1)"
