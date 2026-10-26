#!/usr/bin/env bash
# Проверка обхода DPI на компьютере, без Android SDK (секунды; запускается и в CI):
# 1) логика выбора стратегии (BypassSearch) на сценариях — app/src/test/.../BypassSearchTest.java;
# 2) каждая встроенная строка стратегии — так, как её запускает приложение (с маршрутом: список
#    доменов, сети Telegram, запасной путь) — принимается ядром ByeDPI (хостовый gcc, подмодуль).
# Нужны JDK (javac, java) и gcc.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/example/vpnapp"
BYEDPI="$ROOT/app/src/main/jni/byedpi"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Аннотация androidx.annotation.Nullable — заглушка (без Android SDK её нет)
mkdir -p "$TMP/stub/androidx/annotation" "$TMP/classes"
cat > "$TMP/stub/androidx/annotation/Nullable.java" <<'JAVA'
package androidx.annotation;
public @interface Nullable {}
JAVA
javac -nowarn -encoding UTF-8 -d "$TMP/classes" "$TMP/stub/androidx/annotation/Nullable.java" \
    "$SRC/BypassProfile.java" "$SRC/BypassSearch.java" "$SRC/ByeDpiStats.java" "$SRC/HelperRelay.java" \
    "$ROOT/app/src/test/java/com/example/vpnapp/BypassSearchTest.java"
java -Dstdout.encoding=UTF-8 -cp "$TMP/classes" com.example.vpnapp.BypassSearchTest
echo "Логика выбора стратегии: OK"

if [ ! -f "$BYEDPI/main.c" ]; then
    echo "Нет исходников byedpi (git submodule update --init) — проверка строк пропущена" >&2
    exit 0
fi
SRCS="$("$ROOT/scripts/byedpi-src.sh" "$TMP/byedpi")"
# shellcheck disable=SC2086
gcc -std=c99 -O1 -D_DEFAULT_SOURCE -I"$TMP/byedpi" -w -o "$TMP/ciadpi" $SRCS

# Все отладочные строки ByeDPI (LOG_S/LOG_L) ByeDpiStats должен узнавать и не пускать в журнал
grep -ohE 'LOG\(\(?[^"]*LOG_[SL][^"]*"[^"]*"' "$TMP/byedpi"/*.c | sed -E 's/^[^"]*"//; s/"$//' \
    > "$TMP/debug-formats.txt"
java -Dstdout.encoding=UTF-8 -cp "$TMP/classes" com.example.vpnapp.BypassSearchTest --debug-formats "$TMP/debug-formats.txt"

port=21800
n=0
while IFS=$'\t' read -r -a args; do
    port=$((port + 1))
    n=$((n + 1))
    "$TMP/ciadpi" -i 127.0.0.1 -p "$port" "${args[@]}" > "$TMP/out.txt" 2>&1 &
    pid=$!
    ok=0
    for _ in $(seq 1 30); do
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then ok=1; break; fi
        sleep 0.1
    done
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    if [ "$ok" != 1 ]; then
        echo "ByeDPI не принял строку: ${args[*]}" >&2
        cat "$TMP/out.txt" >&2
        exit 1
    fi
done < <(java -cp "$TMP/classes" com.example.vpnapp.BypassSearchTest --print-args)
echo "Строки стратегий ByeDPI ($n): OK"
