package com.example.vpnapp;

import java.io.FileInputStream;
import java.io.InputStream;
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
 * <li>{@code byedpi <порт>} — аргументы процесса ByeDPI и стратегия-цепочка, выбранная
 * в журнале 0.8.5 («split SNI → disoob SNI»), одной строкой;</li>
 * <li>{@code probe <порт> <ca.pem>} — NetProbe.bypassQuality и DNS через SOCKS-UDP;
 * код выхода 0, если DNS отвечает и все обычные сайты открылись.</li>
 * </ul>
 */
public final class TunLab {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[1]);
        switch (args[0]) {
            case "config":
                System.out.print(Tun2SocksConfig.yaml(port, true));
                return;
            case "byedpi": {
                BypassProfile chain = BypassProfile.chain(BypassProfile.MILD.get(0), BypassProfile.STRONG.get(1));
                System.out.println(String.join(" ", BypassProfile.processArgs(port)) + " " + chain.argsLine());
                return;
            }
            case "probe":
                System.exit(probe(port, args[2]) ? 0 : 1);
                return;
            default:
                throw new IllegalArgumentException(args[0]);
        }
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
