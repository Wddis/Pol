package com.example.vpnapp;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Проверка сети мимо VPN (само приложение исключено из VPN). Вызывать из фонового потока.
 * <p>
 * 1) Напрямую ({@link #run}): открываются ли зарубежные и российские сайты. Если российские
 * открываются, а зарубежные нет — у оператора, скорее всего, «белые списки», и обойти их не сможет
 * ни Psiphon, ни Tor. Этот же результат — эталон «обычного интернета» для обхода DPI.
 * <p>
 * 2) Через SOCKS обхода DPI ({@link #bypassQuality}) — так же, как пойдёт трафик телефона:
 * DNS через 1.1.1.1 (такой DNS у телефона при обходе DPI), соединение по IP через SOCKS
 * (как tun2socks), TLS с SNI, запрос и чтение ответа. Ответа на рукопожатие мало: нужно получить
 * {@link #ENOUGH_BYTES} данных или весь ответ, иначе «замороженное» соединение сошло бы за рабочее.
 * Проверяются заблокированные в РФ сайты (соцсети) и обычные — зарубежные и российские.
 * Заодно — DNS так, как его делает телефон: UDP-запрос к 1.1.1.1 через SOCKS5 UDP ASSOCIATE
 * того же ByeDPI. Без него у телефона нет интернета, хотя сайты по IP открываются.
 * <p>
 * 3) Сторож после подключения ({@link #bypassHealth}): тот же DNS через SOCKS и один сайт.
 */
final class NetProbe {

    enum Result {
        /** Зарубежные сайты открываются. */
        OK,
        /** Российские открываются, зарубежные нет: похоже на «белые списки». */
        WHITELIST,
        /** Не открывается ничего. */
        OFFLINE
    }

    /** Итог прямой проверки. */
    static final class Direct {
        final Result result;
        final BypassSearch.Score score;

        Direct(Result result, BypassSearch.Score score) {
            this.result = result;
            this.score = score;
        }
    }

    private static final class Site {
        final String name;
        final String host;
        final String path;

        Site(String name, String host, String path) {
            this.name = name;
            this.host = host;
            this.path = path;
        }
    }

    // Сайты, заблокированные в РФ по DPI/имени: напрямую не открываются, а через рабочий
    // обход DPI отвечают. По ним видно, что стратегия реально бьёт фильтр. Соцсети — первыми.
    private static final Site[] BLOCKED = {
            new Site("instagram", "www.instagram.com", "/"),
            new Site("facebook", "www.facebook.com", "/"),
            new Site("x", "x.com", "/"),
            new Site("rutracker", "rutracker.org", "/"),
    };
    private static final Site[] FOREIGN = {
            new Site("google", "www.google.com", "/generate_204"),
            new Site("cloudflare", "www.cloudflare.com", "/cdn-cgi/trace"),
            new Site("wikipedia", "www.wikipedia.org", "/"),
    };
    // Через обход DPI идёт ВЕСЬ трафик телефона, поэтому российские сайты проверяем и через него
    private static final Site[] LOCAL = {
            new Site("ya", "ya.ru", "/"),
            new Site("vk", "vk.com", "/"),
            new Site("gosuslugi", "www.gosuslugi.ru", "/"),
    };

    private static final int CONNECT_TIMEOUT_MS = 6_000;
    // С запасом на цепочки: --auto -T 4 в ядре Android 4.x срабатывает на ближайшем повторе
    // пакета после 4 с (фактически 4–7 с), затем переподключение через сильную стратегию
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final long SITE_DEADLINE_MS = 20_000;
    // Больше ~16 КБ, после которых ТСПУ «замораживает» часть соединений к зарубежным хостингам
    static final int ENOUGH_BYTES = 20 * 1024;
    private static final String DNS_SERVER = "1.1.1.1";
    private static final int DNS_TIMEOUT_MS = 2_500;
    private static final long DNS_WAIT_MS = 12_000;
    private static final String UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";

    // Можно подменить в тестах на компьютере
    static SSLSocketFactory tlsFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
    static HostnameVerifier hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

    private static final ConcurrentHashMap<String, FutureTask<InetAddress>> DNS_CACHE =
            new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private NetProbe() {
    }

    /** Забыть адреса сайтов: новое подключение, сеть могла смениться. */
    static void resetDns() {
        DNS_CACHE.clear();
    }

    /** Прямая проверка (без VPN): «белые списки» и эталон обычного интернета. */
    static Direct run() {
        BypassSearch.Score s = probe(0);
        DiagLog.add("Сеть напрямую: зарубежные сайты " + s.foreign + "/" + FOREIGN.length
                + ", российские " + s.local + "/" + LOCAL.length
                + ", заблокированные " + s.blocked + "/" + s.blockedTotal + " · " + s.detail);
        DiagLog.add("Адреса заблокированных сайтов (DNS " + DNS_SERVER + ", если не ответил — системный): "
                + addresses(BLOCKED));
        Result r = s.foreign > 0 ? Result.OK : s.local > 0 ? Result.WHITELIST : Result.OFFLINE;
        return new Direct(r, s);
    }

    /** Результат стратегии, которая не заработала (процесс ByeDPI не запустился или упал). */
    static BypassSearch.Score nothing() {
        return new BypassSearch.Score(0, BLOCKED.length, 0, 0, FOREIGN.length + LOCAL.length,
                "ByeDPI не работает");
    }

    /** Проверка стратегии обхода DPI через её локальный SOCKS. */
    static BypassSearch.Score bypassQuality(int socksPort) {
        return probe(socksPort);
    }

    /** Итог проверки работающего обхода DPI (сторож подключения). */
    static final class Health {
        final boolean dns;      // DNS через SOCKS (UDP), как у телефона
        final boolean site;     // сайт через SOCKS
        final boolean direct;   // тот же сайт напрямую (проверяется, только если через SOCKS не вышло)

        Health(boolean dns, boolean site, boolean direct) {
            this.dns = dns;
            this.site = site;
            this.direct = direct;
        }

        boolean ok() {
            return dns && site;
        }
    }

    /**
     * Быстрая проверка подключённого обхода DPI: DNS через SOCKS-UDP и сайт через SOCKS
     * (generate_204 — ответ без тела, почти без трафика). Если не вышло — тот же сайт напрямую:
     * так видно, сломан ли ByeDPI или пропала сама сеть.
     */
    static Health bypassHealth(int socksPort) {
        Site site = FOREIGN[0];
        boolean dns = socksDnsWorks(socksPort, LOCAL[0].host);
        InetAddress ip = resolve(site.host);
        boolean ok = ip != null && fetch(site.host, site.path, ip, 443, socksPort);
        boolean direct = !ok && ip != null && fetch(site.host, site.path, ip, 443, 0);
        return new Health(dns, ok, direct);
    }

    /** Все сайты одновременно: на проверку уходит один таймаут, а не сумма. 0 — напрямую. */
    private static BypassSearch.Score probe(int socksPort) {
        List<Site> all = new ArrayList<>();
        all.addAll(Arrays.asList(BLOCKED));
        all.addAll(Arrays.asList(FOREIGN));
        all.addAll(Arrays.asList(LOCAL));
        ExecutorService pool = Executors.newFixedThreadPool(all.size() + 1);
        try {
            // DNS телефона идёт через тот же SOCKS (UDP): проверяем одновременно с сайтами
            Future<Boolean> dnsFuture = socksPort > 0
                    ? pool.submit(() -> socksDnsWorks(socksPort, LOCAL[0].host)) : null;
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Site site : all) futures.add(pool.submit(() -> fetch(site, socksPort)));
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(DNS_WAIT_MS + SITE_DEADLINE_MS + 2_000);
            boolean[] ok = new boolean[all.size()];
            for (int i = 0; i < all.size(); i++) {
                try {
                    long left = Math.max(1, deadline - System.nanoTime());
                    ok[i] = futures.get(i).get(left, TimeUnit.NANOSECONDS);
                } catch (Exception ignored) {
                    // не ответил вовремя — недоступен
                }
            }
            int blocked = count(ok, 0, BLOCKED.length);
            int foreign = count(ok, BLOCKED.length, FOREIGN.length);
            int local = count(ok, BLOCKED.length + FOREIGN.length, LOCAL.length);
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                if (i == BLOCKED.length || i == BLOCKED.length + FOREIGN.length) detail.append(" |");
                if (i > 0) detail.append(' ');
                detail.append(all.get(i).name).append(ok[i] ? '+' : '-');
            }
            if (dnsFuture != null && !futureTrue(dnsFuture, deadline)) {
                // Сайты по IP открываются, но имена у телефона не определятся — интернета не будет
                return new BypassSearch.Score(0, BLOCKED.length, 0, 0, FOREIGN.length + LOCAL.length,
                        "DNS через обход (UDP " + DNS_SERVER + ") не отвечает · " + detail);
            }
            return new BypassSearch.Score(blocked, BLOCKED.length, foreign, local,
                    FOREIGN.length + LOCAL.length, detail.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean futureTrue(Future<Boolean> f, long deadlineNanos) {
        try {
            long left = Math.max(1, deadlineNanos - System.nanoTime());
            return f.get(left, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static int count(boolean[] ok, int from, int n) {
        int c = 0;
        for (int i = from; i < from + n; i++) {
            if (ok[i]) c++;
        }
        return c;
    }

    private static boolean fetch(Site site, int socksPort) {
        InetAddress ip = resolve(site.host);
        return ip != null && fetch(site.host, site.path, ip, 443, socksPort);
    }

    /**
     * Один запрос, как у браузера в VPN: соединение по IP (через SOCKS, как tun2socks, или
     * напрямую при socksPort 0), TLS с SNI и проверкой сертификата, GET, чтение ответа.
     */
    static boolean fetch(String host, String path, InetAddress ip, int port, int socksPort) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SITE_DEADLINE_MS);
        Socket raw = new Socket();
        try {
            raw.setSoTimeout(READ_TIMEOUT_MS);
            raw.setTcpNoDelay(true);
            if (socksPort > 0) {
                raw.connect(new InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS);
                socks5Connect(raw, ip, port);
            } else {
                raw.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            }
            SSLSocket ssl = (SSLSocket) tlsFactory.createSocket(raw, host, port, true);
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(Collections.singletonList(new SNIHostName(host)));
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            if (!hostnameVerifier.verify(host, ssl.getSession())) return false;
            OutputStream out = ssl.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: " + UA
                    + "\r\nAccept: text/html,*/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = ssl.getInputStream();
            byte[] buf = new byte[8192];
            byte[] head = new byte[5];
            int headLen = 0;
            int total = 0;
            while (total < ENOUGH_BYTES) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) return false;  // данные идут слишком медленно или застряли
                ssl.setSoTimeout((int) Math.min(READ_TIMEOUT_MS, left));
                int n = in.read(buf);
                if (n < 0) break;  // весь ответ получен
                for (int i = 0; i < n && headLen < head.length; i++) head[headLen++] = buf[i];
                total += n;
            }
            return headLen == head.length
                    && "HTTP/".equals(new String(head, StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            try {
                raw.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
        }
    }

    /** SOCKS5 CONNECT по IP — так же, как к ByeDPI подключается tun2socks. */
    private static void socks5Connect(Socket s, InetAddress ip, int port) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        out.write(new byte[]{5, 1, 0});
        out.flush();
        byte[] auth = readFully(in, 2);
        if (auth[0] != 5 || auth[1] != 0) throw new IOException("SOCKS: нет доступа");
        byte[] addr = ip.getAddress();
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(5);
        req.write(1);
        req.write(0);
        req.write(addr.length == 4 ? 1 : 4);
        req.write(addr, 0, addr.length);
        req.write(port >> 8);
        req.write(port & 0xff);
        out.write(req.toByteArray());
        out.flush();
        byte[] head = readFully(in, 4);
        if (head[0] != 5 || head[1] != 0) throw new IOException("SOCKS: отказ " + head[1]);
        int len;
        if (head[3] == 1) {
            len = 4;
        } else if (head[3] == 4) {
            len = 16;
        } else if (head[3] == 3) {
            len = readFully(in, 1)[0] & 0xff;
        } else {
            throw new IOException("SOCKS: адрес " + head[3]);
        }
        readFully(in, len + 2);
    }

    // ---------- DNS через SOCKS5 UDP ASSOCIATE: так резолвит имена телефон при обходе DPI ----------

    /** Отвечает ли DNS (1.1.1.1) через SOCKS-UDP: две попытки, как у резолвера Android. */
    static boolean socksDnsWorks(int socksPort, String host) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socksQueryA(socksPort, host,
                        InetAddress.getByAddress(new byte[]{1, 1, 1, 1})) != null) return true;
            } catch (IOException ignored) {
                // повтор
            }
        }
        return false;
    }

    /**
     * A-запрос через SOCKS5 UDP ASSOCIATE (RFC 1928, раздел 7) — так же, как DNS телефона
     * передаёт tun2socks: управляющее TCP-соединение, затем UDP-пакет с заголовком SOCKS.
     */
    @Nullable
    static InetAddress socksQueryA(int socksPort, String host, InetAddress server) throws IOException {
        try (Socket ctl = new Socket(); DatagramSocket udp = new DatagramSocket()) {
            ctl.setSoTimeout(DNS_TIMEOUT_MS);
            ctl.connect(new InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS);
            OutputStream out = ctl.getOutputStream();
            InputStream in = ctl.getInputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            byte[] auth = readFully(in, 2);
            if (auth[0] != 5 || auth[1] != 0) throw new IOException("SOCKS: нет доступа");
            // Адрес клиента неизвестен заранее: 0.0.0.0:0, как у tun2socks
            out.write(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            byte[] head = readFully(in, 4);
            if (head[0] != 5 || head[1] != 0) throw new IOException("SOCKS UDP: отказ " + head[1]);
            byte[] relayAddr;
            if (head[3] == 1) {
                relayAddr = readFully(in, 4);
            } else if (head[3] == 4) {
                relayAddr = readFully(in, 16);
            } else {
                throw new IOException("SOCKS UDP: адрес " + head[3]);
            }
            byte[] p = readFully(in, 2);
            int relayPort = ((p[0] & 0xff) << 8) | (p[1] & 0xff);
            InetAddress relay = InetAddress.getByAddress(relayAddr);
            if (relay.isAnyLocalAddress()) relay = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});

            int id = RANDOM.nextInt(0x10000);
            byte[] query = buildQuery(id, host);
            byte[] dst = server.getAddress();
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0);
            pkt.write(0);
            pkt.write(0);  // без фрагментации
            pkt.write(dst.length == 4 ? 1 : 4);
            pkt.write(dst, 0, dst.length);
            pkt.write(0);
            pkt.write(53);
            pkt.write(query, 0, query.length);
            byte[] data = pkt.toByteArray();
            udp.setSoTimeout(DNS_TIMEOUT_MS);
            udp.send(new DatagramPacket(data, data.length, relay, relayPort));
            byte[] buf = new byte[1500];
            while (true) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                udp.receive(resp);  // по таймауту — SocketTimeoutException
                int n = resp.getLength();
                if (n < 10 || buf[2] != 0) continue;
                int off = buf[3] == 1 ? 10 : buf[3] == 4 ? 22 : -1;
                if (off < 0 || n < off + 12) continue;
                byte[] dns = Arrays.copyOfRange(buf, off, n);
                if ((((dns[0] & 0xff) << 8) | (dns[1] & 0xff)) != id) continue;
                return parseA(host, dns, dns.length);
            }
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("SOCKS: соединение закрыто");
            off += r;
        }
        return b;
    }

    // ---------- DNS: как у телефона при обходе DPI (1.1.1.1), один раз на подключение ----------

    @Nullable
    private static InetAddress resolve(String host) {
        FutureTask<InetAddress> task = new FutureTask<>(() -> lookup(host));
        FutureTask<InetAddress> prev = DNS_CACHE.putIfAbsent(host, task);
        if (prev == null) {
            task.run();
            prev = task;
        }
        try {
            InetAddress a = prev.get(DNS_WAIT_MS, TimeUnit.MILLISECONDS);
            if (a == null) DNS_CACHE.remove(host, prev);
            return a;
        } catch (Exception e) {
            DNS_CACHE.remove(host, prev);
            return null;
        }
    }

    /** Адреса сайтов для журнала (уже из кэша). */
    private static String addresses(Site[] sites) {
        StringBuilder sb = new StringBuilder();
        for (Site site : sites) {
            if (sb.length() > 0) sb.append(", ");
            FutureTask<InetAddress> t = DNS_CACHE.get(site.host);
            InetAddress a = null;
            try {
                if (t != null && t.isDone()) a = t.get();
            } catch (Exception ignored) {
                // не определился
            }
            sb.append(site.name).append(' ').append(a != null ? a.getHostAddress() : "нет");
        }
        return sb.toString();
    }

    @Nullable
    private static InetAddress lookup(String host) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                InetAddress a = queryA(host, InetAddress.getByAddress(new byte[]{1, 1, 1, 1}));
                if (a != null) return a;
            } catch (IOException ignored) {
                // повтор, затем системный DNS
            }
        }
        // 1.1.1.1 не ответил — системный DNS
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet4Address) return a;
            }
        } catch (IOException | RuntimeException ignored) {
            // имя не определилось
        }
        return null;
    }

    /** Запрос A-записи по UDP. Возвращает первый IPv4-адрес из ответа или null. */
    @Nullable
    static InetAddress queryA(String host, InetAddress server) throws IOException {
        int id = RANDOM.nextInt(0x10000);
        byte[] query = buildQuery(id, host);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(DNS_TIMEOUT_MS);
            sock.send(new DatagramPacket(query, query.length, server, 53));
            byte[] buf = new byte[1500];
            while (true) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                sock.receive(resp);  // по таймауту — SocketTimeoutException
                int n = resp.getLength();
                if (n < 12 || (((buf[0] & 0xff) << 8) | (buf[1] & 0xff)) != id) continue;
                return parseA(host, buf, n);
            }
        }
    }

    static byte[] buildQuery(int id, String host) {
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        q.write(id >> 8);
        q.write(id & 0xff);
        q.write(0x01);  // рекурсивный запрос
        q.write(0x00);
        q.write(0);
        q.write(1);  // один вопрос
        for (int i = 0; i < 6; i++) q.write(0);
        for (String label : host.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            q.write(b.length);
            q.write(b, 0, b.length);
        }
        q.write(0);
        q.write(0);
        q.write(1);  // A
        q.write(0);
        q.write(1);  // IN
        return q.toByteArray();
    }

    @Nullable
    static InetAddress parseA(String host, byte[] b, int n) throws IOException {
        int flags = u16(b, 2);
        if ((flags & 0x8000) == 0 || (flags & 0x000f) != 0) return null;  // не ответ или ошибка
        int questions = u16(b, 4);
        int answers = u16(b, 6);
        int p = 12;
        for (int i = 0; i < questions; i++) {
            p = skipName(b, p, n);
            if (p < 0) return null;
            p += 4;
        }
        for (int i = 0; i < answers; i++) {
            p = skipName(b, p, n);
            if (p < 0 || p + 10 > n) return null;
            int type = u16(b, p);
            int cls = u16(b, p + 2);
            int len = u16(b, p + 8);
            p += 10;
            if (p + len > n) return null;
            if (type == 1 && cls == 1 && len == 4) {
                return InetAddress.getByAddress(host, Arrays.copyOfRange(b, p, p + 4));
            }
            p += len;  // CNAME и прочее
        }
        return null;
    }

    private static int skipName(byte[] b, int p, int n) {
        while (p < n) {
            int len = b[p] & 0xff;
            if (len == 0) return p + 1;
            if ((len & 0xc0) == 0xc0) return p + 2;  // ссылка на имя выше
            p += len + 1;
        }
        return -1;
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }
}
