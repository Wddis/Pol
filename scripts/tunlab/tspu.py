#!/usr/bin/env python3
"""Модель ТСПУ для имитации телефона (scripts/test-tun.sh): программный DPI в netns «оператора».

Весь трафик, который «оператор» пересылает между телефоном (i0) и интернетом (i1), ядро отдаёт
в TUN-интерфейс dpi0; эта программа читает пакет, решает (пропустить или отбросить) и пишет его
в dpi1 — дальше ядро пересылает его как обычно. Так DPI видит настоящие пакеты ByeDPI: части
ClientHello, перестановки (disorder), TTL, флаг URG (OOB), отдельные TLS-записи (tlsrec).

Поведение — профили (PROFILES ниже), подобраны по журналам телефона:
  sni        — имена сайтов (с поддоменами), по которым ТСПУ рвёт соединение: собирает поток
               клиента по номерам последовательности (часть, пришедшая позже, перекрывает
               раньшую — фейк с тем же номером не помогает), находит SNI в ClientHello
               (или Host в HTTP) и отбрасывает этот и все следующие пакеты соединения в обе стороны;
  urg        — "keep": байт OOB (флаг URG) остаётся в собранном потоке и портит имя (OOB пробивает);
               "strip": байт OOB вырезается, как у обычного TCP (OOB не помогает);
  tls        — "first": имя ищется только в первой TLS-записи; ClientHello, разрезанный на записи
               (tlsrec), не разбирается и пропускается; "join": записи склеиваются (tlsrec не помогает);
  ip_block   — сети, где режутся все пакеты с данными (TCP соединяется, данные не доходят; и UDP);
  freeze     — {имя: байт}: после стольких байт от сервера соединение «замерзает».
Расстояния: DPI видит пакет, если он дошёл до оператора; до сервера ещё SERVER_HOPS — пакет
с меньшим TTL (фейк с малым TTL) до сервера не доходит, а больший доходит и портит ClientHello.

Запуск (root, в netns isp): tspu.py <профиль> [журнал решений]
"""
import fcntl
import ipaddress
import os
import select
import struct
import sys
import time

SERVER_HOPS = 6  # от ТСПУ до сервера: фейк с TTL ≤ 6 (после первого маршрутизатора) до сервера не доходит
MAX_STREAM = 8192

META = ["31.13.72.174/32"]  # адрес www/i.instagram.com в журнале 0.8.7: ни одна стратегия не открыла
PROFILES = {
    # Журнал 0.8.6 (18:47): OOB-семейство пробивает соцсети, мягкие — нет
    "jun2026": dict(sni=["instagram.com", "cdninstagram.com", "facebook.com", "x.com", "rutracker.org"],
                    urg="keep", tls="join",
                    ip_block=["149.154.160.0/20", "157.240.200.36/32"], freeze={}),
    # Журнал 0.8.7 (20:51): OOB и disoob больше не пробивают, tlsrec+oob и tlsrec+disorder —
    # да (facebook, x, rutracker), instagram не открылся ничем; YouTube режется по имени для мягкой
    "oct2026": dict(sni=["instagram.com", "cdninstagram.com", "facebook.com", "x.com", "rutracker.org",
                         "googlevideo.com", "youtube.com", "ytimg.com"],
                    urg="strip", tls="first",
                    ip_block=["149.154.160.0/20", "157.240.200.36/32"] + META, freeze={}),
}

TUNSETIFF = 0x400454CA
IFF_TUN, IFF_NO_PI = 0x0001, 0x1000


def tun(name):
    fd = os.open("/dev/net/tun", os.O_RDWR)
    fcntl.ioctl(fd, TUNSETIFF, struct.pack("16sH", name.encode(), IFF_TUN | IFF_NO_PI))
    return fd


def setup():
    """Весь пересылаемый трафик — в dpi0; что программа пишет в dpi1, ядро пересылает дальше."""
    from pyroute2 import IPRoute
    a, b = tun("dpi0"), tun("dpi1")
    ip = IPRoute()
    for name in ("dpi0", "dpi1"):
        ip.link("set", index=ip.link_lookup(ifname=name)[0], state="up", mtu=1500)
    ip.route("add", dst="0.0.0.0/0", oif=ip.link_lookup(ifname="dpi0")[0], table=10)
    ip.rule("add", iifname="i0", table=10, priority=100)
    ip.rule("add", iifname="i1", table=10, priority=101)
    ip.close()
    for k in ("all", "default", "i0", "i1", "dpi0", "dpi1"):
        with open(f"/proc/sys/net/ipv4/conf/{k}/rp_filter", "w") as f:
            f.write("0")
    with open("/proc/sys/net/ipv4/ip_forward", "w") as f:
        f.write("1")
    return a, b


def match(host, names):
    host = host.lower().rstrip(".")
    return any(host == n or host.endswith("." + n) for n in names)


def sni_from_handshake(hs):
    """('need', None) — мало байт; ('ok', имя или None)."""
    if len(hs) < 4:
        return "need", None
    if hs[0] != 1:
        return "ok", None
    ln = int.from_bytes(hs[1:4], "big")
    if len(hs) < 4 + ln:
        return "need", None
    b = hs[4:4 + ln]
    try:
        p = 34
        p += 1 + b[p]
        p += 2 + int.from_bytes(b[p:p + 2], "big")
        p += 1 + b[p]
        end = p + 2 + int.from_bytes(b[p:p + 2], "big")
        p += 2
        while p + 4 <= end:
            t, l = int.from_bytes(b[p:p + 2], "big"), int.from_bytes(b[p + 2:p + 4], "big")
            if t == 0:
                nl = int.from_bytes(b[p + 7:p + 9], "big")
                return "ok", b[p + 9:p + 9 + nl].decode("ascii", "replace")
            p += 4 + l
    except (IndexError, ValueError):
        pass
    return "ok", None


def inspect(data, mode):
    """Собранный поток клиента → ('need'|'pass'|'host', имя)."""
    if len(data) < 5:
        return "need", None
    if data[0] == 0x16:
        if mode == "first":
            rl = int.from_bytes(data[3:5], "big")
            if len(data) < 5 + rl:
                return "need", None
            hs = data[5:5 + rl]
            if len(hs) >= 4 and 4 + int.from_bytes(hs[1:4], "big") > rl:
                return "pass", None  # ClientHello разрезан на TLS-записи — не разбирается
            st, name = sni_from_handshake(hs)
        else:
            hs, p = b"", 0
            while p + 5 <= len(data) and data[p] == 0x16:
                rl = int.from_bytes(data[p + 3:p + 5], "big")
                hs += data[p + 5:p + 5 + rl]
                p += 5 + rl
            st, name = sni_from_handshake(hs)
        if st == "need":
            return "need", None
        return ("host", name) if name else ("pass", None)
    if data[:4] in (b"GET ", b"POST", b"HEAD") or data[:3] == b"PUT":
        if b"\r\n\r\n" not in data:
            return "need", None
        for line in data.split(b"\r\n"):
            if line.lower().startswith(b"host:"):
                return "host", line[5:].strip().split(b":")[0].decode("ascii", "replace")
        return "pass", None
    return "pass", None  # не TLS и не HTTP (MTProto и т.п.)


class Flow:
    __slots__ = ("isn", "buf", "have", "urg", "state", "host", "srv_bytes", "seen")

    def __init__(self, isn):
        self.isn = isn
        self.buf = bytearray(MAX_STREAM)
        self.have = bytearray(MAX_STREAM)
        self.urg = set()
        self.state = "open"  # open → pass | block
        self.host = None
        self.srv_bytes = 0
        self.seen = time.time()

    def add(self, seq, payload, urg_pos):
        off = (seq - self.isn - 1) & 0xffffffff
        if off >= MAX_STREAM:
            return
        n = min(len(payload), MAX_STREAM - off)
        self.buf[off:off + n] = payload[:n]  # пришедшая позже часть перекрывает раньшую
        self.have[off:off + n] = b"\x01" * n
        if urg_pos is not None:
            self.urg.add((urg_pos - self.isn - 1) & 0xffffffff)

    def stream(self, urg_mode):
        end = self.have.find(0)
        end = MAX_STREAM if end < 0 else end
        data = bytes(self.buf[:end])
        if urg_mode == "strip" and self.urg:
            data = bytes(b for i, b in enumerate(data) if i not in self.urg)
        return data, end


def main():
    prof = PROFILES[sys.argv[1]]
    logf = open(sys.argv[2], "a", buffering=1) if len(sys.argv) > 2 else None

    def log(msg):
        if logf:
            logf.write(time.strftime("%H:%M:%S ") + msg + "\n")

    ipb = [ipaddress.ip_network(n) for n in prof["ip_block"]]
    fin, fout = setup()
    flows = {}
    print("tspu: готов (" + sys.argv[1] + ")", flush=True)
    last_gc = time.time()
    while True:
        select.select([fin], [], [], 5)
        try:
            pkt = os.read(fin, 65535)
        except BlockingIOError:
            continue
        now = time.time()
        if now - last_gc > 30:
            for k in [k for k, f in flows.items() if now - f.seen > 120]:
                del flows[k]
            last_gc = now
        if len(pkt) < 20 or pkt[0] >> 4 != 4:
            os.write(fout, pkt)
            continue
        ihl = (pkt[0] & 15) * 4
        ttl, proto = pkt[8], pkt[9]
        src, dst = ipaddress.ip_address(pkt[12:16]), ipaddress.ip_address(pkt[16:20])
        l4 = pkt[ihl:]
        if proto == 17 and len(l4) >= 8:
            if any(dst in n for n in ipb) and len(l4) > 8:
                continue
        if proto != 6 or len(l4) < 20:
            os.write(fout, pkt)
            continue
        sport, dport, seq = struct.unpack(">HHI", l4[:8])
        doff = (l4[12] >> 4) * 4
        flags = l4[13]
        urgp = struct.unpack(">H", l4[18:20])[0]
        payload = l4[doff:]
        to_srv = dport in (443, 80)
        key = (src, sport, dst, dport) if to_srv else (dst, dport, src, sport)
        if to_srv and payload and any(dst in n for n in ipb):
            continue  # блокировка по адресу: данные не доходят
        if not to_srv and payload and any(src in n for n in ipb):
            continue
        f = flows.get(key)
        if to_srv and flags & 0x02:  # SYN
            f = flows[key] = Flow(seq)
        if f is None:
            os.write(fout, pkt)
            continue
        f.seen = now
        if f.state == "block":
            continue
        if to_srv:
            if payload and f.state == "open":
                urg_pos = seq + urgp - 1 if flags & 0x20 and urgp else None
                f.add(seq, payload, urg_pos)
                data, end = f.stream(prof["urg"])
                kind, host = inspect(data, prof["tls"])
                if kind == "host" or kind == "pass" or end >= MAX_STREAM:
                    f.host = host
                    if host and match(host, prof["sni"]):
                        f.state = "block"
                        log(f"блок {dst}:{dport} {host}")
                        continue
                    f.state = "pass"
                    log(f"пропуск {dst}:{dport} {host or '-'}")
            if payload and ttl <= SERVER_HOPS:
                continue  # до сервера не дойдёт (фейк с малым TTL), но DPI его уже увидел
        elif payload and f.host and f.host in prof["freeze"]:
            f.srv_bytes += len(payload)
            if f.srv_bytes > prof["freeze"][f.host]:
                continue
        os.write(fout, pkt)


if __name__ == "__main__":
    main()
