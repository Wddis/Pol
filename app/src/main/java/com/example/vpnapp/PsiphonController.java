package com.example.vpnapp;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Psiphon (psiphon-tunnel-core, собирается в CI скриптом scripts/build-psiphon.sh): сеть
 * серверов Psiphon с десятком способов маскировки (OSSH, TLS, meek через CDN, QUIC, in-proxy
 * через домашние IP добровольцев). Своих серверов не нужно: список серверов лежит в APK
 * и обновляется с сервера Psiphon.
 * <p>
 * Psiphon работает отдельной программой (libpsiphon.so) и открывает локальный SOCKS-прокси,
 * в который VPN заворачивает трафик (как с Tor). Программа пишет сообщения строками JSON.
 * Методы и колбэки выполняются в рабочем потоке (handler).
 */
final class PsiphonController {

    interface Listener {
        /** Ход подключения: текст для экрана. */
        void onPsiphonProgress(String text);

        /** Туннель готов: SOCKS-порт и страна сервера (или null). */
        void onPsiphonReady(int socksPort, @Nullable String serverRegion);

        /** После подключения туннель пропал (false) или вернулся (true): Psiphon переподключается сам. */
        void onPsiphonTunnel(boolean up);

        /** Psiphon не подключился вовремя или завершился. */
        void onPsiphonError(String reason);
    }

    private static final String TAG = "PsiphonController";
    private static final String LIB = "libpsiphon.so";
    private static final String DIR = "psiphon";
    private static final String DATA_DIR = "data";
    private static final String CONFIG_FILE = "config.json";
    private static final String SERVER_LIST_ASSET = "psiphon/server_list.txt";
    private static final String SERVER_LIST_FILE = "server_list.txt";

    // Публичная сеть Psiphon: идентификаторы и список серверов с ключом подписи
    // (те же значения в scripts/build-psiphon.sh)
    private static final String PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF";
    private static final String SPONSOR_ID = "FFFFFFFFFFFFFFFF";
    private static final String SERVER_LIST_URL =
            "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed";
    private static final String SERVER_LIST_KEY = "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=";

    // Запуск процессов по очереди: новый Psiphon стартует, только когда прошлый завершён
    private static final Object LAUNCH_LOCK = new Object();
    private static final long STALE_WAIT_MS = 3_000;
    private static final long SUMMARY_MS = 20_000;
    private static final int MAX_LOGGED_WARNINGS = 20;
    private static final int MAX_MESSAGE = 200;

    private final Context context;
    private final Handler handler;

    // Поля ниже меняются только в потоке handler
    @Nullable private Listener listener;
    private int session;
    private boolean ready;
    private boolean tunnelUp;
    private int socksPort;
    private String egressRegion = "";
    private boolean regionFallback;
    @Nullable private String serverRegion;
    @Nullable private String protocol;
    private long timeoutMs;
    private String networkId = "UNKNOWN";
    @Nullable private String deviceRegion;

    // Процесс; сессия, сообщения которой сейчас разбираются (поток чтения)
    @Nullable private volatile Process process;
    private volatile int liveSession;

    private final Runnable timeout = this::onTimeout;

    PsiphonController(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    /** Есть ли Psiphon в APK (его нет в локальной сборке без Go и NDK). */
    static boolean isAvailable(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, LIB).isFile();
    }

    /**
     * Запуск Psiphon с выходом в стране countryCode (Country.ANY — любая).
     *
     * @param networkId тип сети для Psiphon: "WIFI", "MOBILE-25001", … (см. NetworkIDGetter в Psiphon)
     * @param timeoutMs ошибка, если туннель не готов за это время
     */
    void start(String countryCode, String networkId, @Nullable String deviceRegion,
               long timeoutMs, Listener l) {
        stop();
        this.networkId = networkId;
        this.deviceRegion = deviceRegion;
        this.timeoutMs = timeoutMs;
        regionFallback = false;
        launch(Country.isAny(countryCode) ? "" : countryCode.toUpperCase(Locale.ROOT), l);
        handler.postDelayed(timeout, timeoutMs);
    }

    private void launch(String region, Listener l) {
        listener = l;
        int s = ++session;
        liveSession = s;
        egressRegion = region;
        File exe = new File(context.getApplicationInfo().nativeLibraryDir, LIB);
        DiagLog.add("Psiphon: запуск, выход " + (region.isEmpty() ? "любой" : region)
                + ", сеть " + networkId.split("-")[0]);
        l.onPsiphonProgress(context.getString(R.string.psiphon_starting));
        String id = networkId;
        String device = deviceRegion;
        new Thread(() -> run(s, exe, region, id, device), "oda-psiphon").start();
    }

    /** Выбранной страны нет среди серверов Psiphon: выход в любой стране. */
    boolean isRegionFallback() {
        return regionFallback;
    }

    /** Остановка: процесс Psiphon завершается. */
    void stop() {
        listener = null;
        session++;
        liveSession = 0;
        handler.removeCallbacks(timeout);
        ready = false;
        tunnelUp = false;
        socksPort = 0;
        serverRegion = null;
        protocol = null;
        Process p = process;
        process = null;
        if (p != null) {
            DiagLog.add("Psiphon: остановка");
            p.destroy();
        }
    }

    /** Протокол готового туннеля (OSSH, FRONTED-MEEK-OSSH, …) или null. */
    @Nullable
    String protocol() {
        return protocol;
    }

    /** Страна сервера Psiphon (она же страна выхода) или null. */
    @Nullable
    String serverRegion() {
        return serverRegion;
    }

    // ---------- Поток oda-psiphon: запуск процесса и чтение сообщений ----------

    private void run(int s, File exe, String region, String networkId, @Nullable String deviceRegion) {
        Process p;
        synchronized (LAUNCH_LOCK) {
            // Пока ждали очереди, попытку могли отменить
            if (s != liveSession) return;
            try {
                if (!exe.isFile()) throw new IOException("нет " + LIB + " в APK");
                killStale();
                File dir = new File(context.getFilesDir(), DIR);
                File data = new File(dir, DATA_DIR);
                //noinspection ResultOfMethodCallIgnored
                data.mkdirs();
                File config = writeConfig(dir, data, region, networkId, deviceRegion);
                List<String> cmd = new ArrayList<>();
                cmd.add(exe.getAbsolutePath());
                cmd.add("-config");
                cmd.add(config.getAbsolutePath());
                cmd.add("-dataRootDirectory");
                cmd.add(data.getAbsolutePath());
                File list = serverList(dir);
                if (list != null) {
                    cmd.add("-serverList");
                    cmd.add(list.getAbsolutePath());
                }
                p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "start", e);
                String reason = "не запустился: " + e.getMessage();
                handler.post(() -> fail(s, reason));
                return;
            }
        }
        handler.post(() -> {
            if (s == session) {
                process = p;
            } else {
                p.destroy();
            }
        });
        new Reader(s).read(p);
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        int exit = code;
        handler.post(() -> onExit(s, exit));
    }

    /** Разбор сообщений Psiphon. Журнал пишется прямо отсюда, события — в поток handler. */
    private final class Reader {
        private final int s;
        private final Map<String, Integer> protocols = new LinkedHashMap<>();
        private final Set<String> loggedErrors = new HashSet<>();
        private int attempts;
        private int warnings;
        private long lastSummary = SystemClock.elapsedRealtime();
        private long lastShown;

        Reader(int s) {
            this.s = s;
        }

        void read(Process p) {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (s != liveSession) continue;  // дочитываем до конца, чтобы процесс не завис
                    handle(line);
                }
            } catch (IOException e) {
                Log.w(TAG, "read: " + e);
            }
            if (attempts > 0) summary();
        }

        private void handle(String line) {
            JSONObject notice;
            try {
                notice = new JSONObject(line);
            } catch (JSONException e) {
                if (!line.trim().isEmpty()) DiagLog.add("Psiphon: " + clip(line));
                return;
            }
            String type = notice.optString("noticeType");
            JSONObject data = notice.optJSONObject("data");
            if (data == null) data = new JSONObject();
            switch (type) {
                case "BuildInfo":
                    DiagLog.add("Psiphon: сборка " + clip(data.optString("buildRev")));
                    break;
                case "ListeningSocksProxyPort": {
                    int port = data.optInt("port");
                    handler.post(() -> onSocksPort(s, port));
                    break;
                }
                case "SocksProxyPortInUse":
                    DiagLog.add("Psiphon: порт SOCKS занят");
                    break;
                case "CandidateServers":
                    DiagLog.add("Psiphon: серверов-кандидатов " + data.optInt("count")
                            + (data.optString("region").isEmpty() ? "" : " в " + data.optString("region")));
                    break;
                case "AvailableEgressRegions": {
                    JSONArray regions = data.optJSONArray("regions");
                    String list = regions != null ? join(regions) : "";
                    DiagLog.add("Psiphon: доступные страны выхода " + (list.isEmpty() ? "—" : list));
                    handler.post(() -> onRegions(s, list));
                    break;
                }
                case "RemoteServerListResourceDownloaded":
                    DiagLog.add("Psiphon: список серверов обновлён");
                    break;
                case "ConnectingServer": {
                    attempts++;
                    String proto = data.optString("protocol", "?");
                    Integer n = protocols.get(proto);
                    protocols.put(proto, n == null ? 1 : n + 1);
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastSummary >= SUMMARY_MS) {
                        lastSummary = now;
                        summary();
                    }
                    // Число попыток на экране — не чаще раза в секунду
                    if (now - lastShown >= 1_000) {
                        lastShown = now;
                        int a = attempts;
                        handler.post(() -> onAttempts(s, a));
                    }
                    break;
                }
                case "ConnectedServer":
                    DiagLog.add("Psiphon: сервер ответил (" + data.optString("protocol")
                            + ", " + data.optString("region") + ")");
                    break;
                case "ActiveTunnel": {
                    String proto = data.optString("protocol");
                    DiagLog.add("Psiphon: туннель через " + proto);
                    handler.post(() -> onProtocol(s, proto));
                    break;
                }
                case "ConnectedServerRegion": {
                    String region = data.optString("serverRegion");
                    handler.post(() -> onServerRegion(s, region));
                    break;
                }
                case "ClientRegion":
                    DiagLog.add("Psiphon: ваша страна по IP — " + data.optString("region"));
                    break;
                case "Tunnels": {
                    int count = data.optInt("count");
                    DiagLog.add("Psiphon: туннелей " + count);
                    handler.post(() -> onTunnels(s, count));
                    break;
                }
                case "EstablishTunnelTimeout":
                    DiagLog.add("Psiphon: не подключился за " + data.optString("timeout"));
                    break;
                case "ServerAlert":
                    DiagLog.add("Psiphon: предупреждение сервера " + clip(data.toString()));
                    break;
                case "Error": {
                    String msg = data.optString("message");
                    // Одинаковые ошибки повторяются десятки раз: в журнал — первая
                    String key = msg.length() > 60 ? msg.substring(0, 60) : msg;
                    if (loggedErrors.add(key)) DiagLog.add("Psiphon ошибка: " + clip(msg));
                    break;
                }
                case "Warning": {
                    String msg = data.optString("message");
                    String key = msg.length() > 60 ? msg.substring(0, 60) : msg;
                    if (warnings < MAX_LOGGED_WARNINGS && loggedErrors.add(key)) {
                        warnings++;
                        DiagLog.add("Psiphon: " + clip(msg));
                    }
                    break;
                }
                case "Exiting":
                    DiagLog.add("Psiphon завершается");
                    break;
                default:
                    // Info и прочие подробности в журнал не пишем
                    break;
            }
        }

        private void summary() {
            StringBuilder sb = new StringBuilder("Psiphon: попыток ").append(attempts).append(" (");
            boolean first = true;
            for (Map.Entry<String, Integer> e : protocols.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(' ').append(e.getValue());
                first = false;
            }
            DiagLog.add(sb.append(')').toString());
        }
    }

    // ---------- Поток handler: состояние ----------

    private void onSocksPort(int s, int port) {
        if (s != session) return;
        socksPort = port;
        DiagLog.add("Psiphon: SOCKS порт " + port);
        maybeReady();
    }

    /** Выбранной страны нет в списке Psiphon: перезапуск с выходом в любой стране. */
    private void onRegions(int s, String regions) {
        Listener l = listener;
        if (s != session || l == null || ready || egressRegion.isEmpty() || regions.isEmpty()) return;
        for (String r : regions.split(" ")) {
            if (r.equals(egressRegion)) return;
        }
        DiagLog.add("Psiphon: страны " + egressRegion + " нет среди серверов, выход в любой стране");
        regionFallback = true;
        Process p = process;
        process = null;
        if (p != null) p.destroy();
        launch("", l);
    }

    private void onAttempts(int s, int attempts) {
        Listener l = listener;
        if (s != session || l == null || ready) return;
        l.onPsiphonProgress(context.getString(R.string.psiphon_searching, attempts));
    }

    private void onProtocol(int s, String proto) {
        if (s == session) protocol = proto;
    }

    private void onServerRegion(int s, String region) {
        if (s == session && !region.isEmpty()) serverRegion = region;
    }

    private void onTunnels(int s, int count) {
        Listener l = listener;
        if (s != session || l == null) return;
        boolean up = count > 0;
        if (!ready) {
            tunnelUp = up;
            maybeReady();
            return;
        }
        if (up != tunnelUp) {
            tunnelUp = up;
            l.onPsiphonTunnel(up);
        }
    }

    private void maybeReady() {
        Listener l = listener;
        if (l == null || ready || !tunnelUp || socksPort <= 0) return;
        ready = true;
        handler.removeCallbacks(timeout);
        DiagLog.add("Psiphon подключён: " + (protocol != null ? protocol : "?")
                + ", сервер " + (serverRegion != null ? serverRegion : "?"));
        l.onPsiphonReady(socksPort, serverRegion);
    }

    private void onTimeout() {
        Listener l = listener;
        if (l == null || ready) return;
        DiagLog.add("Psiphon: туннель не готов за " + timeoutMs / 1000 + " с");
        stop();
        l.onPsiphonError(context.getString(R.string.psiphon_timeout));
    }

    private void onExit(int s, int code) {
        if (s != session) return;
        DiagLog.add("Psiphon: процесс завершился, код " + code);
        fail(s, "завершился (код " + code + ")");
    }

    private void fail(int s, String reason) {
        Listener l = listener;
        if (s != session || l == null) return;
        stop();
        l.onPsiphonError(reason);
    }

    // ---------- Файлы ----------

    private File writeConfig(File dir, File data, String region, String networkId,
                             @Nullable String deviceRegion) throws IOException, JSONException {
        JSONObject c = new JSONObject();
        c.put("PropagationChannelId", PROPAGATION_CHANNEL_ID);
        c.put("SponsorId", SPONSOR_ID);
        c.put("ClientPlatform", "Android_" + Build.VERSION.RELEASE + "_" + context.getPackageName());
        c.put("ClientAPILevel", Build.VERSION.SDK_INT);
        c.put("DataRootDirectory", data.getAbsolutePath());
        c.put("RemoteServerListURLs", new JSONArray().put(new JSONObject()
                .put("URL", Base64.encodeToString(
                        SERVER_LIST_URL.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                .put("OnlyAfterAttempts", 0)));
        c.put("RemoteServerListSignaturePublicKey", SERVER_LIST_KEY);
        c.put("EgressRegion", region);
        c.put("NetworkID", networkId);
        if (deviceRegion != null) c.put("DeviceRegion", deviceRegion.toUpperCase(Locale.ROOT));
        // Нужен только SOCKS: в него заворачивает трафик tun2socks
        c.put("DisableLocalHTTPProxy", true);
        c.put("LocalSocksProxyPort", 0);
        // Таймаут подключения считает приложение, сам Psiphon не сдаётся
        c.put("EstablishTunnelTimeoutSeconds", 0);
        // Подробные сообщения (адреса серверов в них скрыты) — для журнала
        c.put("EmitDiagnosticNotices", true);
        c.put("EmitServerAlerts", true);
        File file = new File(dir, CONFIG_FILE);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            w.write(c.toString());
        }
        return file;
    }

    /** Список серверов из APK (копируется в файл после установки или обновления приложения). */
    @Nullable
    private File serverList(File dir) {
        File out = new File(dir, SERVER_LIST_FILE);
        long installed;
        try {
            installed = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            installed = 0;
        }
        if (out.isFile() && out.length() > 0 && out.lastModified() >= installed) return out;
        File tmp = new File(dir, SERVER_LIST_FILE + ".tmp");
        try (InputStream in = context.getAssets().open(SERVER_LIST_ASSET);
             OutputStream os = new FileOutputStream(tmp, false)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException e) {
            // В APK списка нет: Psiphon скачает его сам
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return out.isFile() ? out : null;
        }
        if (!tmp.renameTo(out)) return null;
        return out;
    }

    /**
     * Psiphon, оставшийся от прошлого запуска, держит свою базу: новый не запустится.
     * Ищем такие процессы своего приложения в /proc и завершаем.
     */
    private static void killStale() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        int self = android.os.Process.myPid();
        List<File> killed = new ArrayList<>();
        for (File f : procs) {
            String name = f.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self) continue;
            String cmdline = readCmdline(new File(f, "cmdline"));
            if (cmdline == null || !cmdline.contains(LIB)) continue;
            DiagLog.add("Psiphon: завершаю прошлый процесс " + pid);
            android.os.Process.killProcess(pid);
            killed.add(f);
        }
        long deadline = SystemClock.elapsedRealtime() + STALE_WAIT_MS;
        for (File f : killed) {
            while (f.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        }
    }

    @Nullable
    private static String readCmdline(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static String join(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(arr.optString(i));
        }
        return sb.toString();
    }

    private static String clip(String s) {
        return s.length() > MAX_MESSAGE ? s.substring(0, MAX_MESSAGE) + "…" : s;
    }
}
