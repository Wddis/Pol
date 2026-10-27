package com.example.vpnapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLSocket;

/**
 * Тест скорости ODA (кнопка «ТЕСТ СКОРОСТИ» после подключения). Без Android: его же запускает стенд.
 * <p>
 * Идёт через SOCKS того способа, которым подключены (ByeDPI, Psiphon или Tor), — тем же путём, что
 * трафик телефона после tun2socks. Само приложение исключено из VPN, поэтому напрямую «через VPN» его
 * запросы не пошли бы. Имя сайта передаётся в SOCKS как есть (DNS — на стороне способа, как у телефона).
 * <ul>
 * <li>Пинг — медиана времени SOCKS CONNECT к {@link #pingHost}:443 из {@link #PINGS} попыток:
 * способ отвечает, когда соединение с сайтом установлено, — это время до сайта и обратно.</li>
 * <li>Загрузка — файл с {@link #downloadHost} по HTTPS не дольше {@link #DOWNLOAD_MS} мс
 * (до {@link #DOWNLOAD_BYTES} байт), скорость считается от первого байта ответа.</li>
 * </ul>
 */
final class SpeedTest {

    static final int PINGS = 5;
    static final long DOWNLOAD_MS = 7_000;
    static final int DOWNLOAD_BYTES = 25_000_000;
    private static final int TIMEOUT_MS = 10_000;

    // Не final: стенд подставляет свои адреса (TunLab)
    static String pingHost = "www.google.com";
    static String downloadHost = "speed.cloudflare.com";
    static String downloadPath = "/__down?bytes=" + DOWNLOAD_BYTES;

    static final class Result {
        /** Медиана пинга, мс; -1 — ни одна попытка не прошла. */
        final long pingMs;
        /** Скорость загрузки, Мбит/с; -1 — загрузка не прошла. */
        final double mbps;
        final long bytes;
        /** Что не прошло (для журнала), или null. */
        final String error;

        Result(long pingMs, double mbps, long bytes, String error) {
            this.pingMs = pingMs;
            this.mbps = mbps;
            this.bytes = bytes;
            this.error = error;
        }

        /** «Пинг 48 мс · загрузка 23,4 Мбит/с». */
        String summary() {
            String ping = pingMs >= 0 ? pingMs + " мс" : "—";
            String speed = mbps >= 0 ? String.format(Locale.forLanguageTag("ru"), "%.1f Мбит/с", mbps) : "—";
            return "Пинг " + ping + " · загрузка " + speed;
        }
    }

    private SpeedTest() {
    }

    static Result run(int socksPort) {
        List<Long> pings = new ArrayList<>();
        String error = null;
        for (int i = 0; i < PINGS; i++) {
            long t0 = System.nanoTime();
            try (Socket s = socks(socksPort, pingHost, 443)) {
                pings.add((System.nanoTime() - t0) / 1_000_000);
            } catch (IOException e) {
                error = "пинг: " + e.getMessage();
            }
        }
        long ping = -1;
        if (!pings.isEmpty()) {
            Collections.sort(pings);
            ping = pings.get(pings.size() / 2);
        }
        double mbps = -1;
        long bytes = 0;
        try {
            long[] r = download(socksPort);
            bytes = r[0];
            if (r[1] > 0) mbps = r[0] * 8.0 / 1000.0 / r[1];  // байт·8 / (мс·1000) = Мбит/с
        } catch (IOException e) {
            error = "загрузка: " + e.getMessage();
        }
        return new Result(ping, mbps, bytes, error);
    }

    /** {байт, мс от первого байта ответа}. */
    private static long[] download(int socksPort) throws IOException {
        try (Socket raw = socks(socksPort, downloadHost, 443)) {
            SSLSocket ssl = (SSLSocket) NetProbe.tlsFactory.createSocket(raw, downloadHost, 443, true);
            ssl.startHandshake();
            if (!NetProbe.hostnameVerifier.verify(downloadHost, ssl.getSession())) {
                throw new IOException("чужой сертификат");
            }
            OutputStream out = ssl.getOutputStream();
            out.write(("GET " + downloadPath + " HTTP/1.1\r\nHost: " + downloadHost
                    + "\r\nUser-Agent: ODA\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = ssl.getInputStream();
            byte[] buf = new byte[64 * 1024];
            int n = in.read(buf);
            if (n <= 0) throw new IOException("пустой ответ");
            String status = new String(buf, 0, Math.min(n, 12), StandardCharsets.US_ASCII);
            if (!status.matches("HTTP/1\\.[01] 200")) throw new IOException("ответ " + status.trim());
            long start = System.nanoTime();
            long total = n;
            long deadline = start + DOWNLOAD_MS * 1_000_000;
            while (System.nanoTime() < deadline && (n = in.read(buf)) > 0) total += n;
            long ms = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            return new long[]{total, ms};
        }
    }

    /** SOCKS5 CONNECT по имени (ATYP 3): имя разрешает способ — как у трафика телефона. */
    private static Socket socks(int port, String host, int dport) throws IOException {
        Socket s = new Socket();
        boolean ok = false;
        try {
            s.setSoTimeout(TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[]{5, 1, 0});
            out.write(req.toByteArray());
            out.flush();
            byte[] auth = read(in, 2);
            if (auth[0] != 5 || auth[1] != 0) throw new IOException("SOCKS: нет доступа");
            req.reset();
            req.write(new byte[]{5, 1, 0, 3, (byte) name.length});
            req.write(name);
            req.write(dport >> 8);
            req.write(dport & 0xff);
            out.write(req.toByteArray());
            out.flush();
            byte[] head = read(in, 4);
            if (head[1] != 0) throw new IOException("SOCKS: отказ " + head[1]);
            int skip = head[3] == 1 ? 4 : head[3] == 4 ? 16 : read(in, 1)[0] & 0xff;
            read(in, skip + 2);
            ok = true;
            return s;
        } finally {
            if (!ok) s.close();
        }
    }

    private static byte[] read(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("SOCKS: соединение закрыто");
            off += r;
        }
        return b;
    }
}
