package dev.sysscan;

import android.content.Context;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Запускается Shizuku в отдельном процессе с правами shell (uid 2000).
 * Основа — rikka.shizuku.demo.service.UserService из RikkaApps/Shizuku-API.
 */
public class UserService extends IUserService.Stub {

    public UserService() {
    }

    @Keep
    public UserService(Context context) {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String exec(String cmd) {
        return run(cmd);
    }

    /** Общая логика: используется и в shell-процессе, и локально (режим без Shizuku). */
    static String run(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) break; // защита от гигантского вывода
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroy();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "ERROR: " + t;
        }
    }
}
