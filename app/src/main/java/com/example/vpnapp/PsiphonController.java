package com.example.vpnapp;

import android.content.Context;
import android.content.SharedPreferences;
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
    private static final long HEALTH_INTERVAL_MS = 45_000;
    private static final long HEALTH_RETRY_MS = 2_500;
    private static final long RECOVERY_TIMEOUT_MS = 40_000;
    private static final int MAX_LOGGED_WARNINGS = 20;
    private static final int MAX_MESSAGE = 200;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences routes;
    private final boolean latencyProbe;

    // Поля ниже меняются только в потоке handler
    @Nullable private Listener listener;
    private int session;
    private final PsiphonHealth health = new PsiphonHealth();
    private int checkingEpoch = -1;
    private int socksPort;
    private String selectedRegion = "";
    @Nullable private String serverRegion;
    @Nullable private String protocol;
    private long timeoutMs;
    private long latencyMs = -1;
    private String networkId = "UNKNOWN";
    @Nullable private String deviceRegion;

    // Процесс; сессия, сообщения которой сейчас разбираются (поток чтения)
    @Nullable private volatile Process process;
    private volatile int liveSession;

    private final Runnable timeout = this::onTimeout;
    private final Runnable healthCheck = this::checkHealth;
    private final Runnable recoveryTimeout = this::onRecoveryTimeout;

    PsiphonController(Context context, Handler handler) {
        this(context, handler, false);
    }

    PsiphonController(Context context, Handler handler, boolean latencyProbe) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        routes = this.context.getSharedPreferences("psiphon_routes", Context.MODE_PRIVATE);
        this.latencyProbe = latencyProbe;
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
        launch(PsiphonRoute.region(countryCode), l);
        handler.postDelayed(timeout, timeoutMs);
    }

    private void launch(String region, Listener l) {
        listener = l;
        selectedRegion = region;
        int s = ++session;
        liveSession = s;
        File exe = new File(context.getApplicationInfo().nativeLibraryDir, LIB);
        log("Psiphon: запуск, выход " + (region.isEmpty() ? "любой" : region)
                + ", сеть " + networkId.split("-")[0]);
        l.onPsiphonProgress(context.getString(R.string.psiphon_starting));
        String id = networkId;
        String device = deviceRegion;
        new Thread(() -> run(s, exe, region, id, device), "oda-psiphon").start();
    }

    /** Остановка: процесс Psiphon завершается. */
    void stop() {
        listener = null;
        session++;
        liveSession = 0;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(healthCheck);
        handler.removeCallbacks(recoveryTimeout);
        health.reset();
        checkingEpoch = -1;
        socksPort = 0;
        latencyMs = -1;
        serverRegion = null;
        protocol = null;
        Process p = process;
        process = null;
        if (p != null) {
            log("Psiphon: остановка");
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

    long latencyMs() {
        return latencyMs;
    }

    // ---------- Поток oda-psiphon: запуск процесса и чтение сообщений ----------

    private void run(int s, File exe, String region, String networkId, @Nullable String deviceRegion) {
        Process p;
        synchronized (LAUNCH_LOCK) {
            // Пока ждали очереди, попытку могли отменить
            if (s != liveSession) return;
            try {
                if (!exe.isFile()) throw new IOException("нет " + LIB + " в APK");
                File dir = new File(context.getFilesDir(), DIR);
                String dataName = PsiphonRoute.cacheDirectory(region);
                File data = new File(latencyProbe ? new File(dir, "latency") : dir, dataName);
                //noinspection ResultOfMethodCallIgnored
                data.mkdirs();
                File config = new File(latencyProbe ? data : dir, CONFIG_FILE);
                killStale(exe, config);
                writeConfig(config, data, region, networkId, deviceRegion);
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
                if (s != liveSession) return;
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
                if (!line.trim().isEmpty()) log("Psiphon: " + clip(line));
                return;
            }
            String type = notice.optString("noticeType");
            JSONObject data = notice.optJSONObject("data");
            if (data == null) data = new JSONObject();
            switch (type) {
                case "BuildInfo":
                    log("Psiphon: сборка " + clip(data.optString("buildRev")));
                    break;
                case "ListeningSocksProxyPort": {
                    int port = data.optInt("port");
                    handler.post(() -> onSocksPort(s, port));
                    break;
                }
                case "SocksProxyPortInUse":
                    log("Psiphon: порт SOCKS занят");
                    break;
                case "CandidateServers":
                    log("Psiphon: серверов-кандидатов " + data.optInt("count")
                            + (data.optString("region").isEmpty() ? "" : " в " + data.optString("region")));
                    break;
                case "AvailableEgressRegions": {
                    JSONArray regions = data.optJSONArray("regions");
                    String list = regions != null ? join(regions) : "";
                    log("Psiphon: доступные страны выхода " + (list.isEmpty() ? "—" : list));
                    break;
                }
                case "RemoteServerListResourceDownloaded":
                    log("Psiphon: список серверов обновлён");
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
                    break;
                }
                case "ConnectedServer": {
                    String proto = data.optString("protocol");
                    String region = data.optString("region");
                    log("Psiphon: сервер ответил (" + proto + ", " + region + ")");
                    // ActiveTunnel и ConnectedServerRegion приходят позже «туннелей 1»: без этого
                    // в журнале было «Psiphon подключён: ?, сервер ?»
                    handler.post(() -> onConnectedServer(s, proto, region));
                    break;
                }
                case "ActiveTunnel": {
                    String proto = data.optString("protocol");
                    log("Psiphon: туннель через " + proto);
                    handler.post(() -> onProtocol(s, proto));
                    break;
                }
                case "ConnectedServerRegion": {
                    String region = data.optString("serverRegion");
                    handler.post(() -> onServerRegion(s, region));
                    break;
                }
                case "ClientRegion":
                    log("Psiphon: ваша страна по IP — " + data.optString("region"));
                    break;
                case "Tunnels": {
                    int count = data.optInt("count");
                    log("Psiphon: туннелей " + count);
                    handler.post(() -> onTunnels(s, count));
                    break;
                }
                case "EstablishTunnelTimeout":
                    log("Psiphon: не подключился за " + data.optString("timeout"));
                    break;
                case "ServerAlert":
                    log("Psiphon: предупреждение сервера " + clip(data.toString()));
                    break;
                case "Error": {
                    String msg = data.optString("message");
                    // Одинаковые ошибки повторяются десятки раз: в журнал — первая
                    String key = msg.length() > 60 ? msg.substring(0, 60) : msg;
                    if (loggedErrors.add(key)) log("Psiphon ошибка: " + clip(msg));
                    break;
                }
                case "Warning": {
                    String msg = data.optString("message");
                    String key = msg.length() > 60 ? msg.substring(0, 60) : msg;
                    if (warnings < MAX_LOGGED_WARNINGS && loggedErrors.add(key)) {
                        warnings++;
                        log("Psiphon: " + clip(msg));
                    }
                    break;
                }
                case "Exiting":
                    log("Psiphon завершается");
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
            log(sb.append(')').toString());
        }
    }

    // ---------- Поток handler: состояние ----------

    private void onSocksPort(int s, int port) {
        if (s != session || port == socksPort) return;
        socksPort = port;
        log("Psiphon: SOCKS порт " + port);
        if (health.transportUp() && port > 0) handler.post(healthCheck);
    }

    private void onConnectedServer(int s, String proto, String region) {
        if (s != session) return;
        if (protocol == null && !proto.isEmpty()) protocol = proto;
        onServerRegion(s, region);
    }

    private void onProtocol(int s, String proto) {
        if (s == session) protocol = proto;
    }

    private void onServerRegion(int s, String region) {
        if (s != session || region.isEmpty()) return;
        if (!PsiphonRoute.acceptsRegion(selectedRegion, region)) {
            log("Psiphon: выбран " + selectedRegion + ", сервер сообщил " + region);
            fail(s, context.getString(R.string.psiphon_wrong_country));
            return;
        }
        serverRegion = region;
    }

    private void onTunnels(int s, int count) {
        Listener l = listener;
        if (s != session || l == null) return;
        boolean up = count > 0;
        if (up == health.transportUp()) return;
        PsiphonHealth.Change change = health.transport(up);
        handler.removeCallbacks(healthCheck);
        if (change == PsiphonHealth.Change.LOST) reportLoss(l);
        if (up && socksPort > 0) {
            if (!health.connectedBefore()) {
                l.onPsiphonProgress(context.getString(R.string.detail_checking));
            }
            handler.post(healthCheck);
        }
    }

    private void checkHealth() {
        if (listener == null || !health.transportUp() || socksPort <= 0) return;
        int s = session;
        int epoch = health.epoch();
        if (checkingEpoch == epoch) return;
        checkingEpoch = epoch;
        int port = socksPort;
        new Thread(() -> {
            long latency = NetProbe.psiphonLatency(port);
            handler.post(() -> onHealth(s, epoch, latency));
        }, "oda-psiphon-health").start();
    }

    private void onHealth(int s, int epoch, long latency) {
        Listener l = listener;
        if (s != session || l == null || epoch != health.epoch()) return;
        checkingEpoch = -1;
        boolean ok = latency >= 0;
        if (ok) latencyMs = latency;
        PsiphonHealth.Change change = health.probe(epoch, ok);
        if (change == PsiphonHealth.Change.READY) {
            handler.removeCallbacks(timeout);
            log("Psiphon: HTTPS доступен, " + protocol + ", сервер " + serverRegion);
            if (!latencyProbe && serverRegion != null && protocol != null
                    && PsiphonRoute.priority(PsiphonRoute.region(serverRegion))
                    && PsiphonRoute.learnable(protocol)) {
                routes.edit().putString(PsiphonRoute.historyKey(serverRegion, networkId), protocol).apply();
            }
            l.onPsiphonReady(socksPort, serverRegion);
        } else if (change == PsiphonHealth.Change.RESTORED) {
            handler.removeCallbacks(recoveryTimeout);
            l.onPsiphonTunnel(true);
        } else if (change == PsiphonHealth.Change.LOST) {
            reportLoss(l);
        }
        if (s == session && epoch == health.epoch()) {
            handler.postDelayed(healthCheck, ok ? HEALTH_INTERVAL_MS : HEALTH_RETRY_MS);
        }
    }

    void recheckNetwork() {
        handler.removeCallbacks(healthCheck);
        handler.post(healthCheck);
    }

    private void reportLoss(Listener l) {
        handler.removeCallbacks(recoveryTimeout);
        handler.postDelayed(recoveryTimeout, RECOVERY_TIMEOUT_MS);
        l.onPsiphonTunnel(false);
    }

    private void onRecoveryTimeout() {
        fail(session, context.getString(R.string.psiphon_recovery_timeout));
    }

    private void onTimeout() {
        if (listener == null || health.connectedBefore()) return;
        log("Psiphon: туннель не готов за " + timeoutMs / 1000 + " с");
        fail(session, context.getString(R.string.psiphon_timeout));
    }

    private void onExit(int s, int code) {
        if (s != session) return;
        log("Psiphon: процесс завершился, код " + code);
        fail(s, "завершился (код " + code + ")");
    }

    private void fail(int s, String reason) {
        Listener l = listener;
        if (s != session || l == null) return;
        stop();
        l.onPsiphonError(reason);
    }

    // ---------- Файлы ----------

    private void writeConfig(File file, File data, String region, String networkId,
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
        // Публичная сборка использует подписанный основной список, ключей отдельного DSL-каталога нет.
        c.put("DisableDSLFetcher", true);
        c.put("EgressRegion", region);
        String confirmed = routes.getString(PsiphonRoute.historyKey(region, networkId), "");
        List<String> transports = PsiphonRoute.initialTransports(region, networkId, confirmed);
        if (!transports.isEmpty()) {
            c.put("InitialLimitTunnelProtocols", new JSONArray(transports));
            c.put("InitialLimitTunnelProtocolsCandidateCount", PsiphonRoute.initialCandidateCount(transports));
            log("Psiphon: приоритет " + transports + ", затем все транспорты");
        }
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
        if (latencyProbe) c.put("ConnectionWorkerPoolMaxSize", 5);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            w.write(c.toString());
        }
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
    private void killStale(File exe, File config) {
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
            if (cmdline == null || !cmdline.startsWith(exe.getAbsolutePath() + '\0')
                    || !cmdline.contains("\0-config\0" + config.getAbsolutePath() + "\0")) continue;
            log("Psiphon: завершаю прошлый процесс " + pid);
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

    private void log(String message) {
        if (!latencyProbe) DiagLog.add(message);
    }

    private static String join(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(arr.optString(i));
        }
        return sb.toString();
    }

    /** Начало и конец длинного сообщения: у Psiphon причина — в конце цепочки «функция#строка: …». */
    private static String clip(String s) {
        if (s.length() <= MAX_MESSAGE) return s;
        int half = MAX_MESSAGE / 2;
        return s.substring(0, half) + " … " + s.substring(s.length() - half);
    }
}
