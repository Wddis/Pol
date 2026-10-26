#!/usr/bin/env bash
# Имитация телефона с обходом DPI на компьютере (Linux, root; ~8 минут; идёт и в CI):
# весь трафик «телефона» → VPN-интерфейс tun0 → tun2socks (hev-socks5-tunnel) → ByeDPI → «оператор»
# с моделью ТСПУ (scripts/tunlab/tspu.py) → «интернет» (DNS 1.1.1.1, 8.8.8.8, 9.9.9.9, 77.88.8.8, HTTPS-сайты, «Telegram», «QUIC»).
# Стратегию выбирает настоящий перебор ODA (BypassSearch + NetProbe + процессы ByeDPI с маршрутом
# BypassProfile.route), конфиг hev — из Tun2SocksConfig, запасной путь — настоящий HelperRelay
# в «Psiphon» стенда (SOCKS, который ходит мимо оператора).
#
# Профили ТСПУ подобраны по журналам телефона (см. tspu.py):
#  nov2026 — журнал 0.8.8: как oct2026, но instagram по адресу не закрыт, а UDP-DNS к 1.1.1.1
#            и 8.8.8.8 режется (обход должен взять другой DNS, а не уступить медленному Psiphon);
#  oct2026 — журнал 0.8.7: OOB не пробивает, tlsrec пробивает; instagram (31.13.72.174), CDN
#            и Telegram закрыты по адресу; YouTube режется по имени для мягкой стратегии;
#  jun2026 — журнал 0.8.6: OOB-семейство пробивает; CDN и Telegram закрыты по адресу.
# Для каждого: 1) выбор стратегии как на телефоне; 2) «приложения» через VPN: обычные сайты,
# YouTube, Facebook/Instagram (как приложение: i.instagram.com, graph.facebook.com), CDN картинок,
# Telegram, QUIC к Meta (должен отбрасываться) и к Google (должен идти); NetProbe и сторож; сводка
# ByeDPI. Для oct2026 ещё: поток DNS (без «pool is full»), запасной путь закрыт (Psiphon не готов) —
# остальное работает. Контроль: запуск 0.8.6 (без -c, без маршрута) — соцсети не открываются,
# поток DNS роняет интернет. Для nov2026 — скорость: загрузка 32 МБ через обход и через «Psiphon»
# (задержка 150 мс и полоса 8 Мбит/с, как у сервера за границей).
# Нужны: gcc, make, patch, JDK, python3 + pyroute2, openssl, curl, util-linux (nsenter, unshare).
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
HELPER_PORT=10808   # HelperRelay (запасной путь), как в приложении
PSIPHON_PORT=1081   # «Psiphon» стенда
# «Psiphon» для замера скорости: сервер за границей — рукопожатие через туннель и общая полоса
PSIPHON_DELAY_MS=150
PSIPHON_KBIT=8000
APP_UID=2000
TUNNEL_UID=2001
LOAD_SECONDS=40
LOAD_PARALLEL=15
CDN_HOST=scontent-hel3-1.cdninstagram.com
TG_IP=149.154.167.51
META_IP=31.13.72.174
GOOGLE_IP=142.250.74.78
PROFILES="${LAB_PROFILES:-nov2026 oct2026 jun2026}"

PIDS=()
# В фоне запускается подоболочка функции (NS/APP/…), программа — её потомок: гасим потомков,
# подоболочка завершается сама (если убить и её, bash печатает «Killed»)
killtree() {  # $1 — pid, $2 — 1: гасить и сам процесс
    local c
    for c in $(pgrep -P "$1" 2>/dev/null); do killtree "$c" 1; done
    [ "${2:-0}" = 1 ] && kill -9 "$1" 2>/dev/null || true
}
stop_pids() {  # остановить процессы стенда из PIDS начиная с номера $1
    local p
    for p in "${PIDS[@]:$1}"; do [ -n "$p" ] && killtree "$p"; done
    for p in "${PIDS[@]:$1}"; do [ -n "$p" ] && { wait "$p"; } 2>/dev/null || true; done
    PIDS=("${PIDS[@]:0:$1}")
}
stop_lab() {
    stop_pids 0
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
# «Psiphon»: свой uid, ходит мимо оператора (туннель за границу)
TUNNEL() { NS phone setpriv --reuid "$TUNNEL_UID" --regid "$TUNNEL_UID" --clear-groups "$@"; }

# ---------- Сборка ----------
echo "Сборка ByeDPI (с правками ODA), hev-socks5-tunnel и проверки ODA…"
BSRCS="$("$ROOT/scripts/byedpi-src.sh" "$TMP/byedpi")"
# shellcheck disable=SC2086
gcc -std=c99 -O1 -D_DEFAULT_SOURCE -I"$TMP/byedpi" -w -o "$TMP/ciadpi" $BSRCS
cp -r "$ROOT/app/src/main/jni/hev-socks5-tunnel" "$TMP/hev"
make -C "$TMP/hev" -j"$(nproc)" > "$TMP/hev-build.log" 2>&1 || { tail -30 "$TMP/hev-build.log"; exit 1; }
HEV="$TMP/hev/bin/hev-socks5-tunnel"

mkdir -p "$TMP/stub/androidx/annotation" "$TMP/stub/com/example/vpnapp" "$TMP/classes"
echo 'package androidx.annotation; public @interface Nullable {}' > "$TMP/stub/androidx/annotation/Nullable.java"
echo 'package com.example.vpnapp; final class DiagLog { static void add(String s) { System.out.println(s); } }' \
    > "$TMP/stub/com/example/vpnapp/DiagLog.java"
javac -nowarn -encoding UTF-8 -d "$TMP/classes" "$TMP/stub/androidx/annotation/Nullable.java" \
    "$TMP/stub/com/example/vpnapp/DiagLog.java" "$SRC/NetProbe.java" "$SRC/BypassSearch.java" \
    "$SRC/BypassProfile.java" "$SRC/Tun2SocksConfig.java" "$SRC/ByeDpiStats.java" "$SRC/HelperRelay.java" \
    "$ROOT/app/src/test/java/com/example/vpnapp/TunLab.java"
# Программы стенда работают и от чужих uid: домашняя папка раннера CI им закрыта — копии в $TMP
cp "$LAB/lab.py" "$LAB/tspu.py" "$TMP/"
chmod -R a+rX "$TMP"
mkdir -m 777 "$TMP/out"  # сюда пишет перебор (uid приложения)
JAVA="$(command -v java)"
# Вывод Java — в UTF-8 (по умолчанию он в кодировке системы, и русский текст станет «???»)
JUTF=(-Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dfile.encoding=UTF-8)
TUNLAB() { "$JAVA" "${JUTF[@]}" -cp "$TMP/classes" com.example.vpnapp.TunLab "$@"; }
APPLAB() { APP "$JAVA" "${JUTF[@]}" -cp "$TMP/classes" com.example.vpnapp.TunLab "$@"; }

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

# DNS телефона в VPN — как у VpnService.Builder при обходе DPI (сервер, ответивший через выбранную
# стратегию, — пишется после перебора); IPv6 у «оператора» нет — не в VPN
phone_dns() { echo "nameserver $1" > "$TMP/resolv.conf"; }
phone_dns 1.1.1.1
echo "hosts: files dns" > "$TMP/nsswitch.conf"

TUNLAB config "$PORT" > "$TMP/hev-new.yml"
TUNLAB config "$PSIPHON_PORT" psiphon > "$TMP/hev-psiphon.yml"
grep -v 'udp-read-write-timeout' "$TMP/hev-new.yml" > "$TMP/hev-old.yml"
mapfile -t PROC_ARGS < <(TUNLAB procargs "$PORT")

short() {  # аргументы для вывода: длинные списки сетей и доменов — одним словом
    local a out=""
    for a in "$@"; do
        case "$a" in
            ":"*" "*) [ "${a#*/}" != "$a" ] && a=":<сети>" || a=":<список доменов>" ;;
        esac
        out="$out $a"
    done
    echo "${out# }"
}

# ---------- Стенд ----------
start_net() {  # $1 — профиль ТСПУ: сеть, «оператор» с моделью ТСПУ, «интернет»
    stop_lab
    python3 "$LAB/lab.py" up "$IPV6"
    : > "$TMP/tspu.log"
    NS isp python3 -u "$TMP/tspu.py" "$1" "$TMP/tspu.log" > "$TMP/tspu.out" 2>&1 &
    PIDS+=($!)
    NS srv python3 -u "$LAB/lab.py" srv "$TMP/site.pem" "$TMP/site.key" > "$TMP/srv.log" 2>&1 &
    PIDS+=($!)
    for _ in $(seq 1 50); do
        grep -q "srv: готов" "$TMP/srv.log" 2>/dev/null && grep -q "tspu: готов" "$TMP/tspu.out" 2>/dev/null && break
        sleep 0.1
    done
    grep -q "tspu: готов" "$TMP/tspu.out" || { echo "  ✗ модель ТСПУ не запустилась:"; cat "$TMP/tspu.out"; exit 1; }
    # Путь через «оператора» должен быть: иначе перебор потратит минуты впустую
    local d i
    for i in 1 2 3; do
        d=$(APP python3 "$TMP/lab.py" dnscheck 2>&1 || true)
        [ "$d" = ok ] && break
    done
    if [ "$d" != ok ]; then
        echo "  ✗ через модель ТСПУ не идут пакеты (DNS напрямую: $d). Настройки «оператора»:"
        NS isp python3 "$TMP/lab.py" dump | sed 's/^/    /'
        echo "    — счётчики интерфейсов «оператора»:"
        NS isp cat /proc/net/dev | sed 's/^/    /'
        echo "    — снмп «оператора» (IP: InHdrErrors, InAddrErrors, ForwDatagrams, InDiscards…):"
        NS isp cat /proc/net/snmp | head -2 | sed 's/^/    /'
        sleep 11
        sed 's/^/    /' "$TMP/tspu.out"
        exit 1
    fi
}

start_phone() {  # $1 — конфиг hev, дальше — аргументы ByeDPI: ByeDPI, tun2socks, маршруты телефона
    local yml="$1"
    shift
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
    NS phone python3 "$LAB/lab.py" routes 0
    sleep 0.5
}

stop_phone() {  # остановить ByeDPI, hev и запасной путь; сеть, ТСПУ и «интернет» остаются
    stop_pids 2
    NS phone python3 "$LAB/lab.py" notun  # ждём, пока tun0 старого hev исчезнет
}

start_helper() {  # «Psiphon» и HelperRelay; $1 — путь открыт (1) или закрыт (0)
    TUNNEL python3 -u "$TMP/lab.py" socks "$PSIPHON_PORT" "$PSIPHON_DELAY_MS" "$PSIPHON_KBIT" \
        > "$TMP/psiphon.log" 2>&1 &
    PIDS+=($!)
    local up=0
    [ "$1" = 1 ] && up="$PSIPHON_PORT"
    APPLAB relay "$HELPER_PORT" "$up" > "$TMP/relay.log" 2>&1 &
    PIDS+=($!)
    for _ in $(seq 1 50); do
        grep -q "socks: готов" "$TMP/psiphon.log" 2>/dev/null && grep -q "relay: готов" "$TMP/relay.log" 2>/dev/null && break
        sleep 0.1
    done
    grep -q "socks: готов" "$TMP/psiphon.log" || { echo "  ✗ «Psiphon» стенда не запустился:"; sed 's/^/    /' "$TMP/psiphon.log"; FAIL=1; }
    grep -q "relay: готов" "$TMP/relay.log" || { echo "  ✗ HelperRelay не запустился:"; sed 's/^/    /' "$TMP/relay.log"; FAIL=1; }
}

site() {  # открыть сайт «браузером телефона» через VPN; печатает код ответа
    PHONE curl -sS --noproxy '*' --cacert "$TMP/ca.pem" -o /dev/null -m "${3:-10}" -w "%{http_code}" \
        "https://$1$2" 2>/dev/null || true
}

site_t() {  # то же, с временем: «200 0.41»; $3 — тайм-аут, с
    PHONE curl -sS --noproxy '*' --cacert "$TMP/ca.pem" -o /dev/null -m "${3:-15}" \
        -w "%{http_code} %{time_total}" "https://$1$2" 2>/dev/null || true
}

speed_run() {  # загрузка /big «браузером телефона» с нескольких сайтов; МБ/с — в SPEEDS
    local h r ok=1
    SPEEDS=()
    for h in www.cloudflare.com www.facebook.com i.instagram.com rr1---sn-n8v7znz7.googlevideo.com; do
        r=$(PHONE curl -sS --noproxy '*' --cacert "$TMP/ca.pem" -o /dev/null -m 90 \
            -w "%{http_code} %{speed_download} %{time_starttransfer}" "https://$h/big" 2>/dev/null || true)
        set -- ${r:-нет 0 0}
        local mb
        mb=$(awk -v b="$2" 'BEGIN { printf "%.2f", b / 1048576 }')
        echo "    $h: $1, $mb МБ/с, первый байт через $3 с"
        [ "$1" = 200 ] || ok=0
        SPEEDS+=("$mb")
    done
    SPEED_OK=$ok
}

fast() { awk -v t="$1" -v lim="$2" 'BEGIN { exit !(t + 0 < lim + 0) }'; }  # $1 < $2 (секунды)
tg() { PHONE python3 "$LAB/lab.py" tgcheck "${1:-10}" 2>/dev/null || true; }
quic() { PHONE python3 "$LAB/lab.py" quiccheck "$1" "${2:-3}" 2>/dev/null || true; }
pool_full() { grep -c 'pool is full' "$TMP/byedpi.log" || true; }

load_test() {  # поток DNS + сайт каждые 5 с; печатает «открылся N из M»
    PHONE python3 "$LAB/lab.py" dnsload "$LOAD_SECONDS" "$LOAD_PARALLEL" "$dns" > "$TMP/load.txt" 2>&1 &
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

expect() {  # $1 — что открываем (для вывода), $2 — результат «код время», $3 — нужный код, $4 — предел, с
    local r="${2:-нет 99}"
    set -- "$1" $r "$3" "$4"
    echo "    $1: ${2:-нет} ${3:-}"
    [ "${2:-}" = "${4}" ] && fast "${3:-99}" "$5"
}

apps_check() {  # «приложения» через VPN; $1 — профиль
    local prof="$1" ok=1 r
    echo "  «Приложения» через VPN:"
    for s in "ya.ru /" "vk.com /" "www.gosuslugi.ru /" "www.cloudflare.com /" "www.wikipedia.org /"; do
        set -- $s
        expect "$1" "$(site_t "$1" "$2")" 200 3 || ok=0
    done
    expect www.google.com "$(site_t www.google.com /generate_204)" 204 3 || ok=0
    check "$ok" "обычные сайты открываются сразу"
    ok=1
    expect www.youtube.com "$(site_t www.youtube.com /)" 200 12 || ok=0
    expect "googlevideo (видео)" "$(site_t rr1---sn-n8v7znz7.googlevideo.com /)" 200 12 || ok=0
    check "$ok" "YouTube открывается"
    ok=1
    expect www.facebook.com "$(site_t www.facebook.com /)" 200 3 || ok=0
    expect "graph.facebook.com (приложение)" "$(site_t graph.facebook.com /)" 200 3 || ok=0
    check "$ok" "Facebook открывается сразу (список доменов → сильная стратегия)"
    ok=1
    local lim=3
    [ "$prof" = oct2026 ] && lim=10  # instagram закрыт по адресу: сильная ждёт 4 с, затем Psiphon
    expect www.instagram.com "$(site_t www.instagram.com /)" 200 "$lim" || ok=0
    expect "i.instagram.com (приложение)" "$(site_t i.instagram.com /)" 200 "$lim" || ok=0
    expect "i.instagram.com ещё раз" "$(site_t i.instagram.com /)" 200 3 || ok=0
    check "$ok" "Instagram открывается (второй раз — сразу: путь к адресу запомнен)"
    ok=1
    expect "$CDN_HOST (картинки)" "$(site_t "$CDN_HOST" / 20)" 200 10 || ok=0
    check "$ok" "CDN картинок, закрытый по адресу, — через запасной путь"
    r=$(tg 10)
    echo "    Telegram $TG_IP: $r"
    check "$([ "${r%% *}" = ok ] && echo 1 || echo 0)" "Telegram — через запасной путь"
    local qm qg
    qm=$(quic "$META_IP")
    qg=$(quic "$GOOGLE_IP")
    echo "    QUIC к Meta $META_IP: $qm, к Google $GOOGLE_IP: $qg"
    check "$([ "$qm" = fail ] && [ "$qg" = ok ] && echo 1 || echo 0)" \
        "QUIC к Meta отбрасывается (приложения уходят на TCP), к Google — идёт"
}

stats() {  # сводка ByeDpiStats по выводу ByeDPI; $1 — порт запасного пути (0 — без него)
    TUNLAB stats "$TMP/byedpi.log" "$TMP/out/chosen.profile" "$1"
}

# ---------- Профили ТСПУ ----------
for PROFILE in $PROFILES; do
    echo
    echo "=================== Профиль ТСПУ «$PROFILE» ==================="
    start_net "$PROFILE"
    echo "1) Выбор стратегии — как на телефоне (BypassSearch + NetProbe, пачки по 3 процесса ByeDPI):"
    rm -f "$TMP"/out/*
    if APPLAB search "$TMP/ciadpi" "$TMP/ca.pem" "$HELPER_PORT" "$TMP/out/chosen" > "$TMP/search.txt" 2>&1; then
        search_ok=1
    else
        search_ok=0
    fi
    sed 's/^/    /' "$TMP/search.txt" | grep -v 'Обход DPI: запуск' || true
    label=$(cat "$TMP/out/chosen.label" 2>/dev/null || echo "")
    dns=$(cat "$TMP/out/chosen.dns" 2>/dev/null || echo 1.1.1.1)
    phone_dns "$dns"
    check "$([ "$search_ok" = 1 ] && grep -q 'заблокированные сайты открываются' "$TMP/search.txt" && echo 1 || echo 0)" \
        "выбрана стратегия, пробивающая заблокированные: «$label»"
    case "$PROFILE" in
        oct2026) check "$(echo "$label" | grep -q tlsrec && echo 1 || echo 0)" "как на телефоне в 0.8.7 — tlsrec" ;;
        nov2026)
            check "$(echo "$label" | grep -q tlsrec && echo 1 || echo 0)" "как на телефоне в 0.8.8 — tlsrec"
            check "$([ "$dns" = 9.9.9.9 ] && echo 1 || echo 0)" \
                "UDP-DNS к 1.1.1.1 и 8.8.8.8 закрыт — DNS телефона $dns (обход не отвергнут из-за DNS)" ;;
        jun2026) check "$(echo "$label" | grep -qE 'oob|disoob' && echo 1 || echo 0)" "как на телефоне в 0.8.6 — OOB-семейство" ;;
    esac
    [ "$search_ok" = 1 ] || continue
    mapfile -t ROUTE < "$TMP/out/chosen"
    ARGS=("${PROC_ARGS[@]}" "${ROUTE[@]}")
    echo "    ByeDPI: $(short "${ARGS[@]}")"

    echo "2) Подключено, запасной путь открыт (Psiphon готов):"
    start_phone "$TMP/hev-new.yml" "${ARGS[@]}"
    start_helper 1
    apps_check "$PROFILE"
    if APPLAB probe "$PORT" "$TMP/ca.pem" > "$TMP/probe.txt"; then probe_ok=1; else probe_ok=0; fi
    sed 's/^/  /' "$TMP/probe.txt"
    check "$probe_ok" "NetProbe: DNS через SOCKS-UDP, обычные сайты, сторож"
    st=$(stats "$HELPER_PORT")
    echo "$st" | sed 's/^/    /'
    check "$(echo "$st" | grep -q 'QUIC (UDP 443):.*Meta' && echo "$st" | grep -q 'Telegram → Psiphon' && echo 1 || echo 0)" \
        "сводка ByeDPI: QUIC к Meta виден, Telegram шёл через Psiphon"

    if [ "$PROFILE" = nov2026 ]; then
        echo "3) Скорость: загрузка 32 МБ через обход DPI (прямое соединение через «оператора»):"
        speed_run
        BYPASS_SPEEDS=("${SPEEDS[@]}")
        check "$SPEED_OK" "через обход все загрузки дошли"
        echo "   то же через «Psiphon» (подключение через Psiphon, как в журнале 0.8.8):"
        stop_phone
        # Фиктивный DNS (mapdns у hev, Tun2SocksConfig.FAKE_DNS): имена резолвит «Psiphon»
        phone_dns "$(awk '/^mapdns:/ { m = 1 } m && /address:/ { print $2; exit }' "$TMP/hev-psiphon.yml")"
        TUNNEL python3 -u "$TMP/lab.py" socks "$PSIPHON_PORT" "$PSIPHON_DELAY_MS" "$PSIPHON_KBIT" \
            > "$TMP/psiphon.log" 2>&1 &
        PIDS+=($!)
        cp "$TMP/hev-psiphon.yml" "$TMP/hev.yml"
        if [ "$IPV6" = 0 ]; then sed -i "/ipv6:/d" "$TMP/hev.yml"; fi
        sed -i "s/^tunnel:/tunnel:\n  name: tun0/" "$TMP/hev.yml"
        NS phone env ${PRELOAD:+LD_PRELOAD=$PRELOAD} "$HEV" "$TMP/hev.yml" > "$TMP/hev.log" 2>&1 &
        PIDS+=($!)
        NS phone python3 "$LAB/lab.py" routes 0
        sleep 1
        speed_run
        PSIPHON_SPEEDS=("${SPEEDS[@]}")
        check "$SPEED_OK" "через «Psiphon» все загрузки дошли"
        ok=1
        for i in "${!BYPASS_SPEEDS[@]}"; do
            awk -v a="${BYPASS_SPEEDS[$i]}" -v b="${PSIPHON_SPEEDS[$i]}" 'BEGIN { exit !(a >= 3 * b && a >= 2) }' || ok=0
        done
        check "$ok" "обход DPI быстрее Psiphon минимум втрое (и не меньше 2 МБ/с): ${BYPASS_SPEEDS[*]} против ${PSIPHON_SPEEDS[*]} МБ/с"
        stop_phone
        phone_dns "$dns"
        start_phone "$TMP/hev-new.yml" "${ARGS[@]}"
        start_helper 1
    fi

    if [ "$PROFILE" = oct2026 ]; then
        echo "3) Нагрузка как у телефона (DNS $LOAD_PARALLEL потоков, ${LOAD_SECONDS} с):"
        load_test
        check "$([ "$LOAD_GOOD" = "$LOAD_TOTAL" ] && echo 1 || echo 0)" "сайт открывался всё время: $LOAD_GOOD из $LOAD_TOTAL"
        check "$([ "$(pool_full)" = 0 ] && echo 1 || echo 0)" "пул ByeDPI не переполнялся"

        echo "4) Запасной путь закрыт (Psiphon ещё не готов или недоступен):"
        stop_phone
        start_phone "$TMP/hev-new.yml" "${ARGS[@]}"
        start_helper 0
        ok=1
        for s in "ya.ru / 200 3" "www.facebook.com / 200 3" "www.youtube.com / 200 12"; do
            set -- $s
            expect "$1" "$(site_t "$1" "$2")" "$3" "$4" || ok=0
        done
        r=$(tg 10)
        echo "    Telegram: $r"
        check "$ok" "обычные сайты, Facebook и YouTube работают без запасного пути"
        check "$([ "${r%% *}" = fail ] && echo 1 || echo 0)" "Telegram без Psiphon не открывается (закрыт по адресу)"
        w_cdn=$(APPLAB why 157.240.200.36)
        w_fb=$(APPLAB why 31.13.72.36)
        echo "    почему 157.240.200.36 (CDN): $w_cdn"
        echo "    почему 31.13.72.36 (facebook): $w_fb"
        check "$(echo "$w_cdn" | grep -q 'по адресу' && echo "$w_fb" | grep -q 'по имени' && echo 1 || echo 0)" \
            "проверка «почему» отличает блокировку по адресу от DPI"

        echo
        echo "=== Контроль — запуск 0.8.6 (без -c, без маршрута; стратегия «split SNI → disoob SNI», как у телефона)"
        stop_phone
        mapfile -t OLD < <(TUNLAB old "$PORT" "disoob SNI")
        echo "    ByeDPI: ${OLD[*]}, hev без тайм-аута UDP"
        start_phone "$TMP/hev-old.yml" "${OLD[@]}"
        r=$(site_t i.instagram.com / 15)
        r2=$(site_t www.facebook.com / 15)
        echo "    i.instagram.com: ${r:-нет}; www.facebook.com: ${r2:-нет}; Telegram: $(tg 8)"
        if [ "${r%% *}" != 200 ] && [ "${r2%% *}" != 200 ]; then
            echo "  ✓ со старым запуском соцсети не открываются — как на телефоне в 0.8.7"
        else
            echo "  ! контроль не воспроизвёлся (не провал)"
        fi
        echo "   Поток DNS со старым конфигом:"
        load_test
        if [ "$LOAD_GOOD" -lt "$LOAD_TOTAL" ] && [ "$(pool_full)" != 0 ]; then
            echo "  ✓ со старым конфигом интернет пропадает — тест ловит ошибку 0.8.5"
        else
            echo "  ! со старым конфигом ошибка не воспроизвелась: нагрузка мала для этой машины (не провал)"
        fi
    fi
done

echo
if [ "$FAIL" = 0 ]; then
    echo "Имитация телефона с обходом DPI: OK"
else
    echo "Имитация телефона с обходом DPI: ОШИБКА"
    echo "--- ByeDPI (хвост)"; tail -20 "$TMP/byedpi.log" || true
    echo "--- hev (хвост)"; tail -20 "$TMP/hev.log" || true
    echo "--- ТСПУ (решения, хвост)"; tail -30 "$TMP/tspu.log" 2>/dev/null || true
    echo "--- ТСПУ (вывод)"; tail -20 "$TMP/tspu.out" 2>/dev/null || true
    echo "--- «Psiphon» (хвост)"; tail -20 "$TMP/psiphon.log" 2>/dev/null || true
    echo "--- HelperRelay (хвост)"; tail -20 "$TMP/relay.log" 2>/dev/null || true
    exit 1
fi
