package com.example.vpnapp;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.freehaven.tor.control.RawEventListener;
import net.freehaven.tor.control.TorControlConnection;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tor (tor-android): один экземпляр на весь процесс. Повторный запуск Tor внутри
 * процесса приводил к аварийному завершению, поэтому Tor запускается один раз
 * с выключенной сетью, а для каждой попытки мосты и страна меняются командами
 * через порт управления (как в Orbot). При отключении у Tor только выключается сеть.
 * <p>
 * Методы и колбэки выполняются в рабочем потоке (handler). Запросы к Tor идут
 * в отдельном потоке, поэтому таймауты срабатывают, даже если Tor не отвечает.
 */
final class TorController {

    interface Listener {
        /** reason — причина проблемы от Tor (TIMEOUT, CONNECTRESET, …) или null. */
        void onProgress(int percent, @Nullable String reason);

        void onReady(int socksPort);

        /** retry = true: стоит попробовать другой способ подключения. */
        void onError(int messageRes, boolean retry);
    }

    private static final String TAG = "TorController";
    private static final long POLL_MS = 1_000;
    // Пауза, чтобы прошлый экземпляр Tor успел завершиться (только если он упал сам)
    private static final long RESTART_GAP_MS = 3_000;
    // Сколько ждём, пока Tor запустится и откроет порт управления
    private static final long START_TIMEOUT_MS = 30_000;
    // Канал рабочий (мост ответил): не обрываем попытку, ждём построения цепочки
    private static final long WORKING_STALL_MS = 180_000;
    private static final long WORKING_HARD_MS = 360_000;
    private static final int WORKING_PERCENT = 25;
    // Прогресс на экране, когда мост ответил: до карты сети / до построения цепочки
    private static final int CONNECTED_PERCENT = 15;
    private static final int CONNECTED_DIR_PERCENT = 80;
    private static final int DIR_INFO_PERCENT = 75;
    private static final Pattern PROGRESS = Pattern.compile("PROGRESS=(\\d+)");
    private static final Pattern REASON = Pattern.compile("REASON=(\\S+)");
    private static final Pattern WARNING = Pattern.compile("WARNING=\"([^\"]*)\"");
    private static final Pattern TAG_NAME = Pattern.compile("TAG=(\\S+)");
    private static final Pattern FIRST_HOP = Pattern.compile(" BUILT \\$([0-9A-Fa-f]{40})");
    private static final Pattern PORT = Pattern.compile(":(\\d+)\"?(\\s|$)");

    // ---------- Общее для процесса: один Tor ----------

    private static final Object SHARED = new Object();
    @Nullable private static volatile TorService service;
    private static boolean bound;               // под SHARED
    private static volatile boolean dead;       // Tor завершился сам, нужен новый запуск
    private static boolean receiverRegistered;  // под SHARED
    private static volatile long lastStopAt;
    // Попытка, которой сейчас принадлежит Tor (ей идут события)
    @Nullable private static volatile TorController current;
    // Соединение, на события которого уже подписаны (поток control)
    @Nullable private static TorControlConnection subscribed;
    // Запросы к Tor от всех попыток идут по очереди в одном потоке
    private static final ExecutorService CONTROL = Executors.newSingleThreadExecutor();

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TorService.LocalBinder) binder).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            onTorDied();
        }
    };

    // Ошибки и неожиданная остановка Tor приходят локальными broadcast-ами
    private static final BroadcastReceiver TOR_EVENTS = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            String status = intent.getStringExtra(TorService.EXTRA_STATUS);
            if (TorService.ACTION_ERROR.equals(action)) {
                DiagLog.add("Tor ошибка запуска: " + intent.getStringExtra(Intent.EXTRA_TEXT));
                onTorDied();
            } else if (TorService.STATUS_STOPPING.equals(status)) {
                DiagLog.add("Tor остановился");
                onTorDied();
            }
        }
    };

    // Журнал Tor и события соединений (поток jtorctl)
    private static final RawEventListener EVENTS = (keyword, data) -> {
        if ("ORCONN".equals(keyword)) {
            // CONNECTED — с мостом (или узлом) прошло рукопожатие: канал рабочий
            if (data == null || !data.contains(" CONNECTED")) return;
            DiagLog.add("tor ORCONN: " + data);
            TorController c = current;
            if (c != null) {
                int s = c.eventSession;
                c.handler.post(() -> c.onConnected(s));
            }
            return;
        }
        DiagLog.add("tor " + keyword + ": " + data);
    };

    private static void onTorDied() {
        synchronized (SHARED) {
            if (!bound) return;
            dead = true;
        }
        TorController c = current;
        if (c != null) c.handler.post(() -> c.fail(R.string.error_tor_stopped, true));
    }

    @Nullable
    private static TorControlConnection control() {
        TorService s = service;
        return s != null ? s.getTorControlConnection() : null;
    }

    // ---------- Попытка подключения ----------

    private final Context context;
    private final Handler handler;

    @Nullable private Listener listener;
    private volatile int session;         // номер попытки: отсекает ответы прошлых попыток
    private volatile int eventSession;    // попытка, к которой относятся события Tor
    private String countryCode = Country.ANY;
    private List<String> bridgeTorrc = Collections.emptyList();
    private boolean ready;
    private boolean configured;
    private boolean configuring;
    private boolean pollBusy;
    private boolean connected;            // мост ответил в этой попытке
    private long requestedAt;
    private long startedAt;
    private long lastProgressAt;
    private int baseProgress;             // прогресс Tor до этой попытки (он не уменьшается)
    @Nullable private String basePhase;
    private int progress;                 // прогресс этой попытки
    @Nullable private String lastReason;
    private long stallMs;
    private long hardMs;

    private final Runnable tick = this::tick;
    private final Runnable bind = this::bindTor;

    TorController(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    /**
     * Подключение с выходом в стране countryCode: Tor запускается, если ещё не запущен,
     * иначе меняются его настройки.
     *
     * @param bridgeTorrc строки мостов (UseBridges, ClientTransportPlugin, Bridge)
     * @param stallMs     ошибка, если прогресс не растёт столько времени (пока мост не ответил)
     * @param hardMs      общий предел на попытку (пока мост не ответил)
     */
    void start(String countryCode, List<String> bridgeTorrc, long stallMs, long hardMs, Listener l) {
        stop();
        listener = l;
        current = this;
        this.countryCode = countryCode;
        this.bridgeTorrc = new ArrayList<>(bridgeTorrc);
        this.stallMs = stallMs;
        this.hardMs = hardMs;
        requestedAt = SystemClock.elapsedRealtime();
        DiagLog.add("Tor: выход " + countryCode + ", строк мостов " + Math.max(0, bridgeTorrc.size() - 1));

        boolean needBind;
        synchronized (SHARED) {
            if (bound && dead) {
                // Tor упал сам: единственный случай, когда запускаем его заново
                unbindLocked();
            }
            needBind = !bound;
        }
        if (needBind) {
            try {
                writeTorrc();
            } catch (IOException e) {
                Log.e(TAG, "torrc", e);
                fail(R.string.error_tor_start, false);
                return;
            }
            registerReceiver();
            long wait = lastStopAt + RESTART_GAP_MS - SystemClock.elapsedRealtime();
            handler.postDelayed(bind, Math.max(0, wait));
            requestedAt += Math.max(0, wait);
        } else {
            DiagLog.add("Tor уже запущен: смена мостов без перезапуска");
        }
        handler.postDelayed(tick, needBind ? POLL_MS : 0);
    }

    /** Смена страны без перезапуска: SETCONF ExitNodes + новые цепочки (в фоне, блокирует). */
    boolean setCountry(String countryCode) {
        TorControlConnection conn = control();
        if (conn == null) return false;
        try {
            if (Country.isAny(countryCode)) {
                conn.resetConf(Arrays.asList("ExitNodes", "StrictNodes"));
            } else {
                conn.setConf(Arrays.asList(
                        "ExitNodes {" + countryCode.toLowerCase(Locale.ROOT) + "}",
                        "StrictNodes 1"));
            }
            conn.signal("NEWNYM");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "setCountry", e);
            return false;
        }
    }

    /** Отпечаток первого узла (моста) готовой цепочки — какой мост сработал (блокирующий вызов). */
    @Nullable
    String firstHopFingerprint() {
        try {
            TorControlConnection conn = control();
            if (conn == null) return null;
            Matcher m = FIRST_HOP.matcher(conn.getInfo("circuit-status"));
            return m.find() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Код страны по IP из базы GeoIP самого Tor, например "de" (блокирующий вызов). */
    @Nullable
    String countryOf(String ip) {
        try {
            TorControlConnection conn = control();
            return conn != null ? conn.getInfo("ip-to-country/" + ip) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Отмена попытки: сам Tor не останавливается, у него выключается сеть. */
    void stop() {
        boolean active = listener != null;
        listener = null;
        session++;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(bind);
        if (current == this) current = null;
        ready = false;
        configured = false;
        configuring = false;
        pollBusy = false;
        connected = false;
        progress = 0;
        lastReason = null;
        if (active && service != null) CONTROL.execute(TorController::disableNetwork);
    }

    private void bindTor() {
        if (listener == null) return;
        boolean ok;
        synchronized (SHARED) {
            if (!bound) {
                dead = false;
                bound = context.bindService(new Intent(context, TorService.class),
                        CONNECTION, Context.BIND_AUTO_CREATE);
            }
            ok = bound;
        }
        if (!ok) {
            fail(R.string.error_tor_start, false);
            return;
        }
        DiagLog.add("Tor запускается");
    }

    /** Раз в секунду: настройка Tor, проверка таймаутов (здесь) и запрос прогресса (в потоке control). */
    private void tick() {
        if (listener == null || ready) return;
        long now = SystemClock.elapsedRealtime();
        if (!configured) {
            if (now - requestedAt > START_TIMEOUT_MS) {
                DiagLog.add("Tor не запустился за " + START_TIMEOUT_MS / 1000 + " с");
                fail(R.string.error_tor_start, false);
                return;
            }
            if (!configuring && control() != null) {
                configuring = true;
                int s = session;
                String cc = countryCode;
                List<String> torrc = bridgeTorrc;
                CONTROL.execute(() -> configure(s, cc, torrc));
            }
            handler.postDelayed(tick, POLL_MS);
            return;
        }
        // Мост ответил — канал рабочий: ждём дольше, Snowflake строит цепочку медленно
        boolean working = connected || progress >= WORKING_PERCENT;
        long stall = working ? Math.max(stallMs, WORKING_STALL_MS) : stallMs;
        long hard = working ? Math.max(hardMs, WORKING_HARD_MS) : hardMs;
        if (now - startedAt > hard || now - lastProgressAt > stall) {
            DiagLog.add(now - startedAt > hard
                    ? "Время попытки вышло (" + hard / 1000 + " с), Tor на " + progress + "%"
                    : "Прогресс стоит " + stall / 1000 + " с на " + progress + "%");
            fail(R.string.error_timeout, true);
            return;
        }
        if (!pollBusy) {
            pollBusy = true;
            int s = session;
            CONTROL.execute(() -> poll(s));
        }
        handler.postDelayed(tick, POLL_MS);
    }

    /**
     * Поток control: сеть выключить → мосты и страна → сеть включить.
     * При выключении сети Tor закрывает все соединения и сбрасывает признак готовой цепочки.
     */
    private void configure(int s, String cc, List<String> torrc) {
        if (s != session) return;
        TorControlConnection conn = control();
        int base = 0;
        String phase = null;
        String error = null;
        try {
            if (conn == null) throw new IOException("no control connection");
            subscribe(conn);
            conn.setConf("DisableNetwork", "1");
            // События после этого ответа относятся уже к новой попытке
            eventSession = s;
            conn.resetConf(Arrays.asList("UseBridges", "ClientTransportPlugin", "Bridge",
                    "ExitNodes", "StrictNodes"));
            List<String> conf = new ArrayList<>(torrc);
            // «Авто»: Tor сам выбирает выходной узел — быстрее и надёжнее
            if (!Country.isAny(cc)) {
                conf.add("ExitNodes {" + cc.toLowerCase(Locale.ROOT) + "}");
                conf.add("StrictNodes 1");
            }
            conn.setConf(conf);
            phase = conn.getInfo("status/bootstrap-phase");
            base = parseProgress(phase);
            conn.setConf("DisableNetwork", "0");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "configure: " + e);
            error = e.toString();
        }
        int b = base;
        String ph = phase;
        String err = error;
        handler.post(() -> onConfigured(s, b, ph, err));
    }

    private void onConfigured(int s, int base, @Nullable String phase, @Nullable String error) {
        configuring = false;
        if (s != session || listener == null) return;
        if (error != null) {
            DiagLog.add("Tor не принял настройки: " + error);
            fail(R.string.error_tor_start, true);
            return;
        }
        configured = true;
        baseProgress = base;
        basePhase = phase;
        startedAt = SystemClock.elapsedRealtime();
        lastProgressAt = startedAt;
        if (base > 0) DiagLog.add("Tor уже загружал карту сети до " + base + "%");
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Подписка на журнал Tor и события соединений (STATUS_CLIENT нужен самому TorService). */
    private static void subscribe(TorControlConnection conn) throws IOException {
        if (subscribed == conn) return;
        conn.addRawEventListener(EVENTS);
        conn.setEvents(Arrays.asList("STATUS_CLIENT", "WARN", "NOTICE", "ORCONN"));
        subscribed = conn;
    }

    /** Поток control: выключение сети Tor между попытками и после отключения. */
    private static void disableNetwork() {
        TorControlConnection conn = control();
        if (conn == null) return;
        try {
            conn.setConf("DisableNetwork", "1");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "disableNetwork: " + e);
        }
    }

    /** Поток control: блокирующие запросы к Tor. */
    private void poll(int s) {
        String phase = null;
        boolean established = false;
        int port = 0;
        try {
            TorControlConnection conn = control();
            if (conn != null) {
                phase = conn.getInfo("status/bootstrap-phase");
                // Готовность — по построенной цепочке: процент Tor после первого запуска не сбрасывается
                established = "1".equals(conn.getInfo("status/circuit-established"));
                if (established) port = parsePort(conn.getInfo("net/listeners/socks"));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "poll: " + e);
        }
        String ph = phase;
        boolean est = established;
        int sp = port;
        handler.post(() -> onPolled(s, ph, est, sp));
    }

    private void onPolled(int s, @Nullable String phase, boolean established, int port) {
        pollBusy = false;
        Listener l = listener;
        if (s != session || l == null || ready || phase == null) return;
        int global = parseProgress(phase);
        // Прошлое сообщение Tor относится к прошлой попытке — его причину не показываем
        String reason = TextUtils.equals(phase, basePhase) ? null : find(REASON, phase);
        boolean progressed = false;
        if (global > baseProgress && global > progress) {
            progress = global;
            progressed = true;
            DiagLog.add("Tor " + progress + "% " + find(TAG_NAME, phase));
        }
        if (established) progress = 100;
        if (progressed || established) lastProgressAt = SystemClock.elapsedRealtime();
        boolean reasonChanged = !TextUtils.equals(reason, lastReason);
        if (reasonChanged) {
            lastReason = reason;
            if (reason != null) {
                String warning = find(WARNING, phase);
                DiagLog.add("Tor проблема на " + progress + "%: " + reason
                        + (warning != null ? " «" + warning + "»" : ""));
            }
        }
        if (progressed || reasonChanged || established) l.onProgress(progress, lastReason);
        if (established) {
            if (port <= 0) {
                fail(R.string.error_tor_start, true);
                return;
            }
            ready = true;
            l.onReady(port);
        }
    }

    /** С мостом прошло рукопожатие: канал рабочий. */
    private void onConnected(int s) {
        Listener l = listener;
        if (s != session || l == null || ready || !configured || connected) return;
        connected = true;
        DiagLog.add("Мост ответил: канал рабочий, ждём цепочку");
        int shown = baseProgress >= DIR_INFO_PERCENT ? CONNECTED_DIR_PERCENT : CONNECTED_PERCENT;
        if (shown > progress) progress = shown;
        lastProgressAt = SystemClock.elapsedRealtime();
        l.onProgress(progress, lastReason);
    }

    /** Ошибка: попытка отменяется, слушатель узнаёт один раз. */
    private void fail(int messageRes, boolean retry) {
        Listener l = listener;
        if (l == null) return;
        stop();
        l.onError(messageRes, retry);
    }

    private void registerReceiver() {
        synchronized (SHARED) {
            if (receiverRegistered) return;
            IntentFilter filter = new IntentFilter(TorService.ACTION_STATUS);
            filter.addAction(TorService.ACTION_ERROR);
            LocalBroadcastManager.getInstance(context).registerReceiver(TOR_EVENTS, filter);
            receiverRegistered = true;
        }
    }

    /** Вызывать под SHARED. */
    private void unbindLocked() {
        try {
            context.unbindService(CONNECTION);
        } catch (IllegalArgumentException ignored) {
            // сервис уже отвязан
        }
        bound = false;
        service = null;
        subscribed = null;
        lastStopAt = SystemClock.elapsedRealtime();
        DiagLog.add("Tor завершился, запускаю заново");
    }

    /** Начальный torrc: сеть выключена, мосты и страна задаются потом через порт управления. */
    private void writeTorrc() throws IOException {
        File geoip = GeoIp.install(context);
        String torrc = "SocksPort auto IPv6Traffic\n"
                + "GeoIPFile " + geoip.getAbsolutePath() + '\n'
                + "ClientOnly 1\n"
                + "AvoidDiskWrites 1\n"
                + "DisableNetwork 1\n";
        try (Writer w = new FileWriter(TorService.getTorrc(context), false)) {
            w.write(torrc);
        }
    }

    private static int parseProgress(@Nullable String phase) {
        if (phase == null) return 0;
        Matcher m = PROGRESS.matcher(phase);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    @Nullable
    private static String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int parsePort(@Nullable String listeners) {
        if (listeners == null) return 0;
        Matcher m = PORT.matcher(listeners);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
