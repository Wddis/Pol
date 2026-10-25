package com.example.vpnapp;

import android.os.Handler;
import android.os.Looper;

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

        public Status(ConnectionState state, String detail, String error, int socksPort) {
            this.state = state;
            this.detail = detail != null ? detail : "";
            this.error = error != null ? error : "";
            this.socksPort = socksPort;
        }
    }

    public interface Listener {
        void onStatus(@NonNull Status status);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Status current = new Status(ConnectionState.OFF, "", "", 0);

    private StatusBus() {
    }

    public static Status current() {
        return current;
    }

    /** Можно вызывать из любого потока; слушатели получат событие в UI-потоке. */
    public static void post(ConnectionState state, String detail, String error, int socksPort) {
        Status status = new Status(state, detail, error, socksPort);
        current = status;
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
