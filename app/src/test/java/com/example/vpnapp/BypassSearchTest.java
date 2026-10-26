package com.example.vpnapp;

import java.util.*;
import java.util.function.Function;

/**
 * Тест выбора стратегии обхода DPI без Android: scripts/test-bypass.sh (javac + java, без Gradle).
 * С аргументом --print-args печатает все встроенные строки ByeDPI (по строке на запуск, аргументы
 * через табуляцию: в списке доменов есть пробелы) — скрипт проверяет их ядром.
 */
public class BypassSearchTest {
    static int fails = 0;
    static void check(boolean c, String msg) { if (!c) { fails++; System.out.println("FAIL: " + msg); } else if (VERBOSE) System.out.println("ok: " + msg); }
    static final boolean VERBOSE = System.getenv("VERBOSE") != null;
    static BypassSearch.Score sc(int blocked, int foreign, int local) { return new BypassSearch.Score(blocked, 4, foreign, local, 6, "b" + blocked + " f" + foreign + " l" + local); }

    /** Прогон: oracle даёт Score по стратегии; возвращает итоговое решение и число пачек. */
    static Object[] run(BypassSearch s, BypassSearch.Score baseline, Function<BypassProfile, BypassSearch.Score> oracle, List<String> log) {
        List<BypassProfile> batch = s.currentBatch();
        int batches = 0;
        boolean baselineSet = false;
        while (true) {
            batches++;
            check(batch.size() <= BypassSearch.PARALLEL, "batch size " + batch.size() + " <= PARALLEL");
            for (BypassProfile p : batch) {
                log.add("  test " + p.label + " [" + p.argsLine() + "]");
                s.report(p, oracle.apply(p));
            }
            if (!baselineSet) { check(!s.ready() || baseline == null, "waits for baseline"); s.setBaseline(baseline); baselineSet = true; }
            check(s.ready(), "ready after batch");
            BypassSearch.Decision d = s.decide();
            for (String n : d.notes) log.add("  note: " + n);
            if (d.claim != null) {
                boolean inBatch = false;
                for (BypassProfile p : batch) if (p == d.claim) inBatch = true;
                check(inBatch, "claimed profile is running in current batch");
                return new Object[]{d, batches};
            }
            if (d.test == null) return new Object[]{d, batches};
            batch = d.test;
        }
    }

    static boolean has(BypassProfile p, String strong) { return p.label.endsWith("→ " + strong); }

    public static void main(String[] a) {
        if (a.length > 1 && a[0].equals("--debug-formats")) {
            // Каждый отладочный формат ByeDPI (LOG_S/LOG_L) — с подставленными значениями — отладка
            ByeDpiStats st = new ByeDpiStats(Collections.singletonList("x"));
            int n = 0;
            try {
                for (String f : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(a[1]))) {
                    String line = f.replace("\\n", "").replace("%.*s", "x").replace("%s", "1.2.3.4")
                            .replaceAll("%[a-z]*[dux]", "5");
                    n++;
                    check(st.feed(line), "debug line not filtered: " + line);
                }
            } catch (java.io.IOException e) {
                check(false, "formats: " + e);
            }
            check(n > 20, "debug formats read: " + n);
            System.out.println(fails == 0 ? "Отладочные строки ByeDPI (" + n + "): OK" : "FAILURES: " + fails);
            System.exit(fails == 0 ? 0 : 1);
        }
        if (a.length > 0 && a[0].equals("--print-args")) {
            List<BypassProfile> all = new ArrayList<>(BypassProfile.MILD);
            for (BypassProfile st : BypassProfile.STRONG) {
                all.add(BypassProfile.chain(BypassProfile.MILD.get(0), st));
                all.add(BypassProfile.chain(null, st));
            }
            // Как их запускает приложение: аргументы процесса и маршрут, без запасного пути и с ним
            for (BypassProfile p : all) {
                for (int helper : new int[]{0, HelperRelay.PORT}) {
                    List<String> line = new ArrayList<>(BypassProfile.processArgs(1));
                    line = line.subList(4, line.size());  // без -i/-p: их задаёт скрипт
                    line.addAll(BypassProfile.route(p, helper).args);
                    System.out.println(String.join("\t", line));
                }
            }
            return;
        }
        // 1) Сеть пользователя: мягкие дают только обычные; oob в цепочке пробивает 2/4, не ломая обычные
        {
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, null, false, false);
            Object[] r = run(s, sc(0, 1, 3), p -> {
                if (has(p, "oob SNI") && p.label.startsWith("split SNI")) return sc(2, 1, 3);
                return sc(0, 1, 3);
            }, log);
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            if (VERBOSE) log.forEach(System.out::println);
            check(d.claim != null && d.full && d.claim.label.equals("split SNI → oob SNI"), "user net: claims chain split→oob, full");
            check(d.claim.argsLine().equals("-T 4:0:0:1 -s 1+s -A t,r,s -o 1+s"), "chain args: " + d.claim.argsLine());
            check((int) r[1] == 6, "user net: 6 batches (first, 4 chain batches, re-check)");
        }
        // 2) Стратегия пробивает, но ломает обычные (как oob в 0.8.4) — не берём; берём BASIC
        {
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, null, false, false);
            Object[] r = run(s, sc(0, 1, 3), p -> {
                if (has(p, "oob SNI")) return sc(1, 1, 1);  // ломает российские
                return sc(0, 1, 3);
            }, log);
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            if (VERBOSE) log.forEach(System.out::println);
            check(d.claim != null && !d.full && d.claim.label.equals("split SNI"), "breaks-normal rejected, basic split SNI claimed: " + (d.claim != null ? d.claim.label : null));
            boolean sawBreaks = false; for (String l : log) if (l.contains("ЛОМАЕТ")) sawBreaks = true;
            check(sawBreaks, "log says ЛОМАЕТ");
        }
        // 3) Запомненная полная — берём сразу, из первой пачки
        {
            BypassProfile rem = BypassProfile.chain(BypassProfile.MILD.get(0), BypassProfile.STRONG.get(0));
            BypassProfile remCopy = BypassProfile.parse(rem.label, rem.argsLine());
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, remCopy, true, false);
            Object[] r = run(s, sc(0, 1, 3), p -> p.sameArgs(rem) ? sc(1, 1, 3) : sc(0, 1, 3), log);
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            check(d.claim != null && d.full && d.claim.sameArgs(rem) && (int) r[1] == 1, "remembered full claimed in first batch");
        }
        // 4) Быстрый повтор запомненной BASIC
        {
            BypassProfile rem = BypassProfile.parse("split SNI", "-s 1+s");
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, rem, false, true);
            check(s.currentBatch().size() == 1, "quick: only remembered in first batch");
            Object[] r = run(s, sc(0, 1, 3), p -> sc(0, 1, 3), log);
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            check(d.claim == rem && !d.full && d.fromMemory && (int) r[1] == 1, "quick basic claimed from memory");
        }
        // 5) Быстрый повтор не прошёл — полный перебор без повторной проверки запомненной
        {
            BypassProfile rem = BypassProfile.parse("x", "-s 1+s -d 5");
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, rem, false, true);
            Object[] r = run(s, sc(0, 1, 3), p -> p == rem ? sc(0, 0, 0) : (p.label.equals("disorder SNI") ? sc(4, 2, 3) : sc(0, 1, 3)), log);
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            if (VERBOSE) log.forEach(System.out::println);
            check(d.claim != null && d.full && d.claim.label.equals("disorder SNI") && !d.fromMemory, "after quick fail: full search finds disorder");
        }
        // 6) Ничего не работает — сдаёмся
        {
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, null, false, false);
            Object[] r = run(s, sc(0, 0, 0), p -> sc(0, 0, 0), log);
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            check(d.giveUp(), "nothing works: give up");
        }
        // 7) Лучшая полная из ранней пачки перепроверяется; повтор не прошёл — берём BASIC
        {
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, null, false, false);
            int[] oobCalls = {0};
            Object[] r = run(s, sc(0, 1, 3), p -> {
                if (has(p, "oob SNI")) return (oobCalls[0]++ == 0) ? sc(1, 1, 3) : sc(0, 0, 0);
                return sc(0, 1, 3);
            }, log);
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            if (VERBOSE) log.forEach(System.out::println);
            check(oobCalls[0] == 2, "full candidate re-verified once");
            check(d.claim != null && !d.full && d.claim.label.equals("split SNI"), "re-verify failed: basic claimed: " + (d.claim != null ? d.claim.label : null));
        }
        // 8) Своя строка
        {
            BypassProfile c = BypassProfile.parse("свои настройки", "-d 1 -s 1+s");
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(c, null, false, false);
            check(s.ready() == false, "custom: not ready before report");
            s.report(c, sc(0, 1, 2));
            check(s.ready(), "custom: ready without baseline");
            BypassSearch.Decision d = s.decide();
            check(d.claim == c && !d.full, "custom claimed as basic");
        }
        // 9) Мягкие хуже прямого соединения — основа цепочек «без обхода»
        {
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, null, false, false);
            List<BypassProfile> seen = new ArrayList<>();
            Object[] r = run(s, sc(0, 3, 3), p -> { seen.add(p); return BypassProfile.MILD.contains(p) ? sc(0, 1, 1) : sc(0, 3, 3); }, log);
            boolean plainBase = false; for (BypassProfile p : seen) if (p.label.startsWith("без обхода → ")) plainBase = true;
            check(plainBase, "mild worse than direct: chains over plain");
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            check(d.claim != null && !d.full && d.claim.label.startsWith("без обхода"), "basic claim = best normal (plain chain): " + (d.claim != null ? d.claim.label : null));
        }
        // 10) Порог: 3/4 заблокированных в пачке — берём сразу, не перебирая дальше
        {
            List<String> log = new ArrayList<>();
            BypassSearch s = new BypassSearch(null, null, false, false);
            Object[] r = run(s, sc(0, 1, 3), p -> has(p, "disoob SNI") ? sc(3, 1, 3) : sc(0, 1, 3), log);
            BypassSearch.Decision d = (BypassSearch.Decision) r[0];
            check(d.claim != null && d.full && has(d.claim, "disoob SNI") && (int) r[1] == 2, "threshold reached: early claim in batch 2");
        }
        // 11) Выбранная стратегия успела остановиться: перепроверка и подключение
        {
            BypassSearch s = new BypassSearch(null, null, false, false);
            for (BypassProfile p : s.currentBatch()) s.report(p, p.label.equals("disorder SNI") ? sc(3, 1, 3) : sc(0, 1, 3));
            s.setBaseline(sc(0, 1, 3));
            BypassSearch.Decision d = s.decide();
            check(d.claim != null && d.claim.label.equals("disorder SNI"), "disorder claimed");
            List<BypassProfile> again = s.recheck(d.claim);
            check(again.size() == 1 && again.get(0) == d.claim && !s.ready(), "recheck: one profile, waits for result");
            s.report(d.claim, sc(3, 1, 3));
            BypassSearch.Decision d2 = s.decide();
            check(d2.claim == d.claim && d2.full, "recheck passed: claimed again");
        }
        // 12) Маршрут цепочки: список доменов → сразу сильная, остальное — мягкая, сбой — сильная
        {
            BypassProfile c = BypassProfile.chain(BypassProfile.MILD.get(0), BypassProfile.STRONG.get(1));
            BypassProfile.Route r = BypassProfile.route(c, 0);
            check(r.groups.equals(Arrays.asList("QUIC Meta → отброшен", "список → disoob SNI", "split SNI", "disoob SNI после сбоя")),
                    "route groups: " + r.groups);
            String line = String.join(" ", r.args);
            check(line.startsWith("-T 4:0:0:1 -K u -V 443 -j :31.13.24.0/21 ") && line.contains(" 2a03:2880::/32 --udp-drop -A n -V 443 -H :instagram.com cdninstagram.com "),
                    "route starts with QUIC drop, then hosts group: " + line);
            check(line.endsWith(" -q 1+s -A n -s 1+s -A t,r,s -q 1+s"), "route tail: " + line);
            check(!r.helper && !line.contains("-C"), "no helper groups without helper port");
            // Запомненная прошлой версией (строкой аргументов) — тот же маршрут
            BypassProfile old = BypassProfile.parse("split SNI → disoob SNI", "-T 4:0:0:1 -s 1+s -A t,r,s -q 1+s");
            check(BypassProfile.route(old, 0).args.equals(r.args), "remembered 0.8.6 chain gets the same route");
        }
        // 13) С запасным путём: Telegram → Psiphon (не готов — напрямую), после сбоев — Psiphon
        {
            BypassProfile c = BypassProfile.chain(BypassProfile.MILD.get(0), BypassProfile.STRONG.get(1));
            BypassProfile.Route r = BypassProfile.route(c, 10808);
            check(r.helper && r.groups.equals(Arrays.asList("QUIC Meta → отброшен", "Telegram → Psiphon", "Telegram напрямую",
                    "список → disoob SNI", "список → Psiphon", "split SNI", "disoob SNI после сбоя", "Psiphon после сбоя")),
                    "helper route groups: " + r.groups);
            List<String> x = r.args;
            int j = x.indexOf("-A") + 2;  // после группы QUIC
            check(x.get(j).equals("-j") && x.get(j + 1).startsWith(":91.105.192.0/23 ") && x.get(j + 1).contains("149.154.160.0/20")
                    && x.get(j + 2).equals("-C") && x.get(j + 3).equals("socks5://127.0.0.1:10808"), "telegram group after QUIC");
            check(x.get(j + 4).equals("-A") && x.get(j + 5).equals("c") && x.get(j + 6).equals("-A") && x.get(j + 7).equals("n"),
                    "telegram direct fallback on connect error");
            int psiphon = 0;
            for (int i = 0; i + 1 < x.size(); i++) if (x.get(i).equals("-C")) psiphon++;
            check(psiphon == 3, "3 groups via helper: " + psiphon);
            // Группа «… → Psiphon» сразу за той, чей сбой она подхватывает (ByeDPI не перескакивает
            // через группу без условия)
            String line = String.join(" ", x);
            check(line.contains("-q 1+s -A t,r,s,c -C socks5://127.0.0.1:10808 -A n -s 1+s -A t,r,s -q 1+s -A t,r,s,c -C socks5://127.0.0.1:10808"),
                    "helper groups order: " + line);
        }
        // 14) Мягкая сама по себе: без запасного пути — как есть; с ним — Telegram и «после сбоя»
        {
            BypassProfile m = BypassProfile.MILD.get(0);
            check(BypassProfile.route(m, 0).args.equals(Arrays.asList(m.args)), "mild as is");
            BypassProfile.Route r = BypassProfile.route(m, 10808);
            check(r.groups.equals(Arrays.asList("Telegram → Psiphon", "Telegram напрямую", "split SNI", "Psiphon после сбоя")),
                    "mild helper groups: " + r.groups);
            check(r.args.get(0).equals("-T"), "mild with helper gets timeout for fallback");
        }
        // 15) Своя строка — как есть, даже с запасным путём; цепочка поверх прямого соединения
        {
            BypassProfile c = BypassProfile.parse("свои", "-d 1 -s 1+s -A t -o 1");
            check(BypassProfile.route(c, 10808).args.equals(Arrays.asList(c.args)), "custom as is");
            check(BypassProfile.chainParts(BypassProfile.parse("x", "-T 4:0:0:1 -s 1+s -A t,r,s -q 1+s -A t -o 1")) == null,
                    "custom with own groups is not our chain");
            BypassProfile plain = BypassProfile.chain(null, BypassProfile.STRONG.get(0));
            BypassProfile.Route r = BypassProfile.route(plain, 0);
            check(r.groups.equals(Arrays.asList("QUIC Meta → отброшен", "список → oob SNI", "без обхода", "oob SNI после сбоя")), "plain chain route: " + r.groups);
        }
        // 16) Сводка вывода ByeDPI
        {
            ByeDpiStats st = new ByeDpiStats(BypassProfile.route(
                    BypassProfile.chain(BypassProfile.MILD.get(0), BypassProfile.STRONG.get(1)), 0).groups);
            String[] out = {
                    "accept: fd=5", "new conn: fd=6, pair=5, addr=57.144.62.34:443",
                    "desync TCP: group=1, round=1, fd=6", "host: i.instagram.com (127)", "split: pos=0-128 (1), m: disoob",
                    "new conn: fd=8, pair=7, addr=157.240.200.36:443",
                    "desync TCP: group=2, round=1, fd=8", "host: scontent-hel3-1.cdninstagram.com (127)",
                    "recv: Connection timed out", "save: ip=157.240.200.36, id=3",
                    "desync TCP: group=3, round=1, fd=9", "host: scontent-hel3-1.cdninstagram.com (127)",
                    "recv: Connection timed out", "unreach ip: 157.240.200.36",
                    "new conn: fd=10, pair=11, addr=149.154.167.51:443", "desync TCP: group=2, round=1, fd=10",
                    "bytes: 5a1b (184)", "split cancel: pos=0--1, n=184", "unreach ip: 149.154.167.51",
                    "udp addr: fd=12, addr=157.240.1.1:443", "udp drop: group=0, fd=12",
                    "udp addr: fd=13, addr=::ffff:142.250.1.1:443", "udp addr: fd=14, addr=1.1.1.1:53",
                    "send: 3 != 10 (fd=6)", "send: pos=126-517", "close: fd=6 (pair=5), recv: 100, rounds: 1"};
            int errors = 0;
            for (String l : out) if (!st.feed(l)) errors++;
            check(errors == 3, "error lines passed to log: " + errors);
            String sum = st.drain();
            check(sum.startsWith("соединений 4: список → disoob SNI 1, split SNI 2, disoob SNI после сбоя 1"), "summary groups: " + sum);
            check(sum.contains("после сбоя: scontent-hel3-1.cdninstagram.com 157.240.200.36 → disoob SNI после сбоя"), "summary fallback: " + sum);
            check(sum.contains("не открылись ничем: ") && sum.contains("149.154.167.51 (Telegram)"), "summary unreach: " + sum);
            check(sum.contains("; QUIC (UDP 443): Meta 1, Google 1") || sum.contains("; QUIC (UDP 443): Google 1, Meta 1"),
                    "summary QUIC (::ffff: — как IPv4): " + sum);
            check(st.drain().isEmpty(), "drained");
            check("Meta".equals(ByeDpiStats.owner("157.240.1.1")) && ByeDpiStats.owner("8.8.8.8") == null, "owners");
            // Проверка «почему» получает адреса и после сводки (в 0.8.7 сводка их стирала)
            check(st.takeNewUnreachable(5).equals(Arrays.asList("157.240.200.36", "149.154.167.51")), "unreachable after drain");
            st.feed("unreach ip: 149.154.167.51");
            check(st.takeNewUnreachable(5).isEmpty(), "not reported twice");
        }
        // 17) Запасной путь: адрес ::ffff:a.b.c.d от ByeDPI (IPv6-сокеты на Android) уходит в Psiphon как IPv4
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            byte[][] got = new byte[2][];
            Thread t = new Thread(() -> {
                try (java.net.Socket c = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), ss.getLocalPort())) {
                    c.getOutputStream().write(new byte[]{5, 1, 0});
                    got[1] = c.getInputStream().readNBytes(2);
                    byte[] req = {5, 1, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff,
                            (byte) 149, (byte) 154, (byte) 167, 51, 1, (byte) 187};
                    c.getOutputStream().write(req);
                    Thread.sleep(200);
                } catch (Exception e) { got[1] = null; }
            });
            t.start();
            try (java.net.Socket srv = ss.accept()) { got[0] = HelperRelay.acceptSocks(srv); }
            t.join();
            check(got[1] != null && got[1][0] == 5 && got[1][1] == 0, "relay answers SOCKS greeting");
            check(Arrays.equals(got[0], new byte[]{5, 1, 0, 1, (byte) 149, (byte) 154, (byte) 167, 51, 1, (byte) 187}),
                    "mapped IPv6 → IPv4: " + Arrays.toString(got[0]));
        } catch (Exception e) {
            check(false, "relay socks test: " + e);
        }
        // Все цепочки разные и содержат таймаут
        Set<String> args = new HashSet<>();
        for (BypassProfile st : BypassProfile.STRONG) args.add(BypassProfile.chain(BypassProfile.MILD.get(0), st).argsLine());
        check(args.size() == BypassProfile.STRONG.size(), "chains unique");
        System.out.println(fails == 0 ? "ALL OK" : ("FAILURES: " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
