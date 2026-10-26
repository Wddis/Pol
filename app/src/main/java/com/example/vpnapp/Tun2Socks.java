package com.example.vpnapp;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * tun2socks (hev-socks5-tunnel): пакеты из VPN-интерфейса → локальный SOCKS5 (Tor, Psiphon, ByeDPI).
 * Для Tor/Psiphon DNS обрабатывает mapdns: приложение получает «фиктивный» IP, а имя
 * хоста уходит в туннель и резолвится на выходе. Конфиг — {@link Tun2SocksConfig}.
 */
final class Tun2Socks {

    private static final String CONFIG_FILE = "tun2socks.yml";

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private Tun2Socks() {
    }

    // Имена и набор методов заданы JNI-кодом hev-socks5-tunnel (нужны все четыре)
    private static native boolean TProxyStartService(String configPath, int fd);

    private static native boolean TProxyStopService();

    private static native boolean TProxyIsRunning();

    private static native long[] TProxyGetStats();

    /** @param directDns см. {@link Tun2SocksConfig#yaml} */
    static boolean start(Context context, ParcelFileDescriptor tun, int socksPort, boolean directDns) {
        try {
            File config = writeConfig(context, socksPort, directDns);
            return TProxyStartService(config.getAbsolutePath(), tun.getFd());
        } catch (IOException e) {
            return false;
        }
    }

    static void stop() {
        TProxyStopService();
    }

    /**
     * Счётчики с запуска: {пакетов от телефона, байт от телефона, пакетов к телефону, байт к телефону}
     * (в hev «tx» — прочитано из VPN-интерфейса, «rx» — записано в него). null, если недоступно.
     */
    @Nullable
    static long[] stats() {
        try {
            long[] s = TProxyGetStats();
            return s != null && s.length >= 4 ? s : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static File writeConfig(Context context, int socksPort, boolean directDns) throws IOException {
        File file = new File(context.getFilesDir(), CONFIG_FILE);
        try (Writer w = new FileWriter(file, false)) {
            w.write(Tun2SocksConfig.yaml(socksPort, directDns));
        }
        return file;
    }
}
