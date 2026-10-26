package com.example.vpnapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Запасной путь обхода DPI: локальный порт, через который ByeDPI отправляет в Psiphon то,
 * что напрямую не открывается (Telegram, адреса, заблокированные по IP). Без Android:
 * его же запускает стенд scripts/test-tun.sh.
 * <p>
 * ByeDPI получает адрес запасного пути при запуске (-C socks5://127.0.0.1:{@link #PORT}),
 * а порт SOCKS у Psiphon каждый раз свой и появляется позже. Поэтому между ними — этот
 * пересыльщик: пока путь закрыт (идёт выбор стратегии или Psiphon ещё не готов),
 * он сразу сбрасывает соединение, и ByeDPI идёт следующей группой; открыт — принимает
 * запрос SOCKS5 от ByeDPI и повторяет его в SOCKS Psiphon, дальше просто пересылает байты.
 * Запрос разбирается ради одного: ByeDPI с IPv6-сокетами (Android) передаёт адрес IPv4
 * как IPv6 вида ::ffff:a.b.c.d — пересыльщик отдаёт его Psiphon обычным IPv4-адресом
 * (так нашла имитация на раннере CI с IPv6: «Psiphon» стенда такой адрес не открывал).
 */
final class HelperRelay {

    static final int PORT = 10808;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int BUF = 16 * 1024;

    private final int port;
    private volatile int upstream;  // порт SOCKS Psiphon, 0 — путь закрыт
    private volatile ServerSocket server;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();

    HelperRelay(int port) {
        this.port = port;
    }

    /** Занять порт и принимать соединения (путь пока закрыт). false — порт занят. */
    synchronized boolean start() {
        if (server != null) return true;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 128);
            server = s;
            Thread t = new Thread(() -> acceptLoop(s), "oda-helper-relay");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Открыть путь в SOCKS на этом порту (Psiphon готов) или закрыть (0). */
    void setUpstream(int socksPort) {
        upstream = socksPort;
    }

    boolean isOpen() {
        return upstream > 0 && server != null;
    }

    synchronized void stop() {
        upstream = 0;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
        }
    }

    /** Сводка для журнала и сброс счётчиков: «соединений N, к телефону M КБ» или "". */
    String drainStats() {
        int n = connections.getAndSet(0);
        long in = bytesIn.getAndSet(0);
        long out = bytesOut.getAndSet(0);
        if (n == 0 && in == 0 && out == 0) return "";
        return "соединений " + n + ", к телефону " + in / 1024 + " КБ, от телефона " + out / 1024 + " КБ";
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                return;  // закрыт
            }
            int up = upstream;
            if (up <= 0) {
                reset(c);
                continue;
            }
            Thread t = new Thread(() -> relay(c, up), "oda-helper-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void relay(Socket client, int up) {
        Socket remote = new Socket();
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            byte[] request = acceptSocks(client);
            remote.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), up),
                    CONNECT_TIMEOUT_MS);
            remote.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            OutputStream out = remote.getOutputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            byte[] auth = readFully(remote.getInputStream(), 2);
            if (auth[0] != 5 || auth[1] != 0) throw new IOException("SOCKS: нет доступа");
            out.write(request);
            out.flush();
            // Ответ Psiphon на CONNECT и дальше данные идут клиенту как есть
            client.setSoTimeout(0);
            remote.setSoTimeout(0);
        } catch (IOException e) {
            reset(client);
            close(remote);
            return;
        }
        connections.incrementAndGet();
        try {
            client.setTcpNoDelay(true);
            remote.setTcpNoDelay(true);
        } catch (IOException ignored) {
            // не важно
        }
        Thread back = new Thread(() -> pump(remote, client, bytesIn), "oda-helper-back");
        back.setDaemon(true);
        back.start();
        pump(client, remote, bytesOut);
        try {
            back.join();
        } catch (InterruptedException ignored) {
            // выходим
        }
        close(client);
        close(remote);
    }

    /**
     * SOCKS5 со стороны ByeDPI: приветствие (без пароля) и запрос CONNECT. Возвращает запрос
     * для Psiphon; адрес ::ffff:a.b.c.d заменён на IPv4 a.b.c.d.
     */
    static byte[] acceptSocks(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        byte[] hello = readFully(in, 2);
        if (hello[0] != 5) throw new IOException("не SOCKS5");
        readFully(in, hello[1] & 0xff);  // способы входа: соглашаемся без пароля
        out.write(new byte[]{5, 0});
        out.flush();
        byte[] head = readFully(in, 4);
        if (head[0] != 5 || head[1] != 1) throw new IOException("не CONNECT");
        byte[] addr;
        switch (head[3]) {
            case 1:
                addr = readFully(in, 4);
                break;
            case 4:
                addr = readFully(in, 16);
                break;
            case 3:
                byte[] len = readFully(in, 1);
                byte[] name = readFully(in, len[0] & 0xff);
                addr = new byte[name.length + 1];
                addr[0] = len[0];
                System.arraycopy(name, 0, addr, 1, name.length);
                break;
            default:
                throw new IOException("SOCKS: адрес " + head[3]);
        }
        byte[] port = readFully(in, 2);
        byte atyp = head[3];
        if (atyp == 4 && isMappedIpv4(addr)) {
            atyp = 1;
            addr = new byte[]{addr[12], addr[13], addr[14], addr[15]};
        }
        byte[] req = new byte[4 + addr.length + 2];
        req[0] = 5;
        req[1] = 1;
        req[2] = 0;
        req[3] = atyp;
        System.arraycopy(addr, 0, req, 4, addr.length);
        req[req.length - 2] = port[0];
        req[req.length - 1] = port[1];
        return req;
    }

    private static boolean isMappedIpv4(byte[] a) {
        for (int i = 0; i < 10; i++) {
            if (a[i] != 0) return false;
        }
        return a[10] == (byte) 0xff && a[11] == (byte) 0xff;
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

    /** Копирует from → to до конца потока; конец передаёт дальше (полузакрытие), ошибку — закрытием. */
    private static void pump(Socket from, Socket to, AtomicLong counter) {
        byte[] buf = new byte[BUF];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                counter.addAndGet(n);
            }
            to.shutdownOutput();
        } catch (IOException e) {
            close(from);
            close(to);
        }
    }

    /** Сброс (RST), а не обычное закрытие: ByeDPI сразу видит отказ и берёт следующую группу. */
    private static void reset(Socket s) {
        try {
            s.setSoLinger(true, 0);
        } catch (IOException ignored) {
            // закроем как есть
        }
        close(s);
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // уже закрыт
        }
    }
}
