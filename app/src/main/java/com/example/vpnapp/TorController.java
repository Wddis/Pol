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

import net.freehaven.tor.control.TorControlConnection;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Запуск и остановка Tor (tor-android), конфиг по стране и мостам, ожидание bootstrap 100%.
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
    // Пауза, чтобы прошлый экземпляр Tor успел полностью завершиться
    private static final long RESTART_GAP_MS = 3_000;
    private static final Pattern PROGRESS = Pattern.compile("PROGRESS=(\\d+)");
    private static final Pattern REASON = Pattern.compile("REASON=(\\S+)");
    private static final Pattern WARNING = Pattern.compile("WARNING=\"([^\"]*)\"");
    private static final Pattern TAG_NAME = Pattern.compile("TAG=(\\S+)");
    private static final Pattern FIRST_HOP = Pattern.compile(" BUILT \\$([0-9A-Fa-f]{40})");
    private static final Pattern PORT = Pattern.compile(":(\\d+)\"?(\\s|$)");

    private static volatile long lastStopAt;

    private final Context context;
    private final Handler handler;
    private final ExecutorService control = Executors.newSingleThreadExecutor();

    @Nullable private Listener listener;
    @Nullable private volatile TorService service;
    private boolean bound;
    private boolean ready;
    private boolean pollBusy;
    private int session;          // номер запуска: отсекает ответы прошлых запусков
    private long startedAt;
    private long lastProgressAt;
    private int lastProgress = -1;
    @Nullable private String lastReason;
    private int subscribed = -1;    // сессия, для которой подписаны на журнал Tor (поток control)
    private long stallMs;
    private long hardMs;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TorService.LocalBinder) binder).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            handler.post(() -> fail(R.string.error_tor_stopped, true));
        }
    };

    // Ошибки и неожиданная остановка Tor приходят локальными broadcast-ами
    private final BroadcastReceiver torEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            String status = intent.getStringExtra(TorService.EXTRA_STATUS);
            // Пока не привязались, это могут быть события прошлого экземпляра Tor
            if (TorService.ACTION_ERROR.equals(action)) {
                DiagLog.add("Tor ошибка запуска: " + intent.getStringExtra(Intent.EXTRA_TEXT));
                handler.post(() -> {
                    if (bound) fail(R.string.error_tor_start, true);
                });
            } else if (TorService.STATUS_STOPPING.equals(status)) {
                handler.post(() -> {
                    if (bound) fail(R.string.error_tor_stopped, true);
                });
            }
        }
    };

    private final Runnable tick = this::tick;
    private final Runnable bind = this::bindTor;

    TorController(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    /**
     * Запускает Tor с выходом в стране countryCode.
     *
     * @param bridgeTorrc строки мостов (UseBridges, ClientTransportPlugin, Bridge)
     * @param stallMs     ошибка, если прогресс не растёт столько времени
     * @param hardMs      общий предел на подключение
     */
    void start(String countryCode, List<String> bridgeTorrc, long stallMs, long hardMs, Listener l) {
        stop();
        listener = l;
        this.stallMs = stallMs;
        this.hardMs = hardMs;
        try {
            writeTorrc(countryCode, bridgeTorrc);
        } catch (IOException e) {
            Log.e(TAG, "torrc", e);
            fail(R.string.error_tor_start, false);
            return;
        }
        IntentFilter filter = new IntentFilter(TorService.ACTION_STATUS);
        filter.addAction(TorService.ACTION_ERROR);
        LocalBroadcastManager.getInstance(context).registerReceiver(torEvents, filter);

        long wait = lastStopAt + RESTART_GAP_MS - SystemClock.elapsedRealtime();
        handler.postDelayed(bind, Math.max(0, wait));
    }

    /** Смена страны без перезапуска: SETCONF ExitNodes + новые цепочки (в фоне, блокирует). */
    boolean setCountry(String countryCode) {
        TorControlConnection conn = control();
        if (conn == null) return false;
        try {
            if (Country.isAny(countryCode)) {
                conn.resetConf(Collections.singletonList("ExitNodes"));
            } else {
                conn.setConf("ExitNodes", "{" + countryCode.toLowerCase(Locale.ROOT) + "}");
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

    void stop() {
        listener = null;
        session++;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(bind);
        LocalBroadcastManager.getInstance(context).unregisterReceiver(torEvents);
        if (bound) {
            bound = false;
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // сервис уже отвязан
            }
            lastStopAt = SystemClock.elapsedRealtime();
        }
        service = null;
        ready = false;
        pollBusy = false;
        lastProgress = -1;
        lastReason = null;
    }

    private void bindTor() {
        if (listener == null) return;
        Intent intent = new Intent(context, TorService.class);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            fail(R.string.error_tor_start, true);
            return;
        }
        startedAt = SystemClock.elapsedRealtime();
        lastProgressAt = startedAt;
        handler.postDelayed(tick, POLL_MS);
    }

    /** Раз в секунду: проверка таймаутов (здесь) и запрос прогресса (в отдельном потоке). */
    private void tick() {
        if (listener == null || ready) return;
        long now = SystemClock.elapsedRealtime();
        if (now - startedAt > hardMs || now - lastProgressAt > stallMs) {
            DiagLog.add(now - startedAt > hardMs
                    ? "Время попытки вышло (" + hardMs / 1000 + " с), Tor на " + lastProgress + "%"
                    : "Прогресс стоит " + stallMs / 1000 + " с на " + lastProgress + "%");
            fail(R.string.error_timeout, true);
            return;
        }
        if (!pollBusy) {
            pollBusy = true;
            int s = session;
            control.execute(() -> poll(s));
        }
        handler.postDelayed(tick, POLL_MS);
    }

    /** Поток control: блокирующие запросы к Tor. */
    private void poll(int s) {
        String phase = null;
        int port = 0;
        try {
            TorControlConnection conn = control();
            if (conn != null) {
                subscribeLog(conn, s);
                phase = conn.getInfo("status/bootstrap-phase");
                if (parseProgress(phase) >= 100) port = parsePort(conn.getInfo("net/listeners/socks"));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "poll: " + e);
        }
        String ph = phase;
        int sp = port;
        handler.post(() -> onPolled(s, ph, sp));
    }

    /** Подписка на предупреждения Tor для журнала (STATUS_CLIENT нужен самому TorService). */
    private void subscribeLog(TorControlConnection conn, int s) throws IOException {
        if (subscribed == s) return;
        subscribed = s;
        conn.addRawEventListener((keyword, data) -> DiagLog.add("tor " + keyword + ": " + data));
        conn.setEvents(Arrays.asList("STATUS_CLIENT", "WARN", "NOTICE"));
    }

    private void onPolled(int s, @Nullable String phase, int port) {
        pollBusy = false;
        Listener l = listener;
        if (s != session || l == null || ready || phase == null) return;
        int progress = parseProgress(phase);
        String reason = find(REASON, phase);
        boolean progressed = progress > lastProgress;
        boolean reasonChanged = !TextUtils.equals(reason, lastReason);
        if (progressed) {
            lastProgress = progress;
            lastProgressAt = SystemClock.elapsedRealtime();
            DiagLog.add("Tor " + progress + "% " + find(TAG_NAME, phase));
        }
        if (reasonChanged) {
            lastReason = reason;
            if (reason != null) {
                String warning = find(WARNING, phase);
                DiagLog.add("Tor проблема на " + progress + "%: " + reason
                        + (warning != null ? " «" + warning + "»" : ""));
            }
        }
        if (progressed || reasonChanged) l.onProgress(lastProgress, reason);
        if (progress >= 100) {
            if (port <= 0) {
                fail(R.string.error_tor_start, true);
                return;
            }
            ready = true;
            l.onReady(port);
        }
    }

    @Nullable
    private TorControlConnection control() {
        TorService s = service;
        return s != null ? s.getTorControlConnection() : null;
    }

    /** Ошибка: останавливаем Tor и сообщаем слушателю один раз. */
    private void fail(int messageRes, boolean retry) {
        Listener l = listener;
        if (l == null) return;
        stop();
        l.onError(messageRes, retry);
    }

    private void writeTorrc(String countryCode, List<String> bridgeTorrc) throws IOException {
        DiagLog.add("torrc: выход " + countryCode + ", строк мостов " + Math.max(0, bridgeTorrc.size() - 1));
        File geoip = GeoIp.install(context);
        String cc = countryCode.toLowerCase(Locale.ROOT);
        StringBuilder torrc = new StringBuilder()
                .append("SocksPort auto IPv6Traffic\n")
                // «Авто»: Tor сам выбирает выходной узел — быстрее и надёжнее
                .append(Country.isAny(countryCode) ? "" : "ExitNodes {" + cc + "}\nStrictNodes 1\n")
                .append("GeoIPFile ").append(geoip.getAbsolutePath()).append('\n')
                .append("ClientOnly 1\n")
                .append("AvoidDiskWrites 1\n");
        for (String line : bridgeTorrc) torrc.append(line).append('\n');
        try (Writer w = new FileWriter(TorService.getTorrc(context), false)) {
            w.write(torrc.toString());
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
