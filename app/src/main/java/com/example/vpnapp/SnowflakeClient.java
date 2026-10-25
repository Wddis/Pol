package com.example.vpnapp;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Свой клиент Snowflake (Tor Project, собирается в CI скриптом scripts/build-snowflake.sh)
 * с маскировкой DTLS-рукопожатия под браузер (covert-dtls). ТСПУ узнаёт обычный Snowflake
 * по первому пакету DTLS, а Snowflake из IPtProxy маскировку не включает.
 * <p>
 * Клиент лежит в APK как libsnowflake.so (только из папки библиотек Android разрешает
 * запуск программ), Tor запускает его сам: ClientTransportPlugin snowflake exec.
 * Это отдельный процесс, поэтому Tor может останавливать его между попытками.
 */
final class SnowflakeClient {

    private static final String TAG = "SnowflakeClient";
    private static final String LIB = "libsnowflake.so";
    private static final String LOG_DIR = "snowflake";
    private static final String LOG_FILE = "snowflake.log";
    // Рукопожатие как у случайной свежей версии Chrome или Firefox
    // (так же по умолчанию маскируются прокси-добровольцы Snowflake)
    static final String COVERT_DTLS = "randomizemimic";
    private static final long CHECK_TIMEOUT_MS = 10_000;
    private static final long LOG_LIMIT_BYTES = 512 * 1024;
    private static final int LOG_TAIL_BYTES = 6 * 1024;

    // Проверка запуска — один раз на процесс (под классом)
    private static boolean checked;
    @Nullable private static String version;

    private SnowflakeClient() {
    }

    /**
     * Строка torrc для Tor или null, если клиента нет в APK или он не запускается
     * на этом устройстве (тогда используется Snowflake из IPtProxy). Блокирующий вызов.
     */
    @Nullable
    static synchronized String torrcLine(Context context, int maxPeers) {
        File exe = new File(context.getApplicationInfo().nativeLibraryDir, LIB);
        if (!checked) {
            checked = true;
            version = check(exe);
            DiagLog.add(version != null
                    ? "Snowflake: свой клиент " + version + ", маскировка DTLS " + COVERT_DTLS
                    : "Snowflake: своего клиента нет, берётся IPtProxy (без маскировки DTLS)");
        }
        if (version == null) return null;
        File log = logFile(context);
        //noinspection ResultOfMethodCallIgnored
        log.getParentFile().mkdirs();
        // Клиент дописывает в конец файла: не даём журналу расти бесконечно
        if (log.length() > LOG_LIMIT_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            log.delete();
        }
        // Параметры из строки моста (если есть) важнее этих: так можно отключить маскировку
        // своей строкой с covertdtls-config=disable
        return "ClientTransportPlugin snowflake exec " + exe.getAbsolutePath()
                + " -covertdtls-config " + COVERT_DTLS
                + " -max " + maxPeers
                + " -log " + log.getAbsolutePath();
    }

    /** Запуск «libsnowflake.so -version»: версия клиента или null, если он не запускается. */
    @Nullable
    private static String check(File exe) {
        if (!exe.isFile()) return null;
        Process p = null;
        try {
            p = new ProcessBuilder(exe.getAbsolutePath(), "-version")
                    .redirectErrorStream(true)
                    .start();
            long deadline = SystemClock.elapsedRealtime() + CHECK_TIMEOUT_MS;
            Integer code = null;
            while (code == null && SystemClock.elapsedRealtime() < deadline) {
                try {
                    code = p.exitValue();
                } catch (IllegalThreadStateException running) {
                    SystemClock.sleep(50);
                }
            }
            if (code == null) {
                DiagLog.add("Snowflake: свой клиент не ответил за " + CHECK_TIMEOUT_MS / 1000 + " с");
                return null;
            }
            String out = read(p.getInputStream()).trim();
            if (code != 0) {
                DiagLog.add("Snowflake: свой клиент завершился с кодом " + code + ": " + out);
                return null;
            }
            int nl = out.indexOf('\n');
            return nl > 0 ? out.substring(0, nl).trim() : out;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "check", e);
            DiagLog.add("Snowflake: свой клиент не запускается: " + e);
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Хвост журнала клиента для экрана «Журнал». */
    static String logTail(Context context) {
        File log = logFile(context);
        if (!log.exists()) return "";
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long start = Math.max(0, f.length() - LOG_TAIL_BYTES);
            byte[] buf = new byte[(int) (f.length() - start)];
            f.seek(start);
            f.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static File logFile(Context context) {
        return new File(new File(context.getFilesDir(), LOG_DIR), LOG_FILE);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
