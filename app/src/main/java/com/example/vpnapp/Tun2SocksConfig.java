package com.example.vpnapp;

/**
 * Конфиг tun2socks (hev-socks5-tunnel) и адреса VPN-интерфейса. Без Android: тот же конфиг
 * проверяет стенд scripts/test-tun.sh (TUN → hev → ByeDPI на компьютере).
 */
final class Tun2SocksConfig {

    static final String TUN_IPV4 = "198.18.0.1";
    static final String TUN_IPV6 = "fc00::1";
    // DNS для Tor/Psiphon: фиктивный адрес, запросы перехватывает mapdns, имена резолвит выход
    static final String FAKE_DNS = "198.18.0.2";
    // DNS для обхода DPI: реальный резолвер, запросы идут через SOCKS (UDP) напрямую, как в ByeDPIAndroid
    static final String DIRECT_DNS = "1.1.1.1";
    static final String DIRECT_DNS6 = "2606:4700:4700::1111";
    static final int MTU = 8500;

    /**
     * Обход DPI: через сколько мс без данных hev закрывает UDP-сессию (по умолчанию 60 с).
     * Каждый DNS-запрос телефона идёт с нового порта — это своя SOCKS-UDP-ассоциация в ByeDPI
     * (3 соединения из его пула). За 60 с их набиралось больше пула, и ByeDPI переставал
     * принимать любые соединения: «подключено», а интернета нет (0.8.5). Активные потоки
     * (QUIC, звонки) обмениваются данными чаще, их это не трогает.
     */
    static final int DIRECT_UDP_TIMEOUT_MS = 20_000;

    private Tun2SocksConfig() {
    }

    /**
     * @param directDns true для обхода DPI: без mapdns, DNS идёт через SOCKS напрямую
     *                  (SOCKS сам резолвит имена/получает IP); false для Tor/Psiphon: mapdns
     */
    static String yaml(int socksPort, boolean directDns) {
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
                .append("  connect-timeout: 30000\n");
        if (directDns) yml.append("  udp-read-write-timeout: ").append(DIRECT_UDP_TIMEOUT_MS).append('\n');
        yml.append("  log-level: warn\n");
        return yml.toString();
    }
}
