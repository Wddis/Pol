package com.example.vpnapp;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * tun2socks (hev-socks5-tunnel): пакеты из VPN-интерфейса → SOCKS5 Tor.
 * DNS обрабатывает mapdns: приложение получает «фиктивный» IP, а имя
 * хоста уходит в Tor и резолвится на выходном узле.
 */
final class Tun2Socks {

    static final String TUN_IPV4 = "198.18.0.1";
    static final String TUN_IPV6 = "fc00::1";
    static final String FAKE_DNS = "198.18.0.2";
    static final int MTU = 8500;

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

    static boolean start(Context context, ParcelFileDescriptor tun, int socksPort) {
        try {
            File config = writeConfig(context, socksPort);
            return TProxyStartService(config.getAbsolutePath(), tun.getFd());
        } catch (IOException e) {
            return false;
        }
    }

    static void stop() {
        TProxyStopService();
    }

    private static File writeConfig(Context context, int socksPort) throws IOException {
        // UDP Tor не передаёт: такие пакеты отбрасываются (кроме DNS — его ловит mapdns)
        String yml = "tunnel:\n"
                + "  mtu: " + MTU + "\n"
                + "  ipv4: " + TUN_IPV4 + "\n"
                + "  ipv6: '" + TUN_IPV6 + "'\n"
                + "socks5:\n"
                + "  port: " + socksPort + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "mapdns:\n"
                + "  address: " + FAKE_DNS + "\n"
                + "  port: 53\n"
                + "  network: 240.0.0.0\n"
                + "  netmask: 240.0.0.0\n"
                + "  cache-size: 10000\n"
                + "misc:\n"
                + "  task-stack-size: 81920\n"
                + "  connect-timeout: 30000\n"
                + "  log-level: warn\n";
        File file = new File(context.getFilesDir(), CONFIG_FILE);
        try (Writer w = new FileWriter(file, false)) {
            w.write(yml);
        }
        return file;
    }
}
