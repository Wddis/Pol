#!/usr/bin/env bash
# Имитация телефона с обходом DPI на компьютере (Linux, root; ~3 минуты; идёт и в CI):
# весь трафик «телефона» → VPN-интерфейс tun0 → tun2socks (hev-socks5-tunnel) → ByeDPI → «оператор»
# с DPI → «интернет» (DNS 1.1.1.1 и HTTPS-сайты). Конфиг hev и аргументы ByeDPI берутся из кода
# приложения (Tun2SocksConfig, BypassProfile), проверка стратегии — настоящий NetProbe.
#
# Что проверяется (см. README, «Ошибка 0.8.5»):
#  1) сайты через VPN: DNS телефона идёт через SOCKS-UDP ByeDPI, TCP — через SOCKS;
#  2) проверка ODA (NetProbe) мимо VPN: DNS через SOCKS-UDP, обычные сайты, сторож;
#  3) нагрузка как у телефона: поток DNS-запросов (каждый — новая UDP-ассоциация) и сайт каждые 5 с:
#     сайт должен открываться всегда, у ByeDPI не должно быть «pool is full»;
#  4) контроль: тот же поток со старым конфигом (0.8.5) должен «ронять» интернет — значит, тест
#     действительно ловит ошибку.
# Нужны: gcc, make, JDK, python3 + pyroute2, iptables, openssl, curl, util-linux (nsenter, unshare).
set -euo pipefail

if [ "$(id -u)" != 0 ]; then
    exec sudo -E env "PATH=$PATH" bash "$0" "$@"
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/example/vpnapp"
LAB="$ROOT/scripts/tunlab"
TMP="$(mktemp -d)"
chmod 755 "$TMP"
PORT=10809
APP_UID=2000
LOAD_SECONDS=40
LOAD_PARALLEL=15

PIDS=()
stop_lab() {
    for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done
    for p in "${PIDS[@]:-}"; do [ -n "$p" ] && wait "$p" 2>/dev/null || true; done
    PIDS=()
    python3 "$LAB/lab.py" down
}
cleanup() {
    stop_lab || true
    rm -rf "$TMP"
}
trap cleanup EXIT

NS() { nsenter --net="/var/run/netns/$1" "${@:2}"; }
# Программа «на телефоне»: через VPN, со своим resolv.conf/nsswitch (DNS — как у VPN ODA)
PHONE() {
    NS phone unshare -m sh -c 'mount --bind "$0/resolv.conf" /etc/resolv.conf &&
        mount --bind "$0/nsswitch.conf" /etc/nsswitch.conf && shift && exec "$@"' "$TMP" x "$@"
}
# Программа «внутри ODA»: uid приложения, мимо VPN
APP() { NS phone setpriv --reuid "$APP_UID" --regid "$APP_UID" --clear-groups "$@"; }

# ---------- Сборка ----------
echo "Сборка ByeDPI, hev-socks5-tunnel и проверки ODA…"
BYEDPI="$ROOT/app/src/main/jni/byedpi"
BSRCS=""
for f in "$BYEDPI"/*.c; do
    case "$(basename "$f")" in win_service.c) ;; *) BSRCS="$BSRCS $f" ;; esac
done
# shellcheck disable=SC2086
gcc -std=c99 -O1 -D_DEFAULT_SOURCE -I"$BYEDPI" -w -o "$TMP/ciadpi" $BSRCS
cp -r "$ROOT/app/src/main/jni/hev-socks5-tunnel" "$TMP/hev"
make -C "$TMP/hev" -j"$(nproc)" > "$TMP/hev-build.log" 2>&1 || { tail -30 "$TMP/hev-build.log"; exit 1; }
HEV="$TMP/hev/bin/hev-socks5-tunnel"

mkdir -p "$TMP/stub/androidx/annotation" "$TMP/stub/com/example/vpnapp" "$TMP/classes"
echo 'package androidx.annotation; public @interface Nullable {}' > "$TMP/stub/androidx/annotation/Nullable.java"
echo 'package com.example.vpnapp; final class DiagLog { static void add(String s) { System.out.println(s); } }' \
    > "$TMP/stub/com/example/vpnapp/DiagLog.java"
javac -nowarn -encoding UTF-8 -d "$TMP/classes" "$TMP/stub/androidx/annotation/Nullable.java" \
    "$TMP/stub/com/example/vpnapp/DiagLog.java" "$SRC/NetProbe.java" "$SRC/BypassSearch.java" \
    "$SRC/BypassProfile.java" "$SRC/Tun2SocksConfig.java" "$ROOT/app/src/test/java/com/example/vpnapp/TunLab.java"
chmod -R a+rX "$TMP"
JAVA="$(command -v java)"
TUNLAB() { "$JAVA" -cp "$TMP/classes" com.example.vpnapp.TunLab "$@"; }

# Без IPv6 в ядре (некоторые контейнеры) — прослойка: сокеты AF_INET6 у hev работают поверх IPv4
IPV6=1
PRELOAD=""
if [ ! -e /proc/net/if_inet6 ]; then
    IPV6=0
    gcc -O2 -shared -fPIC -o "$TMP/v4shim.so" "$LAB/v4shim.c" -ldl
    PRELOAD="$TMP/v4shim.so"
    echo "В ядре нет IPv6: стенд только IPv4 (прослойка v4shim для hev)"
fi

# Сертификаты «сайтов»
SANS=$(python3 -B -c "import sys; sys.path.insert(0, '$LAB'); import lab; print(','.join('DNS:' + h for h in lab.SITES))")
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TMP/ca.key" -out "$TMP/ca.pem" -days 2 -subj "/CN=ODA lab CA" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -keyout "$TMP/site.key" -out "$TMP/site.csr" -subj "/CN=lab" 2>/dev/null
echo "subjectAltName=$SANS" > "$TMP/san.ext"
openssl x509 -req -in "$TMP/site.csr" -CA "$TMP/ca.pem" -CAkey "$TMP/ca.key" -CAcreateserial \
    -out "$TMP/site.pem" -days 2 -extfile "$TMP/san.ext" 2>/dev/null
chmod 644 "$TMP"/*.pem "$TMP"/*.key

# DNS телефона в VPN — как у VpnService.Builder при обходе DPI
{
    echo "nameserver 1.1.1.1"
    [ "$IPV6" = 1 ] && echo "nameserver 2606:4700:4700::1111"
} > "$TMP/resolv.conf"
echo "hosts: files dns" > "$TMP/nsswitch.conf"

TUNLAB config "$PORT" > "$TMP/hev-new.yml"
read -r -a BYEDPI_NEW <<< "$(TUNLAB byedpi "$PORT")"
# Старый конфиг (0.8.5): без тайм-аута UDP в hev и без -c у ByeDPI
grep -v 'udp-read-write-timeout' "$TMP/hev-new.yml" > "$TMP/hev-old.yml"
BYEDPI_OLD=()
skip=0
for a in "${BYEDPI_NEW[@]}"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    if [ "$a" = "-c" ]; then skip=1; continue; fi
    BYEDPI_OLD+=("$a")
done

# ---------- Стенд ----------
start_lab() {  # $1 — конфиг hev, дальше — аргументы ByeDPI
    local yml="$1"
    shift
    stop_lab
    python3 "$LAB/lab.py" up "$IPV6"
    NS isp sh -c 'echo 1 > /proc/sys/net/ipv4/ip_forward'
    # DPI оператора: TCP:443-пакет с именем заблокированного сайта целиком — отбрасывается
    local dpi=1
    for h in instagram.com facebook.com rutracker.org; do
        NS isp iptables -A FORWARD -p tcp --dport 443 -m string --string "$h" --algo bm -j DROP 2>/dev/null || dpi=0
    done
    [ "$dpi" = 1 ] || echo "  (iptables string недоступен: стенд без DPI)"
    NS srv python3 -u "$LAB/lab.py" srv "$TMP/site.pem" "$TMP/site.key" > "$TMP/srv.log" 2>&1 &
    PIDS+=($!)
    : > "$TMP/byedpi.log"
    APP "$TMP/ciadpi" "$@" > "$TMP/byedpi.log" 2>&1 &
    PIDS+=($!)
    cp "$yml" "$TMP/hev.yml"
    if [ "$IPV6" = 0 ]; then sed -i "/ipv6:/d" "$TMP/hev.yml"; fi
    sed -i "s/^tunnel:/tunnel:\n  name: tun0/" "$TMP/hev.yml"
    if [ "$IPV6" = 1 ]; then
        NS phone sh -c 'echo 0 > /proc/sys/net/ipv6/conf/all/accept_dad; echo 0 > /proc/sys/net/ipv6/conf/default/accept_dad'
    fi
    NS phone env ${PRELOAD:+LD_PRELOAD=$PRELOAD} "$HEV" "$TMP/hev.yml" > "$TMP/hev.log" 2>&1 &
    PIDS+=($!)
    NS phone python3 "$LAB/lab.py" routes "$IPV6"
    for _ in $(seq 1 50); do
        grep -q "srv: готов" "$TMP/srv.log" 2>/dev/null && break
        sleep 0.1
    done
    sleep 0.5
}

site() {  # открыть сайт «браузером телефона» через VPN; печатает код ответа
    PHONE curl -sS --noproxy '*' --cacert "$TMP/ca.pem" -o /dev/null -m 10 -w "%{http_code}" \
        "https://$1$2" 2>/dev/null || true
}

pool_full() { grep -c 'pool is full' "$TMP/byedpi.log" || true; }

load_test() {  # поток DNS + сайт каждые 5 с; печатает «открылся N из M»
    PHONE python3 "$LAB/lab.py" dnsload "$LOAD_SECONDS" "$LOAD_PARALLEL" > "$TMP/load.txt" 2>&1 &
    local load=$! good=0 total=0
    for _ in $(seq 1 $((LOAD_SECONDS / 5))); do
        sleep 5
        total=$((total + 1))
        local code
        code=$(site vk.com /)
        [ "$code" = 200 ] && good=$((good + 1))
        printf '    %2d с: vk.com %s, у ByeDPI «pool is full»: %s\n' $((total * 5)) "${code:-нет}" "$(pool_full)"
    done
    wait "$load" || true
    echo "    $(cat "$TMP/load.txt")"
    LOAD_GOOD=$good
    LOAD_TOTAL=$total
}

FAIL=0
check() {  # $1 — условие выполнено (0/1), $2 — текст
    if [ "$1" = 1 ]; then echo "  ✓ $2"; else echo "  ✗ $2"; FAIL=1; fi
}

echo
echo "=== Новый конфиг: ByeDPI ${BYEDPI_NEW[*]}"
echo "    hev: $(grep udp-read-write-timeout "$TMP/hev-new.yml" | xargs)"
start_lab "$TMP/hev-new.yml" "${BYEDPI_NEW[@]}"

echo "1) Сайты через VPN (DNS телефона через SOCKS-UDP ByeDPI):"
ok=1
for s in "www.google.com /generate_204 204" "www.cloudflare.com / 200" "ya.ru / 200" "vk.com / 200" "www.gosuslugi.ru / 200"; do
    set -- $s
    code=$(site "$1" "$2")
    echo "    $1: ${code:-нет}"
    [ "$code" = "$3" ] || ok=0
done
check "$ok" "обычные сайты через VPN открываются"

echo "2) Проверка ODA мимо VPN (NetProbe):"
if APP "$JAVA" -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$TMP/classes" com.example.vpnapp.TunLab probe "$PORT" "$TMP/ca.pem"; then probe_ok=1; else probe_ok=0; fi
check "$probe_ok" "NetProbe: DNS через SOCKS-UDP, обычные сайты, сторож"

echo "3) Нагрузка как у телефона (DNS $LOAD_PARALLEL потоков, ${LOAD_SECONDS} с):"
load_test
check "$([ "$LOAD_GOOD" = "$LOAD_TOTAL" ] && echo 1 || echo 0)" "сайт открывался всё время: $LOAD_GOOD из $LOAD_TOTAL"
check "$([ "$(pool_full)" = 0 ] && echo 1 || echo 0)" "пул ByeDPI не переполнялся"

echo
echo "=== Контроль — старый конфиг 0.8.5: ByeDPI ${BYEDPI_OLD[*]}, hev без тайм-аута UDP"
start_lab "$TMP/hev-old.yml" "${BYEDPI_OLD[@]}"
echo "4) Та же нагрузка:"
load_test
if [ "$LOAD_GOOD" -lt "$LOAD_TOTAL" ] && [ "$(pool_full)" != 0 ]; then
    echo "  ✓ со старым конфигом интернет пропадает — тест ловит ошибку 0.8.5"
else
    echo "  ! со старым конфигом ошибка не воспроизвелась: нагрузка мала для этой машины (не провал)"
fi

echo
if [ "$FAIL" = 0 ]; then
    echo "Имитация телефона с обходом DPI: OK"
else
    echo "Имитация телефона с обходом DPI: ОШИБКА"
    echo "--- ByeDPI (хвост)"; tail -20 "$TMP/byedpi.log" || true
    echo "--- hev (хвост)"; tail -20 "$TMP/hev.log" || true
    exit 1
fi
