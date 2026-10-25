package com.example.vpnapp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * База GeoIP для Tor: без неё ExitNodes {код} не работает.
 * Копируется из assets при первом запуске и после обновления приложения.
 */
final class GeoIp {

    private static final String ASSET = "geoip";
    private static final int BUFFER = 64 * 1024;

    private GeoIp() {
    }

    static File install(Context context) throws IOException {
        File target = new File(context.getFilesDir(), ASSET);
        if (target.exists() && target.lastModified() >= lastUpdateTime(context)) {
            return target;
        }
        File tmp = new File(context.getFilesDir(), ASSET + ".tmp");
        try (InputStream in = context.getAssets().open(ASSET);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Cannot install geoip");
        }
        return target;
    }

    private static long lastUpdateTime(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE;
        }
    }
}
