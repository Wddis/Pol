package com.example.vpnapp;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Помощник стенда scripts/test-tun.sh: отдаёт то, что приложение передаёт tun2socks и ByeDPI,
 * и запускает настоящую проверку NetProbe через SOCKS ByeDPI на стенде.
 * <ul>
 * <li>{@code config <порт>} — конфиг hev-socks5-tunnel для обхода DPI (Tun2SocksConfig);</li>
 * <li>{@code byedpi <порт> <порт запасного пути|0> <сильная>} — аргументы процесса ByeDPI
 * и маршрут цепочки «split SNI → сильная» (BypassProfile.route), по одному в строке;</li>
 * <li>{@code old <порт> <сильная>} — как запускался ByeDPI в 0.8.5–0.8.6: без -c, без списка
 * доменов и групп, цепочка одной группой «после сбоя» (для контрольных прогонов);</li>
 * <li>{@code probe <порт> <ca.pem>} — NetProbe.bypassQuality и DNS через SOCKS-UDP;
 * код выхода 0, если DNS отвечает и все обычные сайты открылись;</li>
 * <li>{@code relay <порт> <порт SOCKS>} — настоящий HelperRelay (запасной путь), путь открыт;
 * порт SOCKS 0 — путь закрыт (как до подключения или пока Psiphon не готов);</li>
 * <li>{@code stats <журнал ByeDPI> <порт запасного пути|0> <сильная>} — сводка ByeDpiStats
 * по выводу ByeDPI (как в журнале приложения);</li>
 * <li>{@code why <ip>} — NetProbe.whyUnreachable: по адресу режут или по имени (DPI).</li>
 * </ul>
 */
public final class TunLab {

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "config":
                System.out.print(Tun2SocksConfig.yaml(Integer.parseInt(args[1]), true));
                return;
            case "byedpi": {
                List<String> a = new ArrayList<>(BypassProfile.processArgs(Integer.parseInt(args[1])));
                a.addAll(BypassProfile.route(chain(args[3]), Integer.parseInt(args[2])).args);
                for (String s : a) System.out.println(s);
                return;
            }
            case "old": {
                List<String> a = new ArrayList<>(java.util.Arrays.asList("-i", "127.0.0.1", "-p", args[1]));
                a.addAll(java.util.Arrays.asList(chain(args[2]).args));
                for (String s : a) System.out.println(s);
                return;
            }
            case "probe":
                System.exit(probe(Integer.parseInt(args[1]), args[2]) ? 0 : 1);
                return;
            case "relay": {
                HelperRelay r = new HelperRelay(Integer.parseInt(args[1]));
                if (!r.start()) throw new IllegalStateException("порт занят");
                r.setUpstream(Integer.parseInt(args[2]));
                System.out.println("relay: готов");
                Thread.sleep(Long.MAX_VALUE);
                return;
            }
            case "stats": {
                BypassProfile.Route route = BypassProfile.route(chain(args[3]), Integer.parseInt(args[2]));
                ByeDpiStats st = new ByeDpiStats(route.groups);
                int errors = 0;
                for (String line : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
                    if (!st.feed(line)) errors++;
                }
                List<String> unreach = st.takeNewUnreachable(100);
                System.out.println("ByeDPI: " + st.drain());
                System.out.println("строк ошибок (в журнал): " + errors);
                for (String ip : unreach) System.out.println("не открылся: " + st.who(ip));
                return;
            }
            case "why":
                System.out.println(NetProbe.whyUnreachable(args[1], 443));
                return;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }

    /** Цепочка «split SNI → сильная» по названию сильной (как её строит BypassSearch). */
    private static BypassProfile chain(String strong) {
        for (BypassProfile s : BypassProfile.STRONG) {
            if (s.label.equals(strong)) return BypassProfile.chain(BypassProfile.MILD.get(0), s);
        }
        throw new IllegalArgumentException("нет сильной стратегии «" + strong + "»");
    }

    private static boolean probe(int port, String caFile) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = new FileInputStream(caFile)) {
            ks.setCertificateEntry("lab", (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        NetProbe.tlsFactory = ctx.getSocketFactory();
        // Проверка имени по сертификату: на Android её делает системный HostnameVerifier,
        // а в настольной JDK он по умолчанию отвергает всё
        NetProbe.hostnameVerifier = (host, session) -> {
            try {
                Collection<List<?>> names = ((X509Certificate) session.getPeerCertificates()[0])
                        .getSubjectAlternativeNames();
                if (names == null) return false;
                for (List<?> n : names) {
                    if (Integer.valueOf(2).equals(n.get(0)) && host.equalsIgnoreCase((String) n.get(1))) return true;
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        };

        boolean dns = NetProbe.socksDnsWorks(port, "ya.ru");
        System.out.println("  DNS через SOCKS-UDP ByeDPI: " + (dns ? "отвечает" : "НЕ ОТВЕЧАЕТ"));
        BypassSearch.Score s = NetProbe.bypassQuality(port);
        System.out.println("  NetProbe: " + s.summary() + " · " + s.detail);
        NetProbe.Health h = NetProbe.bypassHealth(port);
        System.out.println("  Сторож: DNS " + (h.dns ? "+" : "-") + ", сайт " + (h.site ? "+" : "-"));
        return dns && h.ok() && s.normal() == s.normalTotal;
    }
}
