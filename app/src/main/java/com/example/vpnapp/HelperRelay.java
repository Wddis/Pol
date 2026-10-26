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
 * пересыльщик байтов: пока путь закрыт (идёт выбор стратегии или Psiphon ещё не готов),
 * он сразу сбрасывает соединение, и ByeDPI идёт следующей группой; открыт — пересылает
 * соединение как есть в SOCKS Psiphon (сам SOCKS не разбирает).
 */
final class HelperRelay {

    static final int PORT = 10808;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
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
            remote.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), up),
                    CONNECT_TIMEOUT_MS);
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
