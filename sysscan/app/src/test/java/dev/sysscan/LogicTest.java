package dev.sysscan;

import static org.junit.Assert.*;

import org.junit.Test;

public class LogicTest {

    // Форматы вывода взяты из AppOpsService.Shell (android11-release)
    @Test public void appopsModes() {
        assertEquals("ignore", GameMode.parseMode("RUN_ANY_IN_BACKGROUND: ignore; time=+5m10s ago"));
        assertEquals("allow", GameMode.parseMode("RUN_ANY_IN_BACKGROUND: allow"));
        assertEquals("allow", GameMode.parseMode("RUN_ANY_IN_BACKGROUND: allow (running)"));
        assertEquals("default", GameMode.parseMode(""));
        assertEquals("default", GameMode.parseMode("No operations."));
    }

    @Test public void exclusionsNeverTouched() {
        String[] must = {"com.happproxy", "com.anthropic.claude", "com.axlebolt.standoff2",
                "moe.shizuku.privileged.api", "org.telegram.messenger", "com.idamob.tinkoff.android"};
        for (String p : must) {
            assertTrue(GameMode.EXCLUDE.contains(p));
            assertFalse(p, GameMode.DISABLE.contains(p));
            assertFalse(p, GameMode.RESTRICT.contains(p));
            assertFalse(p, GameMode.STOP.contains(p));
            assertFalse(p, Debloat.SET.contains(p));
        }
        for (String p : Debloat.SET) assertFalse(p, GameMode.EXCLUDE.contains(p));
    }

    private static String latency(long startNs, int n, long stepNs, long gapAt, long gapNs) {
        StringBuilder b = new StringBuilder("16666666\n");
        long t = startNs;
        for (int i = 0; i < n; i++) {
            if (i == gapAt) t += gapNs;
            b.append(t - 1000).append('\t').append(t).append('\t').append(t - 500).append('\n');
            t += stepNs;
        }
        b.append("9223372036854775807\t9223372036854775807\t9223372036854775807\n"); // кадр в очереди
        return b.append("#S\n2016000\n1804800\n#T\n61600\n65100\n#G\n52200\n#B\n396\n#M\nMemAvailable:  993280 kB\n").toString();
    }

    @Test public void fpsFromSurfaceFlinger() {
        FpsService f = new FpsService();
        long step = 16_666_666L;
        long base = 1_000_000_000_000L;
        // 1-й замер: буфер истории (127 кадров) — не считается
        FpsService.Sample s0 = f.parse(latency(base, 127, step, -1, 0));
        assertEquals(0, s0.fps);
        // записываем как сделал бы цикл
        addSample(f, s0);
        // 2-й замер: буфер сдвинулся на 60 новых кадров (ровно 1 с при 60 FPS)
        FpsService.Sample s1 = f.parse(latency(base + 60 * step, 127, step, -1, 0));
        assertEquals(60, s1.fps);
        assertEquals(16.7, s1.worstMs, 0.1);
        assertEquals(2016000, s1.big);
        assertEquals(65.1, s1.cpuT, 0.01);
        assertEquals(39.6, s1.batT, 0.01);
        assertEquals(970, s1.memMb);
        addSample(f, s1);
        // 3-й: фриз 100 мс внутри новой секунды → меньше кадров и худший кадр ~117 мс
        FpsService.Sample s2 = f.parse(latency(base + 120 * step, 127, step, 120, 100_000_000L));
        assertTrue("fps=" + s2.fps, s2.fps >= 59 && s2.fps <= 61);
        assertEquals(116.7, s2.worstMs, 0.2);
    }

    private static void addSample(FpsService f, FpsService.Sample s) {
        try {
            java.lang.reflect.Field fl = FpsService.class.getDeclaredField("samples");
            fl.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.List<FpsService.Sample> l = (java.util.List<FpsService.Sample>) fl.get(f);
            l.add(s);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
