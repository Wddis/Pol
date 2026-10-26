package com.example.vpnapp;

import java.util.*;
import java.util.function.Function;

/**
 * Тест выбора стратегии обхода DPI без Android: scripts/test-bypass.sh (javac + java, без Gradle).
 * С аргументом --print-args печатает все встроенные строки ByeDPI — скрипт проверяет их ядром.
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
        if (a.length > 0 && a[0].equals("--print-args")) {
            for (BypassProfile p : BypassProfile.MILD) System.out.println(p.argsLine());
            for (BypassProfile st : BypassProfile.STRONG) {
                System.out.println(BypassProfile.chain(BypassProfile.MILD.get(0), st).argsLine());
                System.out.println(BypassProfile.chain(null, st).argsLine());
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
            check((int) r[1] == 5, "user net: 5 batches (first, 3 chain batches, re-check)");
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
        // Все цепочки разные и содержат таймаут
        Set<String> args = new HashSet<>();
        for (BypassProfile st : BypassProfile.STRONG) args.add(BypassProfile.chain(BypassProfile.MILD.get(0), st).argsLine());
        check(args.size() == BypassProfile.STRONG.size(), "chains unique");
        System.out.println(fails == 0 ? "ALL OK" : ("FAILURES: " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
