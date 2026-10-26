#!/usr/bin/env python3
"""Стенд для scripts/test-tun.sh: путь трафика телефона при обходе DPI на компьютере.

Три сетевых пространства (netns):
  phone — «телефон»: VPN-интерфейс tun0 (hev-socks5-tunnel) и ByeDPI. ByeDPI и проверки ODA
          работают от uid 2000 и идут мимо VPN (как addDisallowedApplication в Android);
          «Psiphon» (SOCKS5, команда socks) работает от uid 2001 и ходит в «интернет» мимо
          оператора — по отдельному каналу p1/s1, как зашифрованный туннель за границу;
  isp   — «оператор»: маршрутизатор с моделью ТСПУ (tspu.py, профиль выбирает test-tun.sh):
          рвёт соединения по имени сайта, режет по адресу CDN соцсети и сервер Telegram;
  srv   — «интернет»: DNS 1.1.1.1, 8.8.8.8, 9.9.9.9, 77.88.8.8 (UDP и TCP), HTTPS-сайты
          с адресами из журналов (/big — 32 МБ для замера скорости),
          «сервер Telegram» (TG_IP:443, не TLS: отвечает эхом, как MTProto — не по имени сайта)
          и UDP-эхо на порту 443 («QUIC»).
IPv6 у «оператора» нет (как в мобильной сети без IPv6); тогда и приложение не направляет IPv6
в VPN (routes 0). routes 1 — IPv6 в VPN без IPv6 у оператора: так было до 0.8.6, и сайты с AAAA
не открывались (tun2socks принимает соединение раньше, чем ByeDPI узнаёт, что IPv6 нет).

Команды: up <ipv6 0|1> | down | routes <ipv6 0|1> | srv <cert> <key> | dnsload <сек> <потоков> [dns]
         | socks <порт> [мс задержки] [кбит/с] | tgcheck <сек> | quiccheck <ip> <сек> | notun
         | dnscheck [dns] | dump
Нужен pyroute2 (pip install pyroute2) и root.
"""
import socket
import struct
import subprocess
import sys
import threading
import time

APP_UID = 2000
TUNNEL_UID = 2001  # «Psiphon»: его трафик идёт мимо оператора (туннель за границу)
SITES = {  # все сайты NetProbe; IPv4 — как в журналах пользователя (0.8.7)
    "www.instagram.com": "31.13.72.174",
    # Приложение Instagram ходит не на www: API на том же узле Meta, картинки — на CDN.
    # Адрес CDN в стенде закрыт по IP — его не откроет ни одна стратегия
    "i.instagram.com": "31.13.72.174",
    "scontent-hel3-1.cdninstagram.com": "157.240.200.36",
    "www.facebook.com": "31.13.72.36",
    "graph.facebook.com": "31.13.72.36",
    # YouTube: в журнале 0.8.7 мягкая стратегия для него срывалась, открывала сильная
    "www.youtube.com": "142.250.74.78",
    "rr1---sn-n8v7znz7.googlevideo.com": "173.194.182.6",
    "x.com": "162.159.140.229",
    "rutracker.org": "104.21.32.39",
    "www.google.com": "142.250.74.46",
    "www.cloudflare.com": "104.16.123.96",
    "www.wikipedia.org": "185.15.59.224",
    "ya.ru": "77.88.55.242",
    "vk.com": "87.240.132.72",
    "www.gosuslugi.ru": "213.59.254.7",
}
# AAAA есть, но сети IPv6 у оператора нет: телефон пробует IPv6 через VPN и откатывается на IPv4
SITES6 = {
    "www.instagram.com": "2a03:2880:f240:1e6:face:b00c:0:4420",
    "i.instagram.com": "2a03:2880:f240:1e6:face:b00c:0:4420",
    "www.google.com": "2a00:1450:4010:c0e::63",
    "ya.ru": "2a02:6b8::2:242",
}
DNS_IP = "1.1.1.1"
DNS_IPS = ["1.1.1.1", "8.8.8.8", "9.9.9.9", "77.88.8.8"]  # как NetProbe.DNS_SERVERS
BIG = 32 * 1024 * 1024  # /big: замер скорости
TG_IP = "149.154.167.51"  # DC2 Telegram: протокол без имени сайта, режется по адресу


def down():
    from pyroute2 import netns
    for n in ("phone", "isp", "srv"):
        try:
            netns.remove(n)
        except Exception:
            pass


def up(ipv6):
    from pyroute2 import IPRoute, NetNS, netns
    down()
    for n in ("phone", "isp", "srv"):
        netns.create(n)
    ip = IPRoute()
    ip.link("add", ifname="p0", kind="veth", peer="i0")
    ip.link("add", ifname="s0", kind="veth", peer="i1")
    ip.link("add", ifname="p1", kind="veth", peer="s1")  # «туннель за границу» мимо оператора
    for ifn, ns in (("p0", "phone"), ("i0", "isp"), ("i1", "isp"), ("s0", "srv"),
                    ("p1", "phone"), ("s1", "srv")):
        ip.link("set", index=ip.link_lookup(ifname=ifn)[0], net_ns_fd=ns)
    ip.close()

    def conf(ns, addrs, routes=()):
        n = NetNS(ns)
        n.link("set", index=n.link_lookup(ifname="lo")[0], state="up")
        for ifn, a, plen in addrs:
            idx = n.link_lookup(ifname=ifn)[0]
            n.addr("add", index=idx, address=a, prefixlen=plen)
            n.link("set", index=idx, state="up")
        for r in routes:
            n.route("add", **r)
        return n

    # rp_filter выключен во всех netns: на раннере CI значения наследуются от системы, а у «телефона»
    # до запуска VPN нет маршрута в основной таблице (у ODA — своя таблица по uid), и проверка
    # обратного пути отбрасывала ответы
    for ns in ("phone", "isp", "srv"):
        subprocess.run(["nsenter", f"--net=/var/run/netns/{ns}", "sh", "-c",
                        "for f in /proc/sys/net/ipv4/conf/*/rp_filter; do echo 0 > $f; done"], check=True)

    conf("isp", [("i0", "10.10.0.1", 24), ("i1", "10.20.0.1", 24)],
         [dict(dst="0.0.0.0/0", gateway="10.20.0.2")]).close()
    conf("srv", [("s0", "10.20.0.2", 24), ("s1", "10.30.0.2", 24), ("lo", TG_IP, 32)]
         + [("lo", a, 32) for a in DNS_IPS]
         + [("lo", a, 32) for a in sorted(set(SITES.values()))],
         [dict(dst="0.0.0.0/0", gateway="10.20.0.1")]).close()
    n = conf("phone", [("p0", "10.10.0.2", 24), ("p1", "10.30.0.1", 24)])
    # «Psiphon» ходит в интернет по своему каналу, мимо DPI оператора (таблица 101)
    n.route("add", dst="0.0.0.0/0", gateway="10.30.0.2", table=101)
    n.rule("add", uid_range=f"{TUNNEL_UID}:{TUNNEL_UID}", table=101, priority=99)
    # «Приложение ODA» исключено из VPN: его трафик идёт в сеть оператора (таблица 100)
    n.route("add", dst="0.0.0.0/0", gateway="10.10.0.1", table=100)
    n.rule("add", uid_range=f"{APP_UID}:{APP_UID}", table=100, priority=100)
    if ipv6:
        # IPv6 у оператора нет: у приложения он недоступен, а не уходит в VPN
        n.route("add", dst="::/0", type="unreachable", table=100, family=socket.AF_INET6)
        n.rule("add", uid_range=f"{APP_UID}:{APP_UID}", table=100, priority=100, family=socket.AF_INET6)
    n.close()


def routes(ipv6):
    """Весь трафик телефона (кроме приложения) — в tun0, как у VpnService.Builder.addRoute."""
    from pyroute2 import IPRoute
    ip = IPRoute()
    for _ in range(100):
        links = ip.link_lookup(ifname="tun0")
        if links:
            break
        time.sleep(0.1)
    else:
        sys.exit("tun0 не появился (hev-socks5-tunnel не запустился)")
    ip.route("replace", dst="0.0.0.0/0", oif=links[0])
    if ipv6:
        ip.route("replace", dst="::/0", oif=links[0], family=socket.AF_INET6)
    ip.close()


# ---------- «Интернет» ----------

def dns_answer(q):
    tid = q[:2]
    i, labels = 12, []
    while q[i]:
        labels.append(q[i + 1:i + 1 + q[i]].decode())
        i += 1 + q[i]
    name = ".".join(labels).lower()
    qtype = struct.unpack(">H", q[i + 1:i + 3])[0]
    question = q[12:i + 5]
    ans = b""
    if qtype == 1 and name in SITES:
        ans = b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 60, 4) + socket.inet_aton(SITES[name])
    elif qtype == 28 and name in SITES6:
        ans = b"\xc0\x0c" + struct.pack(">HHIH", 28, 1, 60, 16) + socket.inet_pton(socket.AF_INET6, SITES6[name])
    rcode = 0 if name in SITES else 3
    return tid + struct.pack(">HHHHH", 0x8180 | rcode, 1, 1 if ans else 0, 0, 0) + question + ans


def serve(cert, key):
    import ssl

    def dns_udp(addr):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind((addr, 53))
        while True:
            q, a = s.recvfrom(1500)
            try:
                s.sendto(dns_answer(q), a)
            except Exception as e:
                print("dns:", e, flush=True)

    def dns_tcp(addr):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((addr, 53))
        s.listen(64)
        while True:
            c, _ = s.accept()
            try:
                n = struct.unpack(">H", c.recv(2))[0]
                r = dns_answer(c.recv(n))
                c.sendall(struct.pack(">H", len(r)) + r)
            except Exception:
                pass
            finally:
                c.close()

    body = b"x" * 65536
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)

    def handle(c):
        try:
            c.settimeout(20)
            t = ctx.wrap_socket(c, server_side=True)
            data = b""
            while b"\r\n\r\n" not in data:
                d = t.recv(4096)
                if not d:
                    return
                data += d
            path = data.split(b" ", 2)[1]
            if path == b"/generate_204":
                t.sendall(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            elif path == b"/big":
                t.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % BIG)
                for _ in range(BIG // len(body)):
                    t.sendall(body)
            else:
                t.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body)
            t.close()
        except Exception:
            pass
        finally:
            c.close()

    def telegram(c):
        """«Сервер Telegram»: не TLS, первым говорит клиент; отвечает эхом каждого блока."""
        try:
            c.settimeout(20)
            while True:
                d = c.recv(4096)
                if not d:
                    return
                c.sendall(d)
        except Exception:
            pass
        finally:
            c.close()

    def https():
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", 443))
        s.listen(256)
        while True:
            c, _ = s.accept()
            target = telegram if c.getsockname()[0] == TG_IP else handle
            threading.Thread(target=target, args=(c,), daemon=True).start()

    def quic_echo(addr):
        # Свой сокет на каждый адрес: ответ должен прийти с того адреса, куда шёл запрос
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind((addr, 443))
        while True:
            d, a = s.recvfrom(2048)
            s.sendto(d, a)

    for a in sorted(set(SITES.values())):
        threading.Thread(target=quic_echo, args=(a,), daemon=True).start()
    for a in DNS_IPS:
        for f in (dns_udp, dns_tcp):
            threading.Thread(target=f, args=(a,), daemon=True).start()
    threading.Thread(target=https, daemon=True).start()
    print("srv: готов", flush=True)
    threading.Event().wait()


# ---------- Нагрузка «как телефон» ----------

def dnsload(seconds, parallel, server=DNS_IP):
    """DNS-запросы через VPN, каждый с нового сокета (новый порт), как резолвер Android."""
    stop = time.time() + seconds
    ok = fail = 0
    lock = threading.Lock()

    def worker(k):
        nonlocal ok, fail
        i = 0
        while time.time() < stop:
            i += 1
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(3)
            q = ((k * 7919 + i) & 0xffff).to_bytes(2, "big") + b"\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" \
                + b"\x02ya\x02ru\x00\x00\x01\x00\x01"
            try:
                s.sendto(q, (server, 53))
                s.recv(512)
                good = True
            except OSError:
                good = False
            finally:
                s.close()
            with lock:
                if good:
                    ok += 1
                else:
                    fail += 1

    threads = [threading.Thread(target=worker, args=(k,)) for k in range(parallel)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    print(f"DNS-нагрузка: ответов {ok} из {ok + fail} за {seconds} с")


# ---------- «Psiphon»: SOCKS5 (CONNECT) с выходом мимо оператора ----------

def socks(port, delay_ms=0, kbit=0):
    """delay_ms — задержка соединения (сервер Psiphon за границей: рукопожатие через туннель),
    kbit — общая полоса туннеля на все соединения, кбит/с (0 — без ограничения)."""
    lock = threading.Lock()
    free_at = [0.0]  # когда «туннель» освободится (общая полоса)

    def pace(n):
        if not kbit:
            return
        with lock:
            now = time.time()
            start = max(now, free_at[0])
            free_at[0] = start + n * 8 / (kbit * 1000)
        if start > now:
            time.sleep(start - now)

    def pipe(a, b):
        try:
            while True:
                d = a.recv(16384)
                if not d:
                    break
                pace(len(d))
                b.sendall(d)
            b.shutdown(socket.SHUT_WR)
        except OSError:
            pass

    def serve_one(c):
        r = None
        try:
            c.settimeout(20)
            h = c.recv(2)
            c.recv(h[1])
            c.sendall(b"\x05\x00")
            q = c.recv(4)
            if q[1] != 1:  # как у Psiphon: только CONNECT (UDP ASSOCIATE от hev — отказ)
                c.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
                return
            if q[3] == 1:
                host = socket.inet_ntoa(c.recv(4))
            elif q[3] == 3:
                host = c.recv(c.recv(1)[0]).decode()
            else:
                host = socket.inet_ntop(socket.AF_INET6, c.recv(16))
            port_ = struct.unpack(">H", c.recv(2))[0]
            if delay_ms:
                time.sleep(delay_ms / 1000)
            # Имена (mapdns у hev при подключении через Psiphon) — по адресам «интернета»
            r = socket.create_connection((SITES.get(host, host), port_), timeout=10)
            r.settimeout(None)
            c.settimeout(None)
            c.sendall(b"\x05\x00\x00\x01" + socket.inet_aton("0.0.0.0") + b"\x00\x00")
            t = threading.Thread(target=pipe, args=(r, c), daemon=True)
            t.start()
            pipe(c, r)
            t.join()
        except Exception as e:
            try:
                c.sendall(b"\x05\x01\x00\x01\x00\x00\x00\x00\x00\x00")
            except OSError:
                pass
            print("socks:", e, flush=True)
        finally:
            c.close()
            if r:
                r.close()

    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(128)
    print("socks: готов", flush=True)
    while True:
        c, _ = s.accept()
        threading.Thread(target=serve_one, args=(c,), daemon=True).start()


def tgcheck(seconds):
    """Как приложение Telegram: TCP к серверу (через VPN), 64 байта «рукопожатия» без имени
    сайта и сообщение; ответ должен вернуться. Печатает ok/fail и время."""
    import os
    t0 = time.time()
    try:
        s = socket.create_connection((TG_IP, 443), timeout=seconds)
        s.settimeout(seconds)
        hello = b"\x5a" + os.urandom(63) + os.urandom(120)  # первый байт — не 0x16 (не TLS) и не 0xef
        s.sendall(hello)
        got = b""
        while len(got) < len(hello):
            d = s.recv(4096)
            if not d:
                break
            got += d
        s.close()
        ok = got == hello
    except OSError:
        ok = False
    print(("ok" if ok else "fail") + " %.1f" % (time.time() - t0))
    sys.exit(0 if ok else 1)


def quiccheck(ip, seconds):
    """Как приложение с QUIC: UDP-пакет (длинный заголовок QUIC) на ip:443 через VPN, ждём эхо."""
    import os
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(seconds)
    pkt = b"\xc3\x00\x00\x00\x01" + os.urandom(1195)
    try:
        s.sendto(pkt, (ip, 443))
        ok = s.recv(2048) == pkt
    except OSError:
        ok = False
    print("ok" if ok else "fail")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "up":
        up(sys.argv[2] == "1")
    elif cmd == "down":
        down()
    elif cmd == "routes":
        routes(sys.argv[2] == "1")
    elif cmd == "srv":
        serve(sys.argv[2], sys.argv[3])
    elif cmd == "dnsload":
        dnsload(int(sys.argv[2]), int(sys.argv[3]), *sys.argv[4:5])
    elif cmd == "socks":
        socks(*(int(a) for a in sys.argv[2:5]))
    elif cmd == "tgcheck":
        tgcheck(float(sys.argv[2]))
    elif cmd == "dnscheck":
        # DNS-запрос (мимо VPN, если запущен от uid приложения): есть ли путь через «оператора».
        # По умолчанию — Яндекс: зарубежные резолверы оператор может резать (профиль nov2026)
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(3)
        q = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x02ya\x02ru\x00\x00\x01\x00\x01"
        try:
            s.sendto(q, (sys.argv[2] if len(sys.argv) > 2 else DNS_IPS[-1], 53))
            s.recv(512)
            print("ok")
        except OSError as e:
            print("fail", e)
            sys.exit(1)
    elif cmd == "dump":
        # Настройки «оператора» для разбора, почему пакеты не идут (запускать в netns isp)
        from pyroute2 import IPRoute
        with IPRoute() as ip:
            for r in ip.get_rules():
                print("rule", r.get_attr("FRA_PRIORITY"), "iif", r.get_attr("FRA_IIFNAME"), "table", r.get_attr("FRA_TABLE"))
            for t in (10, 254):
                for r in ip.get_routes(table=t):
                    print("route table", t, r.get_attr("RTA_DST"), r["dst_len"], r.get_attr("RTA_OIF"),
                          r.get_attr("RTA_GATEWAY"))
            for l in ip.get_links():
                print("link", l["index"], l.get_attr("IFLA_IFNAME"), l["flags"])
        import glob
        for f in sorted(glob.glob("/proc/sys/net/ipv4/conf/*/forwarding") + glob.glob("/proc/sys/net/ipv4/conf/*/rp_filter")):
            print(f, open(f).read().strip())
        print("ip_forward", open("/proc/sys/net/ipv4/ip_forward").read().strip())
    elif cmd == "notun":
        from pyroute2 import IPRoute
        with IPRoute() as ip:
            for _ in range(50):
                if not ip.link_lookup(ifname="tun0"):
                    break
                time.sleep(0.1)
    elif cmd == "quiccheck":
        quiccheck(sys.argv[2], float(sys.argv[3]))
    else:
        sys.exit(__doc__)
