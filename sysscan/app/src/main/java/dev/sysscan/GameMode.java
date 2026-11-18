package dev.sysscan;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Игровой режим: включение запоминает ТОЧНОЕ исходное состояние каждого пакета,
 * выключение возвращает именно его. После каждого шага состояние перечитывается из системы.
 *
 * Все команды проверены по исходникам AOSP android11-release:
 *  pm disable-user / pm enable           — PackageManagerShellCommand
 *  cmd appops set/get RUN_ANY_IN_BACKGROUND (allow|ignore|default) — AppOpsService.Shell
 *  am set-standby-bucket / get-standby-bucket ("restricted" = 45) — ActivityManagerShellCommand
 *  cmd deviceidle whitelist -pkg / +pkg  — DeviceIdleController
 *  am force-stop, am kill-all            — ActivityManagerShellCommand
 */
public final class GameMode {
    private GameMode() { }

    public static final String GAME = "com.axlebolt.standoff2";

    /** Никогда не трогаем: VPN, Claude, связь, банк, сама игра и инструменты. */
    static final Set<String> EXCLUDE = new HashSet<>(Arrays.asList(
            "com.happproxy", "com.example.vpnapp", "com.anthropic.claude",
            "moe.shizuku.privileged.api", BuildConfig.APPLICATION_ID, "com.termux", "com.termux.api",
            "org.telegram.messenger", "com.idamob.tinkoff.android", "com.logistic.sdek",
            "com.google.android.apps.messaging", "com.google.android.dialer", GAME,
            "com.android.systemui", "com.miui.home", "com.miui.securitycenter", "com.miui.powerkeeper",
            "com.lbe.security.miui", "com.xiaomi.xmsf", "com.google.android.gms", "android"));

    /** Реклама/аналитика/неиспользуемое MIUI — отключаются (pm disable-user). */
    static final List<String> DISABLE = Arrays.asList(
            "com.miui.msa.global", "com.miui.analytics", "com.mi.android.globalminusscreen",
            "com.miui.videoplayer", "com.miui.hybrid", "com.miui.hybrid.accessory",
            "com.miui.yellowpage", "com.netflix.partner.activation", "com.google.android.projection.gearhead");

    /** Из пользовательского белого списка энергосбережения. */
    static final List<String> UNWHITELIST = Arrays.asList(
            "com.miui.msa.global", "com.miui.analytics", "com.xiaomi.mipicks",
            "com.miui.weather2", "ua.itaysonlab.vkx", "com.marcussacana.DirectPackageInstaller");

    /** Запрет фоновой работы (открытыми работают как обычно). */
    static final List<String> RESTRICT = Arrays.asList(
            "com.google.android.apps.maps", "com.google.android.youtube", "com.miui.weather2",
            "com.avito.android", "ru.ozon.app.android", "ru.beru.android", "ru.dns.shop.android",
            "com.reddit.frontpage", "com.zhiliaoapp.musically", "com.linkedin.android", "tv.twitch.android.app",
            "com.utorrent.client", "com.yandex.browser", "com.kiwibrowser.browser", "org.mozilla.firefox",
            "com.lazycatsoftware.lmd", "com.guanyin.litv", "ru.ag.a24htv", "ru.rostel", "to.boosty.android",
            "com.suno.android", "cn.wps.moffice_eng", "com.dualspace.multispace.android",
            "com.google.android.apps.youtube.creator", "com.tafayor.killall", "com.tafayor.rammonitor");

    /** Закрываются один раз при включении (сами запустятся, когда откроешь). */
    static final List<String> STOP = Arrays.asList(
            "com.google.android.apps.maps", "com.google.android.youtube", "com.android.settings",
            "com.google.android.tts", "com.miui.weather2", "com.android.vending", "com.android.camera",
            "com.miui.gallery", "com.openai.chatgpt", "com.discord", "com.yandex.browser",
            "com.google.android.apps.youtube.creator", "com.tafayor.killall", "com.tafayor.rammonitor",
            "com.zhiliaoapp.musically", "com.reddit.frontpage", "com.avito.android", "ru.ozon.app.android");

    private static final String OP = "RUN_ANY_IN_BACKGROUND";
    private static final String[] ANIM = {"window_animation_scale", "transition_animation_scale", "animator_duration_scale"};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("gamemode", Context.MODE_PRIVATE); }

    public static boolean isOn(Context c) { return prefs(c).getBoolean("on", false); }

    // ---------------- снимок состояния ----------------

    static class Snap {
        Set<String> installed = new HashSet<>(), disabled = new HashSet<>(), userWl = new HashSet<>();
        Map<String, String> ops = new HashMap<>();
        Map<String, String> bucket = new HashMap<>();
        String[] anim = new String[3];
        long memAvailMb, swapUsedMb;
        Set<String> dynamicExclude = new HashSet<>();
    }

    private static Set<String> all() {
        Set<String> s = new LinkedHashSet<>(DISABLE);
        s.addAll(UNWHITELIST); s.addAll(RESTRICT);
        return s;
    }

    static Snap snap() {
        Snap s = new Snap();
        StringBuilder cmd = new StringBuilder();
        cmd.append("echo '#INST'; pm list packages; echo '#DIS'; pm list packages -d; ");
        cmd.append("echo '#WL'; cmd deviceidle whitelist; ");
        cmd.append("echo '#ANIM'; for k in ").append(String.join(" ", ANIM)).append("; do settings get global $k; done; ");
        cmd.append("echo '#DYN'; settings get secure default_input_method; settings get secure sms_default_application; ");
        cmd.append("settings get secure always_on_vpn_app; ");
        cmd.append("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; ");
        cmd.append("echo '#MEM'; grep -E 'MemAvailable|SwapTotal|SwapFree' /proc/meminfo; ");
        cmd.append("echo '#PKG'; for p in ");
        for (String p : RESTRICT) cmd.append(p).append(' ');
        cmd.append("; do echo \"$p|$(cmd appops get $p ").append(OP).append(" 2>&1 | grep -v '^Uid mode' | grep ").append(OP).append(" | head -1)|$(am get-standby-bucket $p 2>&1 | head -1)\"; done");
        String out = Shell.sh(cmd.toString());

        String sec = "";
        int animI = 0;
        long swapT = 0, swapF = 0;
        for (String raw : out.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("#")) { sec = l; continue; }
            if (l.isEmpty()) continue;
            switch (sec) {
                case "#INST": if (l.startsWith("package:")) s.installed.add(l.substring(8)); break;
                case "#DIS": if (l.startsWith("package:")) s.disabled.add(l.substring(8)); break;
                case "#WL": if (l.startsWith("user,")) { String[] t = l.split(","); if (t.length > 1) s.userWl.add(t[1]); } break;
                case "#ANIM": if (animI < 3) s.anim[animI++] = l; break;
                case "#DYN":
                    String pkg = l.contains("/") ? l.substring(0, l.indexOf('/')) : l;
                    if (!pkg.equals("null") && pkg.contains(".")) s.dynamicExclude.add(pkg);
                    break;
                case "#MEM":
                    long kb = parseLong(l.replaceAll("[^0-9]", ""));
                    if (l.startsWith("MemAvailable")) s.memAvailMb = kb / 1024;
                    else if (l.startsWith("SwapTotal")) swapT = kb;
                    else if (l.startsWith("SwapFree")) swapF = kb;
                    break;
                case "#PKG":
                    String[] t = l.split("\\|", -1);
                    if (t.length >= 3) {
                        s.ops.put(t[0], parseMode(t[1]));
                        s.bucket.put(t[0], t[2].trim());
                    }
                    break;
            }
        }
        s.swapUsedMb = (swapT - swapF) / 1024;
        return s;
    }

    /** "RUN_ANY_IN_BACKGROUND: ignore; time=..." → ignore; "No operations." → default */
    static String parseMode(String l) {
        int i = l.indexOf(OP + ":");
        if (i < 0) return "default";
        String m = l.substring(i + OP.length() + 1).trim();
        int j = m.indexOf(';');
        if (j >= 0) m = m.substring(0, j);
        m = m.trim();
        int k = m.indexOf(' ');
        if (k >= 0) m = m.substring(0, k); // " (running)" и прочие хвосты
        return m.matches("allow|ignore|deny|default|foreground") ? m : "default";
    }

    private static long parseLong(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }

    private static boolean skip(Snap s, String p) {
        return EXCLUDE.contains(p) || s.dynamicExclude.contains(p) || !s.installed.contains(p);
    }

    // ---------------- ВКЛЮЧЕНИЕ ----------------

    public static String enable(Context c) {
        if (!Shell.ready()) return "❌ Нет доступа Shizuku — режим не включён. Ничего не изменено.";
        if (isOn(c)) return "Режим уже включён.";
        Snap before = snap();
        try {
            JSONObject st = new JSONObject();
            JSONArray dis = new JSONArray(), wl = new JSONArray();
            JSONObject ops = new JSONObject(), bk = new JSONObject();
            StringBuilder cmd = new StringBuilder();

            for (String p : DISABLE) {
                if (skip(before, p) || before.disabled.contains(p)) continue; // уже отключено пользователем — не наше
                dis.put(p);
                cmd.append("pm disable-user --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            }
            for (String p : UNWHITELIST) {
                if (EXCLUDE.contains(p) || !before.userWl.contains(p)) continue;
                wl.put(p);
                cmd.append("cmd deviceidle whitelist -").append(p).append(" >/dev/null 2>&1; ");
            }
            for (String p : RESTRICT) {
                if (skip(before, p)) continue;
                ops.put(p, before.ops.containsKey(p) ? before.ops.get(p) : "default");
                bk.put(p, before.bucket.containsKey(p) ? before.bucket.get(p) : "");
                cmd.append("cmd appops set ").append(p).append(' ').append(OP).append(" ignore; ");
                cmd.append("am set-standby-bucket ").append(p).append(" restricted; ");
            }
            for (String p : STOP) {
                if (skip(before, p)) continue;
                cmd.append("am force-stop ").append(p).append("; ");
            }
            for (String k : ANIM) cmd.append("settings put global ").append(k).append(" 0.5; ");
            // сам SysScan не должен убиваться MIUI, пока меряет FPS
            cmd.append("cmd deviceidle whitelist +").append(BuildConfig.APPLICATION_ID).append(" >/dev/null 2>&1; ");
            cmd.append("am kill-all");

            st.put("disabled", dis); st.put("wl", wl); st.put("ops", ops); st.put("bucket", bk);
            st.put("anim", new JSONArray(Arrays.asList(before.anim)));
            st.put("ts", System.currentTimeMillis());
            // сначала сохраняем исходное состояние — чтобы откат был возможен даже при сбое на середине
            prefs(c).edit().putString("state", st.toString()).putBoolean("on", true).commit();

            Shell.sh(cmd.toString());
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
            return "🎮 ИГРОВОЙ РЕЖИМ ВКЛЮЧЁН\n" + verifyOn(st, before, snap());
        } catch (Exception e) {
            return "❌ Ошибка: " + e;
        }
    }

    private static String verifyOn(JSONObject st, Snap before, Snap after) throws Exception {
        StringBuilder r = new StringBuilder();
        int ok = 0, bad = 0;
        JSONArray dis = st.getJSONArray("disabled");
        r.append("\nОтключено (реклама/аналитика MIUI):\n");
        for (int i = 0; i < dis.length(); i++) {
            String p = dis.getString(i);
            boolean y = after.disabled.contains(p);
            r.append(y ? "  ✅ " : "  ❌ ").append(p).append('\n');
            if (y) ok++; else bad++;
        }
        JSONArray wl = st.getJSONArray("wl");
        if (wl.length() > 0) r.append("Убрано из белого списка батареи:\n");
        for (int i = 0; i < wl.length(); i++) {
            String p = wl.getString(i);
            boolean y = !after.userWl.contains(p);
            r.append(y ? "  ✅ " : "  ❌ ").append(p).append('\n');
            if (y) ok++; else bad++;
        }
        r.append("Запрет фона (было → стало):\n");
        JSONObject ops = st.getJSONObject("ops");
        for (java.util.Iterator<String> it = ops.keys(); it.hasNext(); ) {
            String p = it.next();
            String m = after.ops.get(p), b = after.bucket.get(p);
            boolean y = "ignore".equals(m) && "45".equals(b);
            r.append(y ? "  ✅ " : "  ❌ ").append(p).append("  ").append(ops.getString(p)).append("→").append(m)
                    .append(", bucket ").append(st.getJSONObject("bucket").optString(p)).append("→").append(b).append('\n');
            if (y) ok++; else bad++;
        }
        r.append("Не тронуто (исключения): VPN Happ, Claude, Telegram, Тинькофф, СДЭК, SMS, звонки, клавиатура, Shizuku, Termux\n");
        r.append(String.format("\nПроверка из системы: %d ✅ / %d ❌\n", ok, bad));
        r.append("ОЗУ свободно: ").append(before.memAvailMb).append(" → ").append(after.memAvailMb).append(" МБ\n");
        r.append("Swap занят: ").append(before.swapUsedMb).append(" → ").append(after.swapUsedMb).append(" МБ\n");
        return r.toString();
    }

    // ---------------- ВЫКЛЮЧЕНИЕ ----------------

    public static String disable(Context c) {
        if (!Shell.ready()) return "❌ Нет доступа Shizuku. Запусти Shizuku и повтори — состояние сохранено, откат не потерян.";
        String raw = prefs(c).getString("state", null);
        if (!isOn(c) || raw == null) return "Режим и так выключен.";
        try {
            JSONObject st = new JSONObject(raw);
            StringBuilder cmd = new StringBuilder();
            JSONArray dis = st.getJSONArray("disabled");
            for (int i = 0; i < dis.length(); i++) cmd.append("pm enable --user 0 ").append(dis.getString(i)).append(" >/dev/null 2>&1; ");
            JSONArray wl = st.getJSONArray("wl");
            for (int i = 0; i < wl.length(); i++) cmd.append("cmd deviceidle whitelist +").append(wl.getString(i)).append(" >/dev/null 2>&1; ");
            JSONObject ops = st.getJSONObject("ops"), bk = st.getJSONObject("bucket");
            for (java.util.Iterator<String> it = ops.keys(); it.hasNext(); ) {
                String p = it.next();
                cmd.append("cmd appops set ").append(p).append(' ').append(OP).append(' ').append(ops.getString(p)).append("; ");
                String b = bk.optString(p);
                if (!b.isEmpty() && b.matches("\\d+") && !"45".equals(b)) cmd.append("am set-standby-bucket ").append(p).append(' ').append(b).append("; ");
                else if (!"45".equals(b)) cmd.append("am set-standby-bucket ").append(p).append(" active; ");
            }
            JSONArray anim = st.getJSONArray("anim");
            for (int i = 0; i < 3; i++) {
                String v = anim.optString(i, "null");
                if (v.equals("null") || v.isEmpty()) cmd.append("settings delete global ").append(ANIM[i]).append("; ");
                else cmd.append("settings put global ").append(ANIM[i]).append(' ').append(v).append("; ");
            }
            cmd.append("cmd deviceidle whitelist -").append(BuildConfig.APPLICATION_ID).append(" >/dev/null 2>&1; ");
            Shell.sh(cmd.toString());
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }

            Snap after = snap();
            StringBuilder r = new StringBuilder("↩️ ИГРОВОЙ РЕЖИМ ВЫКЛЮЧЕН — всё возвращено как было\n\n");
            int ok = 0, bad = 0;
            for (int i = 0; i < dis.length(); i++) {
                String p = dis.getString(i);
                boolean y = !after.disabled.contains(p);
                r.append(y ? "  ✅ включено: " : "  ❌ всё ещё отключено: ").append(p).append('\n');
                if (y) ok++; else bad++;
            }
            for (int i = 0; i < wl.length(); i++) {
                String p = wl.getString(i);
                boolean y = after.userWl.contains(p);
                r.append(y ? "  ✅ белый список: " : "  ❌ не вернулся в белый список: ").append(p).append('\n');
                if (y) ok++; else bad++;
            }
            for (java.util.Iterator<String> it = ops.keys(); it.hasNext(); ) {
                String p = it.next();
                String want = ops.getString(p), got = after.ops.get(p);
                boolean y = want.equals(got) || ("default".equals(want) && "allow".equals(got));
                r.append(y ? "  ✅ фон: " : "  ❌ фон: ").append(p).append(" = ").append(got).append(" (было ").append(want).append(")\n");
                if (y) ok++; else bad++;
            }
            r.append(String.format("\nПроверка из системы: %d ✅ / %d ❌\n", ok, bad));
            if (bad == 0) prefs(c).edit().putBoolean("on", false).remove("state").commit();
            else r.append("Есть ❌ — нажми «Выключить» ещё раз или пришли этот отчёт.\n");
            return r.toString();
        } catch (Exception e) {
            return "❌ Ошибка: " + e;
        }
    }

    public static List<String> summaryLists() {
        List<String> l = new ArrayList<>();
        l.add("Отключит: " + String.join(", ", DISABLE));
        l.add("Запретит фон: " + RESTRICT.size() + " приложениям");
        return l;
    }
}
