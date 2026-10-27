package com.example.vpnapp;

import android.animation.LayoutTransition;
import android.animation.TimeInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/** Плавность интерфейса: появление содержимого листов и смена элементов на экране. */
final class Motion {

    /** Плавное торможение (Material «emphasized decelerate»), как у выезда листов (anim/ease_out). */
    static final TimeInterpolator EASE_OUT = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private static final long REVEAL_MS = 320;
    private static final long REVEAL_STAGGER_MS = 30;
    private static final long REVEAL_DELAY_MS = 80;
    private static final long LAYOUT_MS = 260;

    private Motion() {
    }

    /**
     * Содержимое листа появляется по очереди сверху вниз (небольшой подъём и проявление), пока сам
     * лист выезжает (anim/sheet_enter) — без резкого «скачка» всего текста сразу.
     */
    static void reveal(ViewGroup root) {
        float shift = root.getResources().getDisplayMetrics().density * 12;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.setAlpha(0f);
            child.setTranslationY(shift);
            child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(REVEAL_DELAY_MS + i * REVEAL_STAGGER_MS)
                    .setDuration(REVEAL_MS).setInterpolator(EASE_OUT).start();
        }
    }

    /**
     * Появление, исчезновение и сдвиг элементов контейнера — плавно (вместо мгновенного
     * перескока, когда что-то показывается или прячется).
     */
    static void animateLayout(ViewGroup group) {
        LayoutTransition t = new LayoutTransition();
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(LAYOUT_MS);
        t.setInterpolator(LayoutTransition.CHANGE_APPEARING, EASE_OUT);
        t.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, EASE_OUT);
        t.setInterpolator(LayoutTransition.CHANGING, EASE_OUT);
        t.setInterpolator(LayoutTransition.APPEARING, EASE_OUT);
        group.setLayoutTransition(t);
    }
}
