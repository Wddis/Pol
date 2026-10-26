package com.example.vpnapp;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * VPN: весь трафик телефона → tun → tun2socks → локальный SOCKS5 (Psiphon или Tor).
 * В режиме «Авто» Psiphon и Tor подключаются одновременно, работает тот, кто успел первым,
 * второй останавливается. Tor по очереди пробует мосты (см. ConnectPlan).
 * Вся логика идёт в потоке worker, UI узнаёт о состоянии через StatusBus.
 */
public class VpnTunnelService extends VpnService
        implements TorController.Listener, PsiphonController.Listener, BypassController.Listener {

    public static final String ACTION_START = "com.example.vpnapp.action.START";
    public static final String ACTION_STOP = "com.example.vpnapp.action.STOP";
    public static final String ACTION_COUNTRY = "com.example.vpnapp.action.COUNTRY";
    public static final String ACTION_RECONNECT = "com.example.vpnapp.action.RECONNECT";

    private static final String TAG = "VpnTunnelService";
    private static final int IP_CHECK_ATTEMPTS = 2;
    // Сколько ждём ответа Moat (оба запроса идут параллельно)
    private static final long MOAT_DEADLINE_MS = 50_000;
    // Пределы, пока ни один мост (узел) не ответил. Когда ответил, канал рабочий,
    // и TorController сам даёт попытке больше времени на построение цепочки
    // Без мостов: 45 с на подключение, при застревании прогресса — 30 с
    private static final long DIRECT_STALL_MS = 30_000;
    private static final long DIRECT_HARD_MS = 45_000;
    private static final long BRIDGE_STALL_MS = 60_000;
    private static final long BRIDGE_HARD_MS = 120_000;
    // Snowflake дольше ищет добровольца
    private static final long SNOWFLAKE_STALL_MS = 90_000;
    private static final long SNOWFLAKE_HARD_MS = 150_000;
    // DNSTT очень медленный: даём больше времени
    private static final long DNSTT_STALL_MS = 120_000;
    private static final long DNSTT_HARD_MS = 240_000;
    // Psiphon сам перебирает серверы и способы маскировки: столько ждём первого туннеля
    private static final long PSIPHON_TIMEOUT_MS = 8 * 60_000;
    // Перезапуски Psiphon, если его процесс завершился после подключения
    private static final int PSIPHON_RESTARTS = 3;
    // Перезапуски ByeDPI, если его процесс завершился после подключения
    private static final int BYPASS_RESTARTS = 3;
    // Если полный перебор недавно не нашёл стратегию для соцсетей, в режиме «Обход DPI»
    // столько времени сразу берём запомненную для обычного интернета, без нового перебора
    private static final long BYPASS_QUICK_BASIC_MS = 12 * 60 * 60_000L;
    // Сторож обхода DPI: как часто проверять DNS и сайт через ByeDPI после подключения
    private static final long BYPASS_WATCH_MS = 30_000;
    // Столько проверок подряд не прошло при живой сети — перезапуск ByeDPI
    private static final int BYPASS_WATCH_FAILS = 2;
    // Счётчики туннеля в журнал: при первой проверке, при сбоях и раз в столько проверок
    private static final int BYPASS_WATCH_STATS_EVERY = 10;
    // Столько разных строк вывода ByeDPI за одну проверку попадает в журнал
    private static final int BYPASS_OUTPUT_LINES = 5;
    // Столько новых адресов, не открывшихся ни одной стратегией, проверяем за раз (почему)
    private static final int BYPASS_UNREACH_CHECKS = 2;

    private HandlerThread thread;
    private Handler worker;
    private TorController tor;
    private PsiphonController psiphon;
    private final BypassController[] bypass = new BypassController[BypassSearch.PARALLEL];
    private PtController pt;
    private HelperRelay relay;
    private Prefs prefs;

    // Поля ниже меняются только в потоке worker
    @Nullable private ParcelFileDescriptor tun;
    private boolean active;
    private boolean tunnelRunning;
    private int socksPort;
    private int generation;  // отсекает устаревшие результаты фоновых задач
    private int connection;  // номер подключения: отсекает ответы прошлого подключения
    private List<BridgeSet> plan = new ArrayList<>();
    private int attempt;
    @Nullable private String lastReason;
    // Чем подключены (StatusBus.ENGINE_*); пока не подключены — null
    @Nullable private String engine;
    private boolean torActive;      // Tor пробует способы или подключён
    private boolean psiphonActive;  // Psiphon подключается или подключён
    private boolean psiphonUsed;    // в этом подключении пробовали Psiphon
    private int psiphonRestarts;
    private String psiphonText = "";
    private boolean bypassActive;   // обход DPI перебирает стратегии или подключён
    @Nullable private BypassSearch bypassSearch;
    // Номер запуска в каждом слоте: отсекает результаты проверки прошлого запуска
    private final int[] bypassRun = new int[BypassSearch.PARALLEL];
    private int bypassSlot = -1;    // слот, через который подключены
    private int bypassRestarts;
    private boolean bypassFull;     // обход пробил заблокированные сайты (не только обычные)
    private int bypassWatch;        // поколение сторожа: отсекает проверки прошлого подключения
    private int bypassWatchFails;
    private int bypassWatchTicks;
    private String bypassText = "";
    // Запасной путь обхода DPI (только «Авто»): то, что напрямую не открывается (Telegram,
    // адреса, закрытые по IP), ByeDPI отправляет через HelperRelay в Psiphon
    private int helperPort;          // порт HelperRelay для ByeDPI в этом подключении, 0 — без него
    private int helperPsiphonPort;   // SOCKS Psiphon, когда он готов как запасной путь
    private boolean helperHinted;    // подсказка про «Авто» в журнале уже была
    // Режим «Обход DPI»: Psiphon запущен только как запасной путь и сам подключением не станет
    private boolean psiphonHelperOnly;
    // DNS телефона при обходе DPI: тот из NetProbe.DNS_SERVERS, что ответил через стратегию
    private final Map<BypassProfile, String> bypassDnsOf = new IdentityHashMap<>();
    private String bypassDns = NetProbe.DNS_SERVERS[0];
    @Nullable private String bypassIp;
    @Nullable private String bypassCc;
    private int torPercent;
    private int torError = R.string.error_blocked;
    private String networkId = "UNKNOWN";
    @Nullable private String netCountry;
    @Nullable private NetProbe.Result probe;
    @Nullable private BypassSearch.Score directScore;  // прямая проверка: эталон для обхода DPI
    private boolean directDone;
    @Nullable private String tunDns;  // реальный DNS текущего tun (обход DPI); null — фиктивный
    private boolean underlyingIpv6;  // у сети оператора есть глобальный IPv6

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        DiagLog.init(this);
        thread = new HandlerThread("oda-vpn");
        thread.start();
        worker = new Handler(thread.getLooper());
        tor = new TorController(this, worker);
        psiphon = new PsiphonController(this, worker);
        for (int i = 0; i < bypass.length; i++) bypass[i] = new BypassController(this, worker, i);
        pt = new PtController(this);
        relay = new HelperRelay(HelperRelay.PORT);
        VpnNotification.ensureChannel(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            worker.post(this::shutdown);
            return START_NOT_STICKY;
        }
        // Foreground нужно объявить сразу, иначе система остановит сервис
        if (!goForeground(getString(R.string.status_connecting))) {
            StatusBus.post(ConnectionState.ERROR, "", getString(R.string.error_foreground), 0, "");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_COUNTRY.equals(action)) {
            worker.post(this::changeCountry);
        } else if (ACTION_RECONNECT.equals(action)) {
            worker.post(this::reconnect);
        } else {
            // ACTION_START или запуск системой («Постоянная VPN»)
            worker.post(this::connect);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        // Пользователь отключил VPN в системе или включил другую VPN
        worker.post(() -> {
            teardown();
            publish(ConnectionState.OFF, "", "");
            finish();
        });
    }

    @Override
    public void onDestroy() {
        worker.post(() -> {
            if (active) {
                teardown();
                publish(ConnectionState.OFF, "", "");
            }
        });
        thread.quitSafely();
        super.onDestroy();
    }

    // ---------- Работа в потоке worker ----------

    private void connect() {
        if (active) return;
        active = true;
        int id = ++connection;
        netCountry = networkCountry();
        // Тип сети определяем до подъёма VPN, пока активна сеть оператора
        networkId = networkId();
        underlyingIpv6 = underlyingHasIpv6();
        engine = null;
        probe = null;
        directScore = null;
        directDone = false;
        psiphonRestarts = 0;
        psiphonText = "";
        bypassText = "";
        helperPort = 0;
        helperPsiphonPort = 0;
        helperHinted = false;
        psiphonHelperOnly = false;
        bypassDnsOf.clear();
        bypassDns = NetProbe.DNS_SERVERS[0];
        bypassIp = null;
        bypassCc = null;
        torPercent = 0;
        lastReason = null;
        torError = R.string.error_blocked;
        BridgeMode mode = prefs.getMode();
        boolean psiphonBuilt = PsiphonController.isAvailable(this);
        boolean bypassBuilt = BypassController.isAvailable(this);
        psiphonUsed = (mode == BridgeMode.AUTO || mode == BridgeMode.PSIPHON) && psiphonBuilt;
        boolean bypassUsed = (mode == BridgeMode.AUTO || mode == BridgeMode.BYPASS) && bypassBuilt;
        DiagLog.add("========== новое подключение ==========");
        DiagLog.add("ODA " + appVersion() + ", Android " + Build.VERSION.RELEASE
                + ", режим " + mode + ", страна " + prefs.getCountry()
                + ", сеть " + networkId.split("-")[0] + " " + netCountry
                + (underlyingIpv6 ? ", IPv6 есть" : ", IPv6 нет"));
        // Интерфейс поднимаем сразу: пока туннель не готов, трафик блокируется, а не уходит мимо
        try {
            tun = establishTun(false);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "establish", e);
            fail(R.string.error_tun);
            return;
        }
        NetProbe.resetDns();
        probeNetwork(id);
        if (mode == BridgeMode.PSIPHON && !psiphonBuilt) {
            fail(R.string.error_psiphon_missing);
            return;
        }
        if (mode == BridgeMode.BYPASS && !bypassBuilt) {
            fail(R.string.error_bypass_missing);
            return;
        }
        // «Авто» и «Обход DPI»: если подключится обход DPI, Psiphon — запасной путь для того,
        // что напрямую не открывается (Telegram, адреса, закрытые по IP). В «Обходе DPI» Psiphon
        // запускается только для этого и сам подключением не становится
        boolean helperOnly = mode == BridgeMode.BYPASS && bypassUsed && psiphonBuilt;
        if ((mode == BridgeMode.AUTO && bypassUsed && psiphonUsed) || helperOnly) {
            relay.setUpstream(0);
            if (relay.start()) {
                helperPort = HelperRelay.PORT;
            } else {
                DiagLog.add("Запасной путь: порт " + HelperRelay.PORT + " занят, обход DPI без него");
            }
        }
        // Обход DPI запускаем первым: когда срабатывает, он самый быстрый (прямое соединение)
        if (bypassUsed) startBypass();
        if (psiphonUsed) {
            startPsiphon();
        } else if (helperOnly && helperPort > 0) {
            psiphonHelperOnly = true;
            DiagLog.add("Psiphon подключается в фоне — только как запасной путь для Telegram и того, "
                    + "что обход DPI не откроет");
            startPsiphon();
        } else if (mode == BridgeMode.AUTO && !bypassBuilt) {
            DiagLog.add("Psiphon нет в этой сборке: только Tor");
        }
        if (mode == BridgeMode.AUTO) startTor(id);
    }

    private void startBypass() {
        bypassActive = true;
        bypassSlot = -1;
        bypassRestarts = 0;
        BypassProfile custom = BypassProfile.parse(getString(R.string.bypass_custom_label), prefs.getByedpiArgs());
        Prefs.BypassBest best = custom == null ? prefs.getBypassBest(networkId) : null;
        // Сразу брать «только обычный интернет» — лишь в режиме «Обход DPI»: в «Авто» у Psiphon
        // и Tor должно остаться время открыть и соцсети
        boolean quick = best != null && !best.full && prefs.getMode() == BridgeMode.BYPASS
                && System.currentTimeMillis() - best.time < BYPASS_QUICK_BASIC_MS;
        if (best != null) {
            DiagLog.add("Обход DPI: в этой сети в прошлый раз «" + best.profile.label + "» ("
                    + (best.full ? "соцсети открывались" : "только обычные сайты") + ")");
        }
        bypassSearch = new BypassSearch(custom, best != null ? best.profile : null,
                best != null && best.full, quick);
        if (directDone) bypassSearch.setBaseline(directScore);
        startBypassBatch(bypassSearch.currentBatch());
    }

    /** Запустить пачку стратегий одновременно: каждая в своём слоте (свой процесс и порт). */
    private void startBypassBatch(List<BypassProfile> batch) {
        stopBypassSlots();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < batch.size() && i < bypass.length; i++) {
            bypassRun[i]++;
            bypass[i].start(batch.get(i), helperPort, this);
            // На экране у цепочки — только сильная часть: «split SNI → oob SNI» → «oob SNI»
            String label = batch.get(i).label;
            names.add("«" + label.substring(label.lastIndexOf("→ ") + 1).trim() + "»");
        }
        bypassText = getString(R.string.bypass_trying, TextUtils.join(", ", names));
        if (engine == null) publish(ConnectionState.CONNECTING, progressText(), "");
    }

    private void stopBypassSlots() {
        for (BypassController b : bypass) b.stop();
    }

    private void startPsiphon() {
        psiphonActive = true;
        psiphon.start(prefs.getCountry(), networkId, netCountry, PSIPHON_TIMEOUT_MS, this);
    }

    /** Tor: план мостов; в «Авто» — с запросом рабочих мостов у Moat. */
    private void startTor(int id) {
        torActive = true;
        String country = netCountry;
        if (!ConnectPlan.needsMoat(prefs.getMode())) {
            startPlan(ConnectPlan.build(this, prefs, null, country));
            return;
        }
        // Ждём ответа Moat, только если начать не с чего: нет прошлого рабочего моста,
        // нет мостов Moat с прошлого раза и сеть без известных блокировок
        boolean waitMoat = prefs.getLastGood() == null && prefs.getMoatCache().isEmpty()
                && !ConnectPlan.isCensored(country);
        if (waitMoat) {
            publish(ConnectionState.CONNECTING, progressText(), "");
            DiagLog.add("Moat: запрос рабочих мостов…");
        } else {
            DiagLog.add("Moat: запрос в фоне, подключение его не ждёт");
            startPlan(ConnectPlan.build(this, prefs, null, country));
            if (!active || !torActive) return;
        }
        Context app = getApplicationContext();
        new Thread(() -> {
            MoatClient.Result moat = null;
            try {
                moat = MoatClient.fetch(app, country, MOAT_DEADLINE_MS);
                if (!moat.sets.isEmpty()) prefs.setMoatCache(moat.sets);
            } catch (Exception e) {
                DiagLog.add("Moat не ответил, беру встроенные мосты");
            }
            MoatClient.Result result = moat;
            worker.post(() -> onMoat(id, waitMoat, result, country));
        }, "oda-moat").start();
    }

    private void onMoat(int id, boolean waited, @Nullable MoatClient.Result result,
                        @Nullable String country) {
        // Уже подключены (например, через Psiphon) или Tor остановлен — мосты не нужны
        if (!active || id != connection || !torActive || engine != null) return;
        if (waited) {
            startPlan(ConnectPlan.build(this, prefs, result, country));
            return;
        }
        // Подключение уже идёт: свежие мосты Moat встают в план после текущей попытки
        if (result == null || socksPort != 0 || plan.isEmpty()) return;
        plan = ConnectPlan.merge(plan, attempt, ConnectPlan.build(this, prefs, result, country));
        logPlan("План обновлён по ответу Moat:");
    }

    /** Проверка сети напрямую: если закрыто всё зарубежное, подскажем про «белые списки». */
    private void probeNetwork(int id) {
        new Thread(() -> {
            NetProbe.Direct d = NetProbe.run();
            NetProbe.Result r = d.result;
            worker.post(() -> {
                if (!active || id != connection) return;
                probe = r;
                directScore = d.score;
                directDone = true;
                // Обход DPI ждёт этот результат как эталон обычного интернета
                if (bypassSearch != null && bypassActive && engine == null) {
                    bypassSearch.setBaseline(d.score);
                    decideBypass();
                }
                if (r == NetProbe.Result.OK) return;
                DiagLog.add(r == NetProbe.Result.WHITELIST
                        ? "Похоже на «белые списки»: зарубежные сайты закрыты даже напрямую"
                        : "Нет доступа в интернет даже напрямую");
                if (engine == null) publish(ConnectionState.CONNECTING, progressText(), "");
            });
        }, "oda-probe").start();
    }

    private void reconnect() {
        if (active) teardown();
        connect();
    }

    private void startPlan(List<BridgeSet> newPlan) {
        if (newPlan.isEmpty()) {
            torGaveUp(prefs.getMode() == BridgeMode.CUSTOM
                    ? R.string.error_no_custom_bridges : R.string.error_tor_start);
            return;
        }
        plan = newPlan;
        attempt = 0;
        logPlan("План Tor:");
        startAttempt();
    }

    private void logPlan(String title) {
        StringBuilder sb = new StringBuilder(title);
        for (BridgeSet set : plan) {
            sb.append(" [").append(set.label).append(": ")
                    .append(set.isDirect() ? "напрямую" : set.lines.size() + " мост., " + set.transports())
                    .append(']');
        }
        DiagLog.add(sb.toString());
    }

    /** Запуск Tor с текущим способом из плана. */
    private void startAttempt() {
        generation++;
        socksPort = 0;
        stopTunnel();
        BridgeSet set = plan.get(attempt);
        lastReason = null;
        torPercent = 0;
        DiagLog.add("Попытка Tor " + (attempt + 1) + "/" + plan.size() + ": " + set.label);
        publish(ConnectionState.CONNECTING, progressText(), "");
        List<String> bridgeTorrc;
        try {
            bridgeTorrc = pt.start(set);
        } catch (Exception e) {
            DiagLog.add("Транспорт не запустился: " + e);
            nextAttempt(R.string.error_tor_start);
            return;
        }
        long stall;
        long hard;
        if (set.isDirect()) {
            stall = DIRECT_STALL_MS;
            hard = DIRECT_HARD_MS;
        } else if (set.transports().contains("dnstt")) {
            stall = DNSTT_STALL_MS;
            hard = DNSTT_HARD_MS;
        } else if (set.transports().contains("snowflake")) {
            stall = SNOWFLAKE_STALL_MS;
            hard = SNOWFLAKE_HARD_MS;
        } else {
            stall = BRIDGE_STALL_MS;
            hard = BRIDGE_HARD_MS;
        }
        tor.start(prefs.getCountry(), bridgeTorrc, stall, hard, this);
    }

    private void nextAttempt(int lastError) {
        if (attempt < plan.size()) {
            // Не прошедшие в этой сети способы в следующий раз пойдут в конце
            BridgeSet failed = plan.get(attempt);
            if (failed.isDirect()) {
                prefs.setDirectFailed(true);
            } else if (getString(R.string.mode_obfs4).equals(failed.label)) {
                prefs.setObfs4Failed(true);
            }
        }
        tor.stop();
        pt.stop();
        attempt++;
        if (attempt < plan.size()) {
            startAttempt();
            return;
        }
        torGaveUp(lastError == R.string.error_timeout ? R.string.error_blocked : lastError);
    }

    /** Tor перебрал все способы: ждём Psiphon, если он ещё пробует, иначе ошибка. */
    private void torGaveUp(int error) {
        torActive = false;
        torError = error;
        tor.stop();
        pt.stop();
        // Сбрасываем «последний рабочий» мост: в этой сети он не прошёл
        prefs.setLastGood(null);
        if (psiphonActive || bypassActive) {
            DiagLog.add("Tor не подключился, другие способы продолжают");
            publish(ConnectionState.CONNECTING, progressText(), "");
            return;
        }
        failAll();
    }

    /** Не сработал ни один способ: ошибка с самой полезной подсказкой. */
    private void failAll() {
        DiagLog.add("Все способы не сработали");
        if (probe == NetProbe.Result.WHITELIST) {
            fail(R.string.error_whitelist);
        } else if (probe == NetProbe.Result.OFFLINE) {
            fail(R.string.error_offline);
        } else if (prefs.getMode() == BridgeMode.PSIPHON) {
            fail(R.string.error_psiphon_failed);
        } else if (prefs.getMode() == BridgeMode.BYPASS) {
            fail(R.string.error_bypass_failed);
        } else {
            fail(psiphonUsed ? R.string.error_all_blocked : torError);
        }
    }

    private void changeCountry() {
        if (!active) {
            connect();
            return;
        }
        if (StatusBus.ENGINE_BYPASS.equals(engine)) {
            // Обход DPI — прямое соединение, страна выхода к нему не применяется
            DiagLog.add("Обход DPI: смена страны не применяется (прямое соединение)");
            return;
        }
        if (StatusBus.ENGINE_PSIPHON.equals(engine)) {
            // Psiphon: перезапуск с новой страной (серверы уже известны, это быстро)
            engine = null;
            generation++;
            stopTunnel();
            socksPort = 0;
            publish(ConnectionState.CONNECTING, getString(R.string.detail_switching), "");
            startPsiphon();
            return;
        }
        if (engine == null) {
            // Ещё подключаемся: перезапускаем текущие попытки с новой страной
            if (psiphonActive) startPsiphon();
            if (torActive && !plan.isEmpty()) startAttempt();
            // обход DPI страну не использует — его не трогаем
            return;
        }
        int gen = ++generation;
        publish(ConnectionState.CONNECTING, getString(R.string.detail_switching), "");
        String country = prefs.getCountry();
        new Thread(() -> {
            boolean ok = tor.setCountry(country);
            worker.post(() -> {
                if (!active || gen != generation) return;
                if (!ok) {
                    fail(R.string.error_country);
                    return;
                }
                // Перезапуск туннеля обрывает старые соединения, новые пойдут через новую страну
                stopTunnel();
                if (startTunnel()) checkIp();
            });
        }, "oda-country").start();
    }

    // ---------- Tor ----------

    @Override
    public void onProgress(int percent, @Nullable String reason) {
        torPercent = percent;
        lastReason = reason;
        if (engine == null) publish(ConnectionState.CONNECTING, progressText(), "");
    }

    @Override
    public void onReady(int port) {
        if (!active || engine != null) return;
        engine = StatusBus.ENGINE_TOR;
        stopOtherEngines(StatusBus.ENGINE_TOR);
        socksPort = port;
        DiagLog.add("Tor 100%, SOCKS порт " + port);
        if (startTunnel()) checkIp();
    }

    @Override
    public void onError(int messageRes, boolean retry) {
        // Tor упал после подключения: туннель остановит следующая попытка
        if (StatusBus.ENGINE_TOR.equals(engine)) engine = null;
        if (retry) {
            nextAttempt(messageRes);
        } else {
            torGaveUp(messageRes);
        }
    }

    // ---------- Psiphon ----------

    @Override
    public void onPsiphonProgress(String text) {
        psiphonText = text;
        if (engine == null) publish(ConnectionState.CONNECTING, progressText(), "");
    }

    @Override
    public void onPsiphonReady(int port, @Nullable String serverRegion) {
        if (!active) return;
        if (engine == null && psiphonHelperOnly) {
            // «Обход DPI» ещё выбирает стратегию: путь откроется, когда он подключится
            helperPsiphonPort = port;
            DiagLog.add("Psiphon готов как запасной путь; откроется, когда подключится обход DPI");
            return;
        }
        if (StatusBus.ENGINE_BYPASS.equals(engine) && helperPort > 0) {
            // Подключены обходом DPI: Psiphon — запасной путь, трафик через него идёт только тот,
            // что ByeDPI сам отправит (Telegram и то, что не открылось напрямую)
            helperPsiphonPort = port;
            relay.setUpstream(port);
            DiagLog.add("Запасной путь готов: Telegram и то, что обход DPI не открывает, идут через Psiphon ("
                    + (psiphon.protocol() != null ? psiphon.protocol() : "?") + ", сервер "
                    + (serverRegion != null ? serverRegion : "?") + ")");
            if (bypassIp != null) publish(ConnectionState.ON, bypassDetail(bypassIp, bypassCc), "");
            return;
        }
        if (engine != null) {
            // Уже подключены через Tor
            psiphonActive = false;
            psiphon.stop();
            return;
        }
        engine = StatusBus.ENGINE_PSIPHON;
        stopOtherEngines(StatusBus.ENGINE_PSIPHON);
        socksPort = port;
        if (startTunnel()) checkIp();
    }

    @Override
    public void onPsiphonTunnel(boolean up) {
        if (StatusBus.ENGINE_BYPASS.equals(engine) && helperPort > 0) {
            DiagLog.add(up ? "Запасной путь (Psiphon): туннель восстановлен"
                    : "Запасной путь (Psiphon): туннель пропал, Psiphon переподключается");
            return;
        }
        if (!StatusBus.ENGINE_PSIPHON.equals(engine)) return;
        if (up) {
            DiagLog.add("Psiphon: туннель восстановлен");
            checkIp();
        } else {
            DiagLog.add("Psiphon: туннель пропал, Psiphon переподключается");
            generation++;
            publish(ConnectionState.CONNECTING, getString(R.string.psiphon_reconnecting), "");
        }
    }

    @Override
    public void onPsiphonError(String reason) {
        if (!active) return;
        DiagLog.add("Psiphon: " + reason);
        if (StatusBus.ENGINE_PSIPHON.equals(engine)) {
            // Psiphon был подключён и завершился: перезапускаем его
            engine = null;
            generation++;
            stopTunnel();
            socksPort = 0;
            if (psiphonRestarts++ < PSIPHON_RESTARTS) {
                publish(ConnectionState.CONNECTING, getString(R.string.psiphon_reconnecting), "");
                startPsiphon();
                return;
            }
            psiphonActive = false;
            if (prefs.getMode() == BridgeMode.PSIPHON) {
                fail(R.string.error_psiphon_stopped);
                return;
            }
            // Psiphon не держится: пробуем Tor
            DiagLog.add("Psiphon не держится, пробую Tor");
            startTor(connection);
            return;
        }
        if (StatusBus.ENGINE_BYPASS.equals(engine) && helperPort > 0 && psiphonActive) {
            // Запасной путь пропал: обход DPI работает дальше, Psiphon перезапускаем
            relay.setUpstream(0);
            helperPsiphonPort = 0;
            if (psiphonRestarts++ < PSIPHON_RESTARTS) {
                DiagLog.add("Запасной путь: перезапуск Psiphon (" + psiphonRestarts + "/" + PSIPHON_RESTARTS + ")");
                startPsiphon();
            } else {
                psiphonActive = false;
                DiagLog.add("Запасной путь недоступен: Psiphon не подключается. Обход DPI работает без него");
            }
            if (bypassIp != null) publish(ConnectionState.ON, bypassDetail(bypassIp, bypassCc), "");
            return;
        }
        psiphonActive = false;
        if (engine != null) return;
        if (torActive || bypassActive) {
            publish(ConnectionState.CONNECTING, progressText(), "");
            return;
        }
        failAll();
    }

    // ---------- Обход DPI (ByeDPI) ----------

    @Override
    public void onBypassListening(int slot, int port, BypassProfile profile) {
        if (!active || !bypassActive) return;
        if (!bypassSearching()) {
            if (StatusBus.ENGINE_BYPASS.equals(engine) && slot == bypassSlot) {
                DiagLog.add("Обход DPI: «" + profile.label + "» снова работает");
            } else {
                bypass[slot].stop();  // кто-то уже подключился раньше
            }
            return;
        }
        // Проверяем как у браузера: заблокированные сайты (соцсети) и обычные, зарубежные и российские
        int id = connection;
        int run = bypassRun[slot];
        new Thread(() -> {
            BypassSearch.Score q = NetProbe.bypassQuality(port);
            worker.post(() -> onBypassScored(id, slot, run, profile, q));
        }, "oda-bypass-check-" + slot).start();
    }

    private void onBypassScored(int id, int slot, int run, BypassProfile profile, BypassSearch.Score score) {
        if (!active || id != connection || !bypassSearching()) return;
        if (run != bypassRun[slot]) return;  // результат прошлого запуска этого слота
        if (score.dns != null) bypassDnsOf.put(profile, score.dns);
        bypassSearch.report(profile, score);  // в журнал — вместе с итогом пачки
        decideBypass();
    }

    @Override
    public void onBypassError(int slot, BypassProfile profile, String reason) {
        if (!active || !bypassActive) return;
        if (StatusBus.ENGINE_BYPASS.equals(engine) && slot == bypassSlot) {
            // ByeDPI упал уже после подключения: без него трафик стоит — перезапускаем
            if (bypassRestarts < BYPASS_RESTARTS) {
                bypassRestarts++;
                DiagLog.add("Обход DPI: перезапуск «" + profile.label + "» (" + bypassRestarts
                        + "/" + BYPASS_RESTARTS + ")");
                bypass[slot].start(profile, helperPort, this);
                return;
            }
            fail(R.string.error_bypass_stopped);
            return;
        }
        if (!bypassSearching()) return;
        bypassRun[slot]++;  // проверка этого запуска уже не нужна
        bypassSearch.report(profile, NetProbe.nothing());
        decideBypass();
    }

    /**
     * Обход DPI ещё перебирает стратегии: пока никто не подключился, или в «Авто» раньше успел
     * Psiphon — тогда обход ищет дальше и, если пробьёт соцсети, подключение переходит на него:
     * прямое соединение заметно быстрее Psiphon (сервер за границей).
     */
    private boolean bypassSearching() {
        return bypassActive && bypassSearch != null && (engine == null
                || (StatusBus.ENGINE_PSIPHON.equals(engine) && helperPort > 0));
    }

    /** Пачка проверена: подключиться, проверить следующую или сдаться (решает BypassSearch). */
    private void decideBypass() {
        BypassSearch search = bypassSearch;
        if (search == null || !search.ready()) return;
        BypassSearch.Decision d = search.decide();
        for (String note : d.notes) DiagLog.add(note);
        boolean onPsiphon = StatusBus.ENGINE_PSIPHON.equals(engine);
        if (d.claim != null && onPsiphon && !d.full) {
            // Psiphon открывает всё; обход только для обычных сайтов — не замена ему
            DiagLog.add("Обход DPI открывает только обычные сайты: остаюсь на Psiphon");
            stopBypassSlots();
            bypassActive = false;
            bypassText = "";
            return;
        }
        if (d.claim != null) {
            int slot = -1;
            for (int i = 0; i < bypass.length; i++) {
                if (bypass[i].profile() == d.claim) slot = i;
            }
            if (slot >= 0) {
                for (int i = 0; i < bypass.length; i++) {
                    if (i != slot) bypass[i].stop();
                }
                // Запоминаем выбор для этой сети (кроме своей строки и быстрого повтора запомненного)
                if (!d.fromMemory && BypassProfile.parse("", prefs.getByedpiArgs()) == null) {
                    prefs.setBypassBest(networkId, d.claim, d.full);
                }
                if (onPsiphon) {
                    // Psiphon остаётся запасным путём (Telegram и закрытое по адресу)
                    DiagLog.add("Переключаюсь с Psiphon на обход DPI (быстрее: прямое соединение)");
                    helperPsiphonPort = socksPort;
                }
                claimBypass(slot, d.claim, d.full);
                return;
            }
            // Процесс выбранной стратегии успел завершиться: перезапуск и повторная проверка
            DiagLog.add("Обход DPI: «" + d.claim.label + "» остановилась, проверяю ещё раз");
            startBypassBatch(search.recheck(d.claim));
            return;
        } else if (d.test != null) {
            startBypassBatch(d.test);
            return;
        }
        stopBypassSlots();
        bypassActive = false;
        bypassText = "";
        DiagLog.add("Обход DPI не помог ни одной стратегией"
                + (onPsiphon ? ": остаюсь на Psiphon" : ""));
        if (engine == null && psiphonHelperOnly && psiphonActive) {
            // Запасной путь без обхода DPI не нужен
            psiphonActive = false;
            psiphon.stop();
        }
        if (engine == null && !torActive && !psiphonActive) {
            failAll();
        } else if (engine == null) {
            publish(ConnectionState.CONNECTING, progressText(), "");
        }
    }

    private void claimBypass(int slot, BypassProfile profile, boolean full) {
        DiagLog.add("Обход DPI: подключаюсь через «" + profile.label + "» — "
                + (full ? "заблокированные сайты открываются" : "только обычные сайты"));
        engine = StatusBus.ENGINE_BYPASS;
        bypassSlot = slot;
        bypassFull = full;
        bypassText = "";
        String dns = bypassDnsOf.get(profile);
        bypassDns = dns != null ? dns : NetProbe.DNS_SERVERS[0];
        if (!bypassDns.equals(NetProbe.DNS_SERVERS[0])) {
            DiagLog.add("DNS телефона: " + bypassDns + " (" + NetProbe.DNS_SERVERS[0]
                    + " через обход не отвечает)");
        }
        stopOtherEngines(StatusBus.ENGINE_BYPASS);
        if (helperPort > 0 && helperPsiphonPort > 0) {
            relay.setUpstream(helperPsiphonPort);
            DiagLog.add("Запасной путь открыт: Telegram и то, что обход DPI не откроет, идут через Psiphon");
        } else if (helperPort > 0 && psiphonActive) {
            DiagLog.add("Psiphon продолжает подключаться как запасной путь: Telegram и то, "
                    + "что обход DPI не откроет, пойдут через него");
        }
        socksPort = bypass[slot].socksPort();
        if (startTunnel()) {
            checkIp();
            startBypassWatch();
        }
    }

    // ---------- Сторож обхода DPI ----------
    // Проверка стратегии идёт до подключения и мимо VPN. Сторож следит, что ByeDPI и дальше несёт
    // трафик телефона: DNS через SOCKS-UDP (как у телефона) и сайт; в журнал — счётчики tun2socks
    // и ошибки ядра ByeDPI. Если ByeDPI перестал пропускать трафик, а сеть жива, — перезапуск.

    private void startBypassWatch() {
        bypassWatchFails = 0;
        bypassWatchTicks = 0;
        int gen = ++bypassWatch;
        worker.postDelayed(() -> bypassWatchTick(gen), BYPASS_WATCH_MS);
    }

    private void bypassWatchTick(int gen) {
        if (!active || gen != bypassWatch || !StatusBus.ENGINE_BYPASS.equals(engine) || bypassSlot < 0) return;
        BypassController b = bypass[bypassSlot];
        int port = b.socksPort();
        String out = b.drainOutput();
        long[] stats = Tun2Socks.stats();
        ByeDpiStats conns = b.stats();
        List<String> unreach = conns != null
                ? conns.takeNewUnreachable(BYPASS_UNREACH_CHECKS) : new ArrayList<>();
        String summary = conns != null ? conns.drain() : "";
        String helper = helperPort > 0 ? relay.drainStats() : "";
        String dns = bypassDns;
        new Thread(() -> {
            NetProbe.Health h = NetProbe.bypassHealth(port, dns);
            // Почему адрес не открылся ни одной стратегией: по адресу или по DPI
            List<String> why = new ArrayList<>();
            for (String ip : unreach) {
                why.add("Не открывается обходом DPI: " + conns.who(ip) + " — "
                        + NetProbe.whyUnreachable(ip, 443));
            }
            worker.post(() -> {
                if (!active || gen != bypassWatch) return;
                if (!summary.isEmpty()) DiagLog.add("ByeDPI за 30 с: " + summary);
                for (String w : why) DiagLog.add(w);
                if (!helper.isEmpty()) DiagLog.add("Запасной путь (Psiphon): " + helper);
                if (!why.isEmpty() && helperPort == 0 && !helperHinted) {
                    helperHinted = true;
                    DiagLog.add("Запасного пути нет (Psiphon не собран или порт занят): эти адреса не откроются");
                }
                onBypassHealth(gen, h, out, stats);
            });
        }, "oda-bypass-watch").start();
    }

    private void onBypassHealth(int gen, NetProbe.Health h, String out, @Nullable long[] stats) {
        if (!active || gen != bypassWatch || !StatusBus.ENGINE_BYPASS.equals(engine) || bypassSlot < 0) return;
        bypassWatchTicks++;
        logBypassOutput(out);
        boolean failing = !h.ok() && h.direct;
        if (stats != null && (bypassWatchTicks == 1 || failing
                || bypassWatchTicks % BYPASS_WATCH_STATS_EVERY == 0)) {
            DiagLog.add("Туннель: от телефона " + stats[0] + " пакетов (" + stats[1] / 1024
                    + " КБ), к телефону " + stats[2] + " (" + stats[3] / 1024 + " КБ)");
        }
        if (h.ok()) {
            if (bypassWatchFails > 0) DiagLog.add("Обход DPI: трафик снова идёт");
            bypassWatchFails = 0;
        } else if (!h.direct) {
            // Напрямую тоже ничего: пропала сама сеть, ByeDPI тут ни при чём
            DiagLog.add("Обход DPI: проверка не прошла и напрямую — похоже, нет сети, жду");
        } else {
            bypassWatchFails++;
            DiagLog.add("Обход DPI: через ByeDPI не идёт (DNS " + (h.dns ? "+" : "-")
                    + ", сайт " + (h.site ? "+" : "-") + "), напрямую сайт открывается ("
                    + bypassWatchFails + "/" + BYPASS_WATCH_FAILS + ")");
            if (bypassWatchFails >= BYPASS_WATCH_FAILS) {
                bypassWatchFails = 0;
                BypassController b = bypass[bypassSlot];
                BypassProfile p = b.profile();
                // p == null: процесс уже перезапускается после падения (onBypassError)
                if (p != null && bypassRestarts < BYPASS_RESTARTS) {
                    bypassRestarts++;
                    DiagLog.add("Обход DPI: перезапуск «" + p.label + "» (" + bypassRestarts
                            + "/" + BYPASS_RESTARTS + ")");
                    b.start(p, helperPort, this);
                } else if (p != null) {
                    fail(R.string.error_bypass_stopped);
                    return;
                }
            }
        }
        worker.postDelayed(() -> bypassWatchTick(gen), BYPASS_WATCH_MS);
    }

    /** Ошибки ядра ByeDPI в журнал: одинаковые строки — одной строкой со счётчиком. */
    private void logBypassOutput(String out) {
        if (out.trim().isEmpty()) return;
        Map<String, Integer> lines = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            Integer n = lines.get(l);
            lines.put(l, n == null ? 1 : n + 1);
        }
        int shown = 0;
        for (Map.Entry<String, Integer> e : lines.entrySet()) {
            if (shown++ >= BYPASS_OUTPUT_LINES) {
                DiagLog.add("ByeDPI: … ещё " + (lines.size() - BYPASS_OUTPUT_LINES) + " разных строк");
                break;
            }
            DiagLog.add("ByeDPI: " + e.getKey() + (e.getValue() > 1 ? " ×" + e.getValue() : ""));
        }
    }

    /** Остановить все способы, кроме победившего. */
    private void stopOtherEngines(String winner) {
        // Обход DPI с запасным путём: Psiphon не останавливаем, он нужен для Telegram и того,
        // что напрямую не открывается
        boolean keepPsiphon = StatusBus.ENGINE_BYPASS.equals(winner) && helperPort > 0;
        // «Авто»: Psiphon успел раньше — обход DPI ищет дальше, чтобы перейти на него (быстрее)
        boolean keepBypass = StatusBus.ENGINE_PSIPHON.equals(winner) && helperPort > 0
                && bypassActive && bypassSearch != null;
        DiagLog.add("Подключено через " + winner + ": остальные способы останавливаются"
                + (keepPsiphon && psiphonActive ? " (кроме Psiphon — он запасной путь)" : "")
                + (keepBypass ? " (кроме обхода DPI: если пробьёт соцсети, перейду на него — он быстрее)" : ""));
        if (!StatusBus.ENGINE_TOR.equals(winner) && torActive) {
            torActive = false;
            tor.stop();
            pt.stop();
        }
        if (!StatusBus.ENGINE_PSIPHON.equals(winner) && psiphonActive && !keepPsiphon) {
            psiphonActive = false;
            psiphon.stop();
        }
        if (!StatusBus.ENGINE_BYPASS.equals(winner) && bypassActive && !keepBypass) {
            bypassActive = false;
            stopBypassSlots();
        }
    }

    // ---------- Туннель и проверка IP ----------

    private boolean startTunnel() {
        // Обходу DPI нужен реальный DNS без mapdns: при необходимости переустанавливаем tun
        boolean direct = StatusBus.ENGINE_BYPASS.equals(engine);
        if (tun == null || !Objects.equals(direct ? bypassDns : null, tunDns)) {
            stopTunnel();
            try {
                if (tun != null) tun.close();
                tun = establishTun(direct);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "re-establish tun", e);
                fail(R.string.error_tun);
                return false;
            }
        }
        ParcelFileDescriptor t = tun;
        // Если tun2socks упадёт (нативный код), это последняя строка перед «завершился аварийно»
        DiagLog.add("tun2socks: запуск → SOCKS 127.0.0.1:" + socksPort + ", DNS "
                + (direct ? bypassDns + " через SOCKS-UDP" : Tun2SocksConfig.FAKE_DNS + " (mapdns)"));
        boolean started;
        try {
            started = t != null && Tun2Socks.start(this, t, socksPort, direct);
        } catch (LinkageError e) {
            // нативная библиотека не загрузилась
            Log.e(TAG, "tun2socks", e);
            DiagLog.add("tun2socks: библиотека не загрузилась: " + e);
            started = false;
        }
        if (!started) {
            fail(R.string.error_tun2socks);
            return false;
        }
        tunnelRunning = true;
        return true;
    }

    private void stopTunnel() {
        if (tunnelRunning) {
            Tun2Socks.stop();
            tunnelRunning = false;
        }
    }

    /** Проверка IP и страны выхода в фоне, с повтором при неудаче. */
    private void checkIp() {
        int gen = ++generation;
        publish(ConnectionState.CONNECTING, getString(R.string.detail_checking), "");
        int port = socksPort;
        boolean viaTrace = StatusBus.ENGINE_PSIPHON.equals(engine)
                || StatusBus.ENGINE_BYPASS.equals(engine);
        new Thread(() -> {
            String ip = null;
            String cc = null;
            for (int i = 0; i < IP_CHECK_ATTEMPTS && ip == null; i++) {
                try {
                    if (viaTrace) {
                        IpChecker.Exit exit = IpChecker.fetchTrace(port);
                        ip = exit.ip;
                        cc = exit.country;
                    } else {
                        ip = IpChecker.fetchExitIp(port);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "ip check: " + e);
                }
            }
            String fp = null;
            if (!viaTrace) {
                cc = ip != null ? tor.countryOf(ip) : null;
                fp = tor.firstHopFingerprint();
            }
            String exitIp = ip;
            String exitCc = cc;
            String hop = fp;
            worker.post(() -> onIpChecked(gen, exitIp, exitCc, hop));
        }, "oda-ipcheck").start();
    }

    private void onIpChecked(int gen, @Nullable String ip, @Nullable String cc, @Nullable String fp) {
        if (!active || gen != generation) return;
        DiagLog.add("Проверка IP: " + (ip != null ? ip + " (" + cc + ")" : "не удалась"));
        if (StatusBus.ENGINE_BYPASS.equals(engine)) {
            bypassIp = ip;
            bypassCc = cc;
            publish(ConnectionState.ON, bypassDetail(ip, cc), "");
            return;
        }
        if (StatusBus.ENGINE_PSIPHON.equals(engine)) {
            publish(ConnectionState.ON, psiphonDetail(ip, cc), "");
            return;
        }
        String detail;
        if (ip != null) {
            String code = cc != null && !cc.isEmpty() && !"??".equals(cc)
                    ? cc.toUpperCase(Locale.ROOT) : prefs.getCountry();
            detail = getString(R.string.detail_ip, ip, Country.nameOf(this, code));
        } else {
            detail = getString(R.string.detail_ip_unknown);
        }
        BridgeSet set = attempt < plan.size() ? plan.get(attempt) : null;
        if (set != null && !set.isDirect()) {
            // Какой мост сработал: запоминаем именно его, в следующий раз он пойдёт первым
            BridgeSet used = set.narrowTo(fp);
            BridgeSet good = used != null ? used : set;
            DiagLog.add("Сработал: " + good.label + (used != null ? " (" + fp.substring(0, 8) + "…)" : ""));
            if (prefs.getMode() == BridgeMode.AUTO) prefs.setLastGood(good);
            if (getString(R.string.mode_obfs4).equals(good.label)) prefs.setObfs4Failed(false);
            detail = "Tor · " + good.label + " · " + detail;
        } else {
            if (set != null && prefs.getMode() == BridgeMode.AUTO) {
                prefs.setLastGood(set);
                prefs.setDirectFailed(false);
            }
            detail = "Tor · " + detail;
        }
        publish(ConnectionState.ON, detail, "");
    }

    /** Строка под статусом при подключении через Psiphon. */
    private String psiphonDetail(@Nullable String ip, @Nullable String cc) {
        String proto = psiphon.protocol();
        StringBuilder sb = new StringBuilder("Psiphon");
        if (proto != null && !proto.isEmpty()) sb.append(" (").append(proto).append(')');
        String code = cc != null ? cc.toUpperCase(Locale.ROOT) : psiphon.serverRegion();
        sb.append(" · ");
        if (ip == null) {
            sb.append(getString(R.string.detail_ip_unknown));
        } else if (code == null || code.isEmpty()) {
            sb.append(getString(R.string.detail_ip_only, ip));
        } else {
            sb.append(getString(R.string.detail_ip, ip, Country.nameOf(this, code)));
        }
        if (psiphon.isRegionFallback()) sb.append('\n').append(getString(R.string.detail_region_fallback));
        return sb.toString();
    }

    /** Строка под статусом при подключении через обход DPI (прямое соединение, свой IP). */
    private String bypassDetail(@Nullable String ip, @Nullable String cc) {
        StringBuilder sb = new StringBuilder(getString(
                bypassFull ? R.string.bypass_connected : R.string.bypass_connected_partial));
        sb.append(" · ");
        if (ip == null) {
            sb.append(getString(R.string.detail_ip_unknown));
        } else if (cc == null || cc.isEmpty()) {
            sb.append(getString(R.string.detail_ip_only, ip));
        } else {
            sb.append(getString(R.string.detail_ip, ip, Country.nameOf(this, cc.toUpperCase(Locale.ROOT))));
        }
        if (helperPort > 0) {
            sb.append('\n').append(getString(helperPsiphonPort > 0
                    ? R.string.bypass_helper_ready : psiphonActive
                    ? R.string.bypass_helper_wait : R.string.bypass_helper_off));
        }
        // Честно: соцсети этим способом не открылись
        if (!bypassFull && helperPsiphonPort == 0) {
            sb.append('\n').append(getString(R.string.bypass_partial_hint));
        }
        return sb.toString();
    }

    // ---------- Текст статуса ----------

    /** Подсказка о сети и ход подключения каждого способа (по строке на способ). */
    private String progressText() {
        List<String> lines = new ArrayList<>();
        if (probe == NetProbe.Result.WHITELIST) {
            lines.add(getString(R.string.hint_whitelist));
        } else if (probe == NetProbe.Result.OFFLINE) {
            lines.add(getString(R.string.hint_offline));
        }
        if (bypassActive && !bypassText.isEmpty()) lines.add(bypassText);
        if (psiphonActive && !psiphonText.isEmpty()) lines.add(psiphonText);
        if (torActive) lines.add(torText());
        return TextUtils.join("\n", lines);
    }

    private String torText() {
        // План ещё не готов: ждём ответа Moat
        if (plan.isEmpty() || attempt >= plan.size()) return getString(R.string.detail_moat);
        String tor = getString(R.string.detail_bootstrap, torPercent);
        String why = reasonText(lastReason);
        if (why != null) tor = tor + " · " + why;
        String label = plan.get(attempt).label;
        if (plan.size() > 1) {
            label = getString(R.string.detail_attempt, attempt + 1, plan.size(), label);
        }
        return label + " · " + tor;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Причина от Tor понятными словами. */
    @Nullable
    private String reasonText(@Nullable String reason) {
        if (reason == null) return null;
        switch (reason) {
            case "TIMEOUT":
                return getString(R.string.reason_timeout);
            case "CONNECTRESET":
                return getString(R.string.reason_reset);
            case "CONNECTREFUSED":
                return getString(R.string.reason_refused);
            case "NOROUTE":
                return getString(R.string.reason_noroute);
            case "DONE":
            case "IOERROR":
            case "MISC":
                return getString(R.string.reason_misc);
            case "PT_MISSING":
                return getString(R.string.reason_pt);
            default:
                return reason;
        }
    }

    // ---------- Сеть ----------

    /** Код страны сети оператора (подсказка для Moat и Psiphon), без разрешений. */
    @Nullable
    private String networkCountry() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return null;
        String cc = tm.getNetworkCountryIso();
        if (cc == null || cc.isEmpty()) cc = tm.getSimCountryIso();
        return cc == null || cc.isEmpty() ? null : cc;
    }

    /** Есть ли у активной сети (до подъёма VPN) глобальный IPv6-адрес. */
    private boolean underlyingHasIpv6() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        try {
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
            if (lp == null) return false;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                // глобальный: не loopback, не link-local и не ULA (fc00::/7)
                if (a instanceof Inet6Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                        && (a.getAddress()[0] & 0xfe) != 0xfc) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "ipv6: " + e);
        }
        return false;
    }

    /** Тип сети для Psiphon: "WIFI", "MOBILE-25001" (код оператора), … */
    private String networkId() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return "UNKNOWN";
        try {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
            if (caps == null) return "UNKNOWN";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                String op = tm != null ? tm.getNetworkOperator() : null;
                return op != null && !op.isEmpty() ? "MOBILE-" + op : "MOBILE";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "WIRED";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        } catch (RuntimeException e) {
            Log.w(TAG, "network type: " + e);
        }
        return "UNKNOWN";
    }

    private ParcelFileDescriptor establishTun(boolean directDns) throws IOException {
        // Tor/Psiphon: IPv6 тоже в туннель, чтобы он не утекал мимо. Обход DPI соединяет напрямую:
        // если у сети оператора нет IPv6, IPv6 в VPN не нужен и вреден — tun2socks принимает
        // соединение приложения раньше, чем ByeDPI пробует сайт по IPv6, и приложение получает
        // сброс уже «установленного» соединения вместо быстрого отката на IPv4
        boolean ipv6 = !directDns || underlyingIpv6;
        Builder b = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(Tun2SocksConfig.MTU)
                .addAddress(Tun2SocksConfig.TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .setConfigureIntent(VpnNotification.openApp(this));
        if (ipv6) {
            b.addAddress(Tun2SocksConfig.TUN_IPV6, 128);
            b.addRoute("::", 0);
        }
        if (directDns) {
            // Обход DPI: реальный резолвер, запросы идут через SOCKS напрямую
            b.addDnsServer(bypassDns);
            // IPv6-адрес есть только у основного (1.1.1.1); запасные — только IPv4
            if (ipv6 && Tun2SocksConfig.DIRECT_DNS.equals(bypassDns)) b.addDnsServer(Tun2SocksConfig.DIRECT_DNS6);
        } else {
            // Tor/Psiphon: фиктивный DNS, перехватывает mapdns
            b.addDnsServer(Tun2SocksConfig.FAKE_DNS);
        }
        tunDns = directDns ? bypassDns : null;
        try {
            // Само приложение (Tor, мосты и Psiphon) ходит в сеть напрямую
            b.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException(e);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false);
        }
        ParcelFileDescriptor fd = b.establish();
        if (fd == null) throw new IOException("VPN not prepared");
        return fd;
    }

    private void fail(int messageRes) {
        teardown();
        publish(ConnectionState.ERROR, "", getString(messageRes));
        finish();
    }

    private void shutdown() {
        teardown();
        publish(ConnectionState.OFF, "", "");
        finish();
    }

    private void teardown() {
        active = false;
        generation++;
        bypassWatch++;
        connection++;
        engine = null;
        if (psiphonActive) {
            psiphonActive = false;
            psiphon.stop();
        }
        if (bypassActive) {
            bypassActive = false;
            stopBypassSlots();
        }
        relay.setUpstream(0);
        relay.stop();
        helperPort = 0;
        helperPsiphonPort = 0;
        psiphonHelperOnly = false;
        bypassSearch = null;
        bypassSlot = -1;
        torActive = false;
        // Сам Tor не останавливается (повторный запуск в процессе приводил к падению),
        // у него только выключается сеть
        DiagLog.add("Отключение: сеть Tor выключена");
        tor.stop();
        DiagLog.add("Остановка мостов");
        pt.stop();
        stopTunnel();
        socksPort = 0;
        plan = new ArrayList<>();
        attempt = 0;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
            tun = null;
        }
    }

    private void finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void publish(ConnectionState state, String detail, String error) {
        boolean on = state == ConnectionState.ON;
        StatusBus.post(state, detail, error, on ? socksPort : 0, on && engine != null ? engine : "");
        if (active) {
            String title = getString(on ? R.string.status_on : R.string.status_connecting);
            VpnNotification.update(this, title + " · " + prefs.getCountry(), detail);
        }
    }

    private boolean goForeground(String title) {
        Notification n = VpnNotification.build(this, title, prefs.getCountry());
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED : 0;
        try {
            ServiceCompat.startForeground(this, VpnNotification.ID, n, type);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground", e);
            return false;
        }
    }
}
