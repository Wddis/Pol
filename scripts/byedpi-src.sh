#!/usr/bin/env bash
# Исходники ByeDPI для сборки: копия подмодуля app/src/main/jni/byedpi с правками ODA
# (scripts/byedpi.patch) в папке $1. Подмодуль не меняется. Правки:
#  - опция группы --udp-drop (-G): UDP-датаграммы группы отбрасываются — так приложения Meta
#    уходят с QUIC на TCP, где их открывает стратегия обхода DPI;
#  - фильтр сетей (-j/--ipset) узнаёт IPv4 в виде ::ffff:a.b.c.d: с IPv6-сокетами (Android)
#    ByeDPI так записывает адреса UDP и повторных соединений, и сети IPv4 не совпадали;
#  - ассоциация UDP к порту 53 закрывается, как только все её DNS-запросы получили ответ:
#    резолвер телефона на каждый запрос открывает новую, и они держали пул до тайм-аута tun2socks.
# Использование: scripts/byedpi-src.sh <папка>  → печатает список .c-файлов для компилятора.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/jni/byedpi"
OUT="$1"
if [ ! -f "$SRC/main.c" ]; then
    echo "Нет исходников byedpi в $SRC — git submodule update --init" >&2
    exit 1
fi
mkdir -p "$OUT"
cp "$SRC"/*.c "$SRC"/*.h "$OUT"/
patch -s -p1 -d "$OUT" < "$ROOT/scripts/byedpi.patch"
for f in "$OUT"/*.c; do
    case "$(basename "$f")" in win_service.c) ;; *) printf '%s ' "$f" ;; esac
done
echo
