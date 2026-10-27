package com.example.vpnapp;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * Размеры экрана ODA не зависят от «Размера шрифта» и «Масштаба экрана» в настройках Android: шрифт — не
 * крупнее обычного, масштаб — не крупнее заводского для этого телефона. Иначе главный экран не помещается
 * и его приходится листать (макет рассчитан на обычный масштаб). Подгонка под высоту экрана — в MainActivity.
 */
final class Ui {

    private static final float MAX_FONT_SCALE = 1.0f;

    private Ui() {
    }

    /** Для attachBaseContext каждого экрана. */
    static Context wrap(Context base) {
        Configuration c = new Configuration(base.getResources().getConfiguration());
        boolean changed = false;
        if (c.fontScale > MAX_FONT_SCALE) {
            c.fontScale = MAX_FONT_SCALE;
            changed = true;
        }
        int stable = DisplayMetrics.DENSITY_DEVICE_STABLE;
        if (stable > 0 && c.densityDpi > stable) {
            c.densityDpi = stable;
            changed = true;
        }
        return changed ? base.createConfigurationContext(c) : base;
    }
}
