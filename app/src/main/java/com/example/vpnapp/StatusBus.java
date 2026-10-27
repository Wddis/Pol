package com.example.vpnapp;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Текущее состояние VPN внутри процесса: сервис публикует, экран слушает. */
public final class StatusBus {

    /** Снимок состояния. */
    public static final class Status {
        public final ConnectionState state;
        public final String detail;
        public final String error;
        public final int socksPort;
        /** Чем подключены: ENGINE_TOR, ENGINE_PSIPHON или "" (пока не подключены). */
        public final String engine;
        /**
         * Когда подключились ({@link SystemClock#elapsedRealtime}), 0 — не подключены. Не сбрасывается
         * короткими «Подключение…» после подключения (переход Psiphon → обход DPI, перезапуск Psiphon):
         * секундомер считает время с первого подключения до отключения.
         */
        public final long connectedAt;
        /**
         * Когда началось подключение с нуля ({@link SystemClock#elapsedRealtime}), 0 — не подключаемся.
         * По нему экран показывает красный индикатор, если подключение идёт дольше минуты.
         */
        public final long connectingSince;

        public Status(ConnectionState state, String detail, String error, int socksPort, String engine,
                      long connectedAt, long connectingSince) {
            this.state = state;
            this.detail = detail != null ? detail : "";
            this.error = error != null ? error : "";
            this.socksPort = socksPort;
            this.engine = engine != null ? engine : "";
            this.connectedAt = connectedAt;
            this.connectingSince = connectingSince;
        }
    }

    public interface Listener {
        void onStatus(@NonNull Status status);
    }

    public static final String ENGINE_TOR = "tor";
    public static final String ENGINE_PSIPHON = "psiphon";
    public static final String ENGINE_BYPASS = "bypass";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Status current = new Status(ConnectionState.OFF, "", "", 0, "", 0, 0);

    private StatusBus() {
    }

    public static Status current() {
        return current;
    }

    /** Можно вызывать из любого потока; слушатели получат событие в UI-потоке. */
    public static synchronized void post(ConnectionState state, String detail, String error, int socksPort,
                                         String engine) {
        long since = current.connectedAt;
        if (state == ConnectionState.ON) {
            if (since == 0) since = SystemClock.elapsedRealtime();
        } else if (state != ConnectionState.CONNECTING) {
            since = 0;
        }
        long connecting = current.connectingSince;
        if (state == ConnectionState.CONNECTING) {
            if (current.state != ConnectionState.CONNECTING && current.state != ConnectionState.ON) {
                connecting = SystemClock.elapsedRealtime();
            }
        } else {
            connecting = 0;
        }
        Status status = new Status(state, detail, error, socksPort, engine, since, connecting);
        current = status;
        History.onStatus(status);
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onStatus(status);
        });
    }

    @MainThread
    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
        listener.onStatus(current);
    }

    @MainThread
    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }
}
