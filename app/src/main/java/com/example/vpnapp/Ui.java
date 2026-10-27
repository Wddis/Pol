package com.example.vpnapp;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import java.util.Locale;

/**
 * Размеры экрана ODA не зависят от «Размера шрифта» и «Масштаба экрана» в настройках Android: шрифт — не
 * крупнее обычного, масштаб — не крупнее заводского для этого телефона. Иначе главный экран не помещается
 * и его приходится листать (макет рассчитан на обычный масштаб). Подгонка под высоту экрана — в MainActivity.
 */
final class Ui {

    private static final float MAX_FONT_SCALE = 1.0f;
    private static volatile Resources ru;

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

    /**
     * «5 минут», «2 дня» по правилам русского языка при любом языке телефона: Android выбирает форму plurals
     * по языку системы, и на английском было «5 минуты» (тексты ODA — только русские).
     */
    static String plural(Context ctx, int id, int n) {
        Resources r = ru;
        if (r == null) {
            Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            Configuration c = new Configuration(app.getResources().getConfiguration());
            c.setLocale(Locale.forLanguageTag("ru"));
            r = app.createConfigurationContext(c).getResources();
            ru = r;
        }
        return r.getQuantityString(id, n, n);
    }
}
