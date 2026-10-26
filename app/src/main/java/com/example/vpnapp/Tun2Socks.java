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
    // DNS для Tor/Psiphon: фиктивный адрес, запросы перехватывает mapdns, имена резолвит выход
    static final String FAKE_DNS = "198.18.0.2";
    // DNS для обхода DPI: реальный резолвер, запросы идут через SOCKS (UDP) напрямую, как в ByeDPIAndroid
    static final String DIRECT_DNS = "1.1.1.1";
    static final String DIRECT_DNS6 = "2606:4700:4700::1111";
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

    /**
     * @param directDns true для обхода DPI: без mapdns, DNS идёт через SOCKS напрямую
     *                  (SOCKS сам резолвит имена/получает IP); false для Tor/Psiphon: mapdns
     */
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

    private static File writeConfig(Context context, int socksPort, boolean directDns) throws IOException {
        StringBuilder yml = new StringBuilder()
                .append("tunnel:\n")
                .append("  mtu: ").append(MTU).append('\n')
                .append("  ipv4: ").append(TUN_IPV4).append('\n')
                .append("  ipv6: '").append(TUN_IPV6).append("'\n")
                .append("socks5:\n")
                .append("  port: ").append(socksPort).append('\n')
                .append("  address: 127.0.0.1\n")
                .append("  udp: 'udp'\n");
        // Tor/Psiphon не передают UDP и резолвят удалённо: DNS ловит mapdns.
        // Обход DPI соединяется напрямую: mapdns не нужен, DNS идёт через SOCKS к реальному резолверу.
        if (!directDns) {
            yml.append("mapdns:\n")
                    .append("  address: ").append(FAKE_DNS).append('\n')
                    .append("  port: 53\n")
                    .append("  network: 240.0.0.0\n")
                    .append("  netmask: 240.0.0.0\n")
                    .append("  cache-size: 10000\n");
        }
        yml.append("misc:\n")
                .append("  task-stack-size: 81920\n")
                .append("  connect-timeout: 30000\n")
                .append("  log-level: warn\n");
        File file = new File(context.getFilesDir(), CONFIG_FILE);
        try (Writer w = new FileWriter(file, false)) {
            w.write(yml.toString());
        }
        return file;
    }
}
