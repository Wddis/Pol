package dev.sysscan;

import android.content.Context;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Разовая чистка: удаляет мусор MIUI для пользователя 0 (pm uninstall -k --user 0).
 * Системный раздел не трогается (root не нужен) — восстановление: cmd package install-existing.
 *
 * Список — только пакеты с оценкой "Recommended" (безопасно) в базе Universal Android Debloater NG
 * (github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation, uad_lists.json),
 * пересечённые с реальным списком пакетов этого телефона. Пользовательские приложения
 * (Заметки, Погода, Компас, YouTube, Карты, облако) не включены.
 */
public final class Debloat {
    private Debloat() { }

    /** пакет → что это (по UAD-ng). */
    static final Map<String, String> LIST = new LinkedHashMap<>();
    static {
        // реклама и слежка MIUI
        LIST.put("com.miui.msa.global", "Системная реклама MIUI");
        LIST.put("com.miui.analytics", "Аналитика Xiaomi");
        LIST.put("com.miui.daemon", "MIUI daemon — сбор и отправка данных");
        LIST.put("com.miui.hybrid", "Быстрые приложения (реклама)");
        LIST.put("com.miui.hybrid.accessory", "Аксессуары для быстрых приложений");
        LIST.put("com.mi.android.globalminusscreen", "Левый экран App Vault (уведомления-реклама)");
        LIST.put("com.miui.wmsvc", "WMService — фоновый сервис с интернетом и GPS");
        LIST.put("com.xiaomi.xmsfkeeper", "Логгер сервисов Xiaomi");
        LIST.put("com.miui.yellowpage", "Жёлтые страницы MIUI");
        LIST.put("com.xiaomi.mipicks", "Магазин GetApps (реклама)");
        LIST.put("com.xiaomi.glgm", "Xiaomi Games");
        // телеметрия Qualcomm (постоянно висят в памяти)
        LIST.put("com.qualcomm.qti.qdma", "Qualcomm Device Management & Analytics");
        LIST.put("com.qualcomm.qti.devicestatisticsservice", "Qualcomm статистика использования");
        LIST.put("com.qualcomm.qti.qms.service.telemetry", "Qualcomm телеметрия");
        LIST.put("com.qualcomm.qti.smq", "Qualcomm Technology Reporting");
        // китайские / индийские платёжные сервисы (не работают в РФ)
        LIST.put("com.tencent.soter.soterserver", "Tencent Soter (биометрия WeChat Pay)");
        LIST.put("org.ifaa.aidl.manager", "IFAA (китайская финансовая аутентификация)");
        LIST.put("com.mipay.wallet.in", "Mi Pay Индия");
        LIST.put("com.xiaomi.payment", "Mi Credit");
        LIST.put("com.xiaomi.simactivate.service", "Активация SIM Xiaomi (Китай)");
        LIST.put("com.netflix.partner.activation", "Активация Netflix");
        // скрытые заводские тесты и мелочь
        LIST.put("com.longcheertel.AutoTest", "Заводской тест");
        LIST.put("com.longcheertel.cit", "Заводской тест CIT");
        LIST.put("com.longcheertel.midtest", "Заводской тест");
        LIST.put("com.longcheertel.modemlog", "Логи модема / tcpdump");
        LIST.put("com.longcheertel.sarauth", "Заводской тест");
        LIST.put("com.goodix.gftest", "Тест сканера отпечатка");
        LIST.put("com.fingerprints.sensortesttool", "Тест сканера отпечатка");
        LIST.put("com.android.traceur", "Системная трассировка (для разработчиков)");
        LIST.put("com.google.android.feedback", "Отправка отчётов о сбоях");
        LIST.put("com.google.android.gms.location.history", "Пустая заглушка истории местоположений");
        LIST.put("com.android.providers.partnerbookmarks", "Партнёрские закладки Chrome");
        LIST.put("com.android.egg", "Пасхалка Android");
    }

    static boolean isDone(Context c) { return GameMode.prefs(c).getBoolean("debloated", false); }

    private static Set<String> installed() {
        Set<String> s = new HashSet<>();
        for (String l : Shell.sh("pm list packages").split("\n"))
            if (l.startsWith("package:")) s.add(l.substring(8).trim());
        return s;
    }

    /** RSS (МБ) процессов из списка — сколько ОЗУ они занимали прямо сейчас. */
    private static Map<String, Long> rssByPkg() {
        Map<String, Long> m = new HashMap<>();
        for (String l : Shell.sh("ps -A -o RSS,NAME").split("\n")) {
            String[] t = l.trim().split("\\s+", 2);
            if (t.length < 2) continue;
            String base = t[1].contains(":") ? t[1].substring(0, t[1].indexOf(':')) : t[1];
            if (!LIST.containsKey(base)) continue;
            try { m.merge(base, Long.parseLong(t[0]) / 1024, Long::sum); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    public static String run(Context c) {
        if (!Shell.ready()) return "❌ Нет доступа Shizuku. Ничего не изменено.";
        Set<String> before = installed();
        Map<String, Long> rss = rssByPkg();
        String memBefore = Shell.sh("grep MemAvailable /proc/meminfo").replaceAll("[^0-9]", "");

        StringBuilder cmd = new StringBuilder();
        for (String p : LIST.keySet()) {
            if (GameMode.EXCLUDE.contains(p) || !before.contains(p)) continue;
            cmd.append("pm uninstall -k --user 0 ").append(p).append(" >/dev/null 2>&1; ");
        }
        if (cmd.length() > 0) Shell.sh(cmd.toString());
        GameMode.prefs(c).edit().putBoolean("debloated", true).commit();
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }

        Set<String> after = installed();
        String memAfter = Shell.sh("grep MemAvailable /proc/meminfo").replaceAll("[^0-9]", "");
        StringBuilder r = new StringBuilder("🧹 РАЗОВАЯ ЧИСТКА\n(источник списка: UAD-ng, только «Recommended»)\n\n");
        int ok = 0, bad = 0, absent = 0;
        long freed = 0;
        for (Map.Entry<String, String> e : LIST.entrySet()) {
            String p = e.getKey();
            if (!before.contains(p)) { absent++; continue; }
            boolean y = !after.contains(p);
            long mb = rss.containsKey(p) ? rss.get(p) : 0;
            if (y) { ok++; freed += mb; } else bad++;
            r.append(y ? "✅ " : "❌ ").append(e.getValue()).append(mb > 0 ? "  (" + mb + " МБ ОЗУ)" : "")
                    .append("\n    ").append(p).append('\n');
        }
        r.append(String.format("\nУдалено: %d ✅ / не удалось: %d ❌ / не было на телефоне: %d\n", ok, bad, absent));
        r.append("Эти процессы занимали в ОЗУ: ~").append(freed).append(" МБ\n");
        try {
            r.append("Свободно ОЗУ сейчас: ").append(Long.parseLong(memBefore) / 1024).append(" → ")
                    .append(Long.parseLong(memAfter) / 1024).append(" МБ\n");
        } catch (NumberFormatException ignored) { }
        r.append("\nПерезагрузи телефон — после этого они больше не запустятся.\n"
                + "Откат: кнопка «Вернуть удалённое» (или сброс до заводских).\n");
        return r.toString();
    }

    public static String restore(Context c) {
        if (!Shell.ready()) return "❌ Нет доступа Shizuku.";
        Set<String> before = installed();
        StringBuilder cmd = new StringBuilder();
        for (String p : LIST.keySet())
            if (!before.contains(p)) cmd.append("cmd package install-existing --user 0 ").append(p).append(" >/dev/null 2>&1; ");
        if (cmd.length() > 0) Shell.sh(cmd.toString());
        Set<String> after = installed();
        StringBuilder r = new StringBuilder("↩️ ВОЗВРАТ УДАЛЁННОГО\n\n");
        int ok = 0, miss = 0;
        for (String p : LIST.keySet()) {
            if (before.contains(p)) continue;
            if (after.contains(p)) { ok++; r.append("✅ ").append(p).append('\n'); }
            else { miss++; r.append("— нет в прошивке: ").append(p).append('\n'); }
        }
        GameMode.prefs(c).edit().putBoolean("debloated", false).commit();
        r.append(String.format("\nВозвращено: %d, отсутствуют в прошивке: %d\n", ok, miss));
        return r.toString();
    }

    public static String preview() {
        StringBuilder b = new StringBuilder("Будут удалены для пользователя (системный раздел не трогается, откат возможен):\n\n");
        for (Map.Entry<String, String> e : LIST.entrySet()) b.append("• ").append(e.getValue()).append('\n');
        b.append("\nНЕ трогается: VPN, Claude, Telegram, банк, звонки, SMS, камера, галерея, клавиатура, "
                + "Play Маркет, сервисы Google, «Безопасность», рабочий стол, обновления системы.");
        return b.toString();
    }

    static final Set<String> SET = new HashSet<>(Arrays.asList(LIST.keySet().toArray(new String[0])));
}
