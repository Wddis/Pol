package dev.sysscan;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

/** Общее подключение к shell-сервису Shizuku (uid 2000) для Activity и FPS-монитора. */
public final class Shell {
    private Shell() { }

    public interface Listener { void onState(String msg); }

    private static volatile IUserService svc;
    private static Listener listener;

    private static final Shizuku.UserServiceArgs ARGS = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, UserService.class.getName()))
            .daemon(false).processNameSuffix("shell").debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            if (b != null && b.pingBinder()) {
                svc = IUserService.Stub.asInterface(b);
                emit("✅ Shizuku подключён (права shell).");
            }
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    public static void setListener(Listener l) { listener = l; }

    private static void emit(String m) { Listener l = listener; if (l != null) l.onState(m); }

    public static boolean ready() { return svc != null; }

    /** Проверяет Shizuku и подключается; при необходимости запрашивает разрешение. */
    public static void connect(boolean askPermission) {
        try {
            if (!Shizuku.pingBinder()) {
                emit("⚠️ Shizuku не запущен. Открой Shizuku → Start (через беспроводную отладку).");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                if (svc == null) { Shizuku.bindUserService(ARGS, CONN); emit("Запускаю shell-сервис…"); }
            } else if (askPermission) {
                Shizuku.requestPermission(42);
            } else {
                emit("Shizuku работает. Нажми «Разрешить доступ».");
            }
        } catch (Throwable t) {
            emit("Ошибка Shizuku: " + t);
        }
    }

    public static void dead() { svc = null; emit("⚠️ Shizuku остановлен. Запусти его снова."); }

    /** Выполнить команду с правами shell; без Shizuku — с правами обычного приложения. */
    public static String sh(String cmd) {
        IUserService s = svc;
        if (s != null) {
            try { return s.exec(cmd); } catch (Throwable t) { return "ERROR: " + t; }
        }
        return UserService.run(cmd);
    }
}
