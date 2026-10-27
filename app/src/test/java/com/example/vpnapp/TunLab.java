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
 * <li>{@code config <порт> [psiphon]} — конфиг hev-socks5-tunnel для обхода DPI (Tun2SocksConfig);
 * с {@code psiphon} — как при подключении через Psiphon (mapdns);</li>
 * <li>{@code procargs <порт>} — аргументы процесса ByeDPI (как у BypassController), по одному в строке;</li>
 * <li>{@code byedpi <порт> <порт запасного пути|0> <сильная>} — аргументы процесса ByeDPI
 * и маршрут цепочки «split SNI → сильная» (BypassProfile.route), по одному в строке;</li>
 * <li>{@code old <порт> <сильная>} — как запускался ByeDPI в 0.8.5–0.8.6: без -c, без списка
 * доменов и групп, цепочка одной группой «после сбоя» (для контрольных прогонов);</li>
 * <li>{@code probe <порт> <ca.pem>} — NetProbe.bypassQuality и DNS через SOCKS-UDP;
 * код выхода 0, если DNS отвечает и все обычные сайты открылись;</li>
 * <li>{@code relay <порт> <порт SOCKS>} — настоящий HelperRelay (запасной путь), путь открыт;
 * порт SOCKS 0 — путь закрыт (как до подключения или пока Psiphon не готов);</li>
 * <li>{@code stats <журнал ByeDPI> <файл стратегии> <порт запасного пути|0>} — сводка ByeDpiStats
 * по выводу ByeDPI (как в журнале приложения); файл стратегии — file.profile от search;</li>
 * <li>{@code why <ip>} — NetProbe.whyUnreachable: по адресу режут или по имени (DPI);</li>
 * <li>{@code speedtest <порт SOCKS> <ca.pem>} — SpeedTest (кнопка «ТЕСТ СКОРОСТИ»): пинг и загрузка
 * через SOCKS; код выхода 0, если оба измерены;</li>
 * <li>{@code search <ciadpi> <ca.pem> <порт запасного пути|0> <файл>} — выбор стратегии как в
 * приложении: прямая проверка (эталон), затем BypassSearch пачками по 3 — у каждой стратегии свой
 * процесс ByeDPI (аргументы процесса и маршрут BypassProfile.route) и проверка NetProbe; журнал —
 * как у приложения. Выбранная стратегия: маршрут ByeDPI по аргументу в строке — в файл,
 * название — в файл.label, аргументы — в файл.profile, DNS телефона (ответивший через неё сервер
 * из NetProbe.DNS_SERVERS) — в файл.dns. Код выхода 0 — выбрана, 1 — не выбрана.</li>
 * </ul>
 */
public final class TunLab {

    private static final int BASE_PORT = 10809;

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "config":
                // config <порт> [psiphon]: для Psiphon — с mapdns, как у приложения
                System.out.print(Tun2SocksConfig.yaml(Integer.parseInt(args[1]),
                        args.length < 3 || !args[2].equals("psiphon")));
                return;
            case "procargs":
                for (String s : BypassProfile.processArgs(Integer.parseInt(args[1]))) System.out.println(s);
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
                String profileArgs = new String(Files.readAllBytes(Paths.get(args[2])), StandardCharsets.UTF_8).trim();
                BypassProfile.Route route = BypassProfile.route(BypassProfile.parse("lab", profileArgs), Integer.parseInt(args[3]));
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
            case "speedtest": {
                // Кнопка «ТЕСТ СКОРОСТИ»: тот же SpeedTest через SOCKS способа (здесь — ByeDPI)
                setupTls(args[2]);
                SpeedTest.Result r = SpeedTest.run(Integer.parseInt(args[1]));
                System.out.println(r.summary() + " · принято " + r.bytes / 1024 + " КБ"
                        + (r.error != null ? " · не прошло: " + r.error : ""));
                System.exit(r.pingMs >= 0 && r.mbps > 0 && r.error == null ? 0 : 1);
                return;
            }
            case "search":
                setupTls(args[2]);
                System.exit(search(args[1], Integer.parseInt(args[3]), args[4]) ? 0 : 1);
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

    /** Перебор стратегий как в VpnTunnelService (startBypass/decideBypass), без Android. */
    private static boolean search(String exe, int helper, String outFile) throws Exception {
        NetProbe.Direct direct = NetProbe.run();
        BypassSearch s = new BypassSearch(null, null, false, false);
        // DNS телефона по стратегии — как bypassDnsOf в VpnTunnelService
        java.util.Map<BypassProfile, String> dnsOf = new java.util.IdentityHashMap<>();
        List<BypassProfile> batch = s.currentBatch();
        boolean baseline = false;
        while (true) {
            List<Process> procs = new ArrayList<>();
            List<java.util.concurrent.Future<BypassSearch.Score>> scores = new ArrayList<>();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(batch.size());
            try {
                for (int i = 0; i < batch.size(); i++) {
                    BypassProfile p = batch.get(i);
                    int port = BASE_PORT + i;  // как у BypassController
                    BypassProfile.Route route = BypassProfile.route(p, helper);
                    List<String> cmd = new ArrayList<>();
                    cmd.add(exe);
                    cmd.addAll(BypassProfile.processArgs(port));
                    cmd.addAll(route.args);
                    System.out.println("Обход DPI: запуск «" + p.label + "» (порт " + port + "): " + p.argsLine()
                            + (route.groups.size() > 1 ? "; группы: " + route.describe() : ""));
                    procs.add(new ProcessBuilder(cmd).redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
                    awaitPort(port);
                    scores.add(pool.submit(() -> NetProbe.bypassQuality(port)));
                }
                for (int i = 0; i < batch.size(); i++) {
                    BypassSearch.Score q = scores.get(i).get();
                    if (q.dns != null) dnsOf.put(batch.get(i), q.dns);
                    s.report(batch.get(i), q);
                }
            } finally {
                pool.shutdownNow();
                for (Process p : procs) p.destroy();
                for (Process p : procs) p.waitFor();
            }
            if (!baseline) {
                s.setBaseline(direct.score);
                baseline = true;
            }
            BypassSearch.Decision d = s.decide();
            for (String n : d.notes) System.out.println(n);
            if (d.claim != null) {
                System.out.println("Обход DPI: подключаюсь через «" + d.claim.label + "» — "
                        + (d.full ? "заблокированные сайты открываются" : "только обычные сайты"));
                Files.write(Paths.get(outFile), BypassProfile.route(d.claim, helper).args, StandardCharsets.UTF_8);
                Files.write(Paths.get(outFile + ".label"), java.util.Collections.singletonList(d.claim.label),
                        StandardCharsets.UTF_8);
                Files.write(Paths.get(outFile + ".profile"), java.util.Collections.singletonList(d.claim.argsLine()),
                        StandardCharsets.UTF_8);
                String dns = dnsOf.get(d.claim);
                if (dns == null) dns = NetProbe.DNS_SERVERS[0];
                System.out.println("DNS телефона: " + dns);
                Files.write(Paths.get(outFile + ".dns"), java.util.Collections.singletonList(dns),
                        StandardCharsets.UTF_8);
                return true;
            }
            if (d.test == null) {
                System.out.println("Обход DPI не помог ни одной стратегией");
                return false;
            }
            batch = d.test;
        }
    }

    private static void awaitPort(int port) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                return;
            } catch (java.io.IOException e) {
                Thread.sleep(100);
            }
        }
    }

    /** NetProbe на стенде доверяет сертификатам «сайтов» стенда (свой УЦ). */
    private static void setupTls(String caFile) throws Exception {
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
        NetProbe.hostnameVerifier = TunLab::verifyHost;
    }

    private static boolean verifyHost(String host, javax.net.ssl.SSLSession session) {
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
    }

    private static boolean probe(int port, String caFile) throws Exception {
        setupTls(caFile);
        String server = NetProbe.socksDnsServer(port, "ya.ru");
        boolean dns = server != null;
        System.out.println("  DNS через SOCKS-UDP ByeDPI: " + (dns ? "отвечает " + server : "НЕ ОТВЕЧАЕТ"));
        BypassSearch.Score s = NetProbe.bypassQuality(port);
        System.out.println("  NetProbe: " + s.summary() + " · " + s.detail);
        NetProbe.Health h = NetProbe.bypassHealth(port, dns ? server : NetProbe.DNS_SERVERS[0]);
        System.out.println("  Сторож: DNS " + (h.dns ? "+" : "-") + ", сайт " + (h.site ? "+" : "-"));
        return dns && h.ok() && s.normal() == s.normalTotal;
    }
}
