package com.example.vpnapp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Круглая кнопка включения, рисуется на Canvas.
 * Выкл: чёрный круг с белой обводкой. Подключение: вращается дуга 90°.
 * Вкл: белый круг с чёрной иконкой и «дыханием» масштаба.
 */
public class PowerButtonView extends View {

    private static final long TRANSITION_MS = 450;
    private static final long SPIN_MS = 1200;
    private static final long BREATH_MS = 1800;
    private static final float BREATH_SCALE = 1.04f;
    private static final float STROKE_DP = 1.5f;
    private static final float ARC_DP = 2f;
    private static final float ICON_DP = 48f;
    private static final float ARC_SWEEP = 90f;
    private static final float ICON_ALPHA_CONNECTING = 0.5f;
    private static final int GRAY_LINE = 0x2A;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final Drawable icon;
    private final float strokeWidth;
    private final float arcWidth;
    private final float iconSize;

    private ConnectionState state = ConnectionState.OFF;

    // Текущие значения, которые плавно анимируются
    private float fill;          // 0 — чёрный круг, 1 — белый
    private float iconAlpha = 1f;
    private float arcAlpha;      // видимость вращающейся дуги
    private float arcAngle;
    private float breath = 1f;   // масштаб «дыхания»

    private ValueAnimator transition;
    private ValueAnimator spin;
    private ValueAnimator breathing;

    public PowerButtonView(Context context) {
        this(context, null);
    }

    public PowerButtonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        strokeWidth = STROKE_DP * density;
        arcWidth = ARC_DP * density;
        iconSize = ICON_DP * density;

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(arcWidth);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(Color.WHITE);

        Drawable d = ContextCompat.getDrawable(context, R.drawable.ic_power);
        icon = d != null ? d.mutate() : null;

        setClickable(true);
        setFocusable(true);
        updateDescription();
    }

    public ConnectionState getState() {
        return state;
    }

    /** Смена состояния; animate = false — без анимации (например, после поворота). */
    public void setState(@NonNull ConnectionState newState, boolean animate) {
        if (newState == state && animate) return;
        state = newState;
        updateDescription();

        boolean on = newState == ConnectionState.ON;
        boolean connecting = newState == ConnectionState.CONNECTING;
        float toFill = on ? 1f : 0f;
        float toIcon = connecting ? ICON_ALPHA_CONNECTING : 1f;
        float toArc = connecting ? 1f : 0f;

        cancel(transition);
        if (!on) cancel(breathing);
        if (connecting) startSpin();

        if (!animate) {
            fill = toFill;
            iconAlpha = toIcon;
            arcAlpha = toArc;
            breath = 1f;
            if (!connecting) cancel(spin);
            if (on) startBreathing();
            invalidate();
            return;
        }

        final float fromFill = fill;
        final float fromIcon = iconAlpha;
        final float fromArc = arcAlpha;
        final float fromBreath = breath;
        transition = ValueAnimator.ofFloat(0f, 1f);
        transition.setDuration(TRANSITION_MS);
        transition.setInterpolator(new DecelerateInterpolator());
        transition.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            fill = lerp(fromFill, toFill, t);
            iconAlpha = lerp(fromIcon, toIcon, t);
            arcAlpha = lerp(fromArc, toArc, t);
            if (!on) breath = lerp(fromBreath, 1f, t);
            invalidate();
        });
        transition.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (canceled) return;
                // Дуга крутится, пока не исчезнет полностью
                if (state != ConnectionState.CONNECTING) cancel(spin);
                if (state == ConnectionState.ON) startBreathing();
            }
        });
        transition.start();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        // Запас под максимальное «дыхание», чтобы круг не обрезался
        float baseR = Math.min(getWidth(), getHeight()) / 2f / BREATH_SCALE - arcWidth;
        float r = baseR * breath;

        // Заливка: от чёрного к белому
        fillPaint.setColor(gray(fill));
        canvas.drawCircle(cx, cy, r, fillPaint);

        // Обводка: во время подключения уходит в цвет линий, чтобы дуга читалась
        int strokeGray = Math.round(lerp(0xFF, GRAY_LINE, arcAlpha * (1f - fill)));
        strokePaint.setColor(Color.rgb(strokeGray, strokeGray, strokeGray));
        canvas.drawCircle(cx, cy, r - strokeWidth / 2f, strokePaint);

        // Вращающаяся дуга
        if (arcAlpha > 0f) {
            float ar = r - strokeWidth / 2f;
            arcRect.set(cx - ar, cy - ar, cx + ar, cy + ar);
            arcPaint.setAlpha(Math.round(255 * arcAlpha));
            canvas.drawArc(arcRect, arcAngle - 90f, ARC_SWEEP, false, arcPaint);
        }

        // Иконка: белая на чёрном, чёрная на белом
        if (icon != null) {
            float half = iconSize * breath / 2f;
            icon.setBounds(Math.round(cx - half), Math.round(cy - half),
                    Math.round(cx + half), Math.round(cy + half));
            icon.setTint(gray(1f - fill));
            icon.setAlpha(Math.round(255 * iconAlpha));
            icon.draw(canvas);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (state == ConnectionState.CONNECTING) startSpin();
        if (state == ConnectionState.ON) startBreathing();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancel(transition);
        cancel(spin);
        cancel(breathing);
        super.onDetachedFromWindow();
    }

    private void startSpin() {
        if (spin != null && spin.isRunning()) return;
        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(SPIN_MS);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> {
            arcAngle = (float) a.getAnimatedValue();
            invalidate();
        });
        spin.start();
    }

    private void startBreathing() {
        if (breathing != null && breathing.isRunning()) return;
        breathing = ValueAnimator.ofFloat(1f, BREATH_SCALE);
        breathing.setDuration(BREATH_MS);
        breathing.setRepeatCount(ValueAnimator.INFINITE);
        breathing.setRepeatMode(ValueAnimator.REVERSE);
        breathing.setInterpolator(new AccelerateDecelerateInterpolator());
        breathing.addUpdateListener(a -> {
            breath = (float) a.getAnimatedValue();
            invalidate();
        });
        breathing.start();
    }

    private void updateDescription() {
        int res;
        switch (state) {
            case ON:
                res = R.string.cd_power_on;
                break;
            case CONNECTING:
                res = R.string.cd_power_connecting;
                break;
            default:
                res = R.string.cd_power_off;
        }
        setContentDescription(getContext().getString(res));
    }

    private static void cancel(@Nullable ValueAnimator animator) {
        if (animator != null) animator.cancel();
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** Оттенок серого: 0 — чёрный, 1 — белый. */
    private static int gray(float t) {
        int v = Math.round(255 * Math.max(0f, Math.min(1f, t)));
        return Color.rgb(v, v, v);
    }
}
