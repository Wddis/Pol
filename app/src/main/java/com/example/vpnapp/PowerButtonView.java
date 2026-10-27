package com.example.vpnapp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Кнопка включения (0.11, макет владельца): тёмный диск с кольцом и белая иконка питания.
 * Кольцо: серое — не подключено; зелёное со свечением и мягким «дыханием» — подключено;
 * подключение — по серому кольцу бежит зелёная дуга (красная, если подключение идёт дольше минуты);
 * ошибка — красное кольцо.
 */
public class PowerButtonView extends View {

    private static final long TRANSITION_MS = 450;
    private static final long PRESS_MS = 120;
    private static final long RELEASE_MS = 300;
    private static final long SPIN_MS = 1200;
    private static final long BREATH_MS = 2200;
    private static final float PRESS_SCALE = 0.94f;
    private static final float RING_DP = 3f;
    private static final float GLOW_DP = 26f;
    private static final float ICON_DP = 64f;
    private static final float ARC_SWEEP = 100f;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final Drawable icon;
    private final int colorIdle;
    private final int colorOn;
    private final int colorDanger;
    private final float ringWidth;
    private final float glowWidth;
    private final float iconSize;

    private ConnectionState state = ConnectionState.OFF;
    private boolean alarm;

    // Плавно меняющиеся значения
    private int ringColor;
    private float glow;          // 0..1 — свечение вокруг кольца (подключено)
    private float arcAlpha;      // видимость бегущей дуги (подключение)
    private float arcAngle;
    private float breath;        // 0..1 — «дыхание» свечения
    private float press = 1f;
    private float iconAlpha = 0.85f;

    private int glowShaderColor;
    private float glowShaderR;

    private ValueAnimator transition;
    private ValueAnimator spin;
    private ValueAnimator breathing;
    private ValueAnimator pressAnim;

    public PowerButtonView(Context context) {
        this(context, null);
    }

    public PowerButtonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        ringWidth = RING_DP * density;
        glowWidth = GLOW_DP * density;
        iconSize = ICON_DP * density;
        colorIdle = ContextCompat.getColor(context, R.color.power_off);
        colorOn = ContextCompat.getColor(context, R.color.power_on);
        colorDanger = ContextCompat.getColor(context, R.color.danger);
        ringColor = colorIdle;

        discPaint.setStyle(Paint.Style.FILL);
        discPaint.setColor(ContextCompat.getColor(context, R.color.power_disc));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringWidth);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(ringWidth);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.FILL);

        Drawable d = ContextCompat.getDrawable(context, R.drawable.ic_power);
        icon = d != null ? d.mutate() : null;

        setClickable(true);
        setFocusable(true);
        updateDescription();
    }

    public ConnectionState getState() {
        return state;
    }

    /** Подключение идёт дольше минуты — дуга красная (индикатор ошибки). */
    public void setAlarm(boolean value) {
        if (alarm == value) return;
        alarm = value;
        invalidate();
    }

    /** Смена состояния; animate = false — без анимации (например, после поворота). */
    public void setState(@NonNull ConnectionState newState, boolean animate) {
        if (newState == state && animate) return;
        state = newState;
        updateDescription();

        boolean on = newState == ConnectionState.ON;
        boolean connecting = newState == ConnectionState.CONNECTING;
        int toRing = on ? colorOn : newState == ConnectionState.ERROR ? colorDanger : colorIdle;
        float toGlow = on ? 1f : 0f;
        float toArc = connecting ? 1f : 0f;
        float toIcon = on ? 1f : 0.85f;

        cancel(transition);
        if (!on) cancel(breathing);
        if (connecting) startSpin();

        if (!animate) {
            ringColor = toRing;
            glow = toGlow;
            arcAlpha = toArc;
            iconAlpha = toIcon;
            if (!connecting) cancel(spin);
            if (on) startBreathing();
            invalidate();
            return;
        }

        final int fromRing = ringColor;
        final float fromGlow = glow;
        final float fromArc = arcAlpha;
        final float fromIcon = iconAlpha;
        transition = ValueAnimator.ofFloat(0f, 1f);
        transition.setDuration(TRANSITION_MS);
        transition.setInterpolator(new DecelerateInterpolator());
        transition.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            ringColor = (int) argb.evaluate(t, fromRing, toRing);
            glow = lerp(fromGlow, toGlow, t);
            arcAlpha = lerp(fromArc, toArc, t);
            iconAlpha = lerp(fromIcon, toIcon, t);
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
                if (state != ConnectionState.CONNECTING) cancel(spin);
                if (state == ConnectionState.ON) startBreathing();
            }
        });
        transition.start();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animatePress(PRESS_SCALE, PRESS_MS, new DecelerateInterpolator());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animatePress(1f, RELEASE_MS, new OvershootInterpolator(3f));
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        // Снаружи — место под свечение; всё — в долях размера (кнопка подстраивается под высоту экрана)
        float half = Math.min(getWidth(), getHeight()) / 2f;
        float glowW = Math.min(glowWidth, half * 0.24f);
        float ringR = (half - glowW) * press;

        // Свечение вокруг кольца (подключено), дышит
        float g = glow * (0.75f + 0.25f * breath);
        if (g > 0.01f) {
            float outer = ringR + glowW;
            if (glowShaderColor != colorOn || glowShaderR != outer) {
                glowShaderColor = colorOn;
                glowShaderR = outer;
                int c0 = (colorOn & 0x00FFFFFF) | 0x66000000;
                glowPaint.setShader(new RadialGradient(cx, cy, outer,
                        new int[]{Color.TRANSPARENT, c0, Color.TRANSPARENT},
                        new float[]{Math.max(0f, (ringR - glowW * 0.4f) / outer), ringR / outer, 1f},
                        Shader.TileMode.CLAMP));
            }
            glowPaint.setAlpha(Math.round(255 * g));
            canvas.drawCircle(cx, cy, outer, glowPaint);
        }

        // Тёмный диск и кольцо
        canvas.drawCircle(cx, cy, ringR, discPaint);
        ringPaint.setColor(ringColor);
        canvas.drawCircle(cx, cy, ringR, ringPaint);

        // Подключение: бегущая дуга
        if (arcAlpha > 0f) {
            arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
            arcPaint.setColor(alarm ? colorDanger : colorOn);
            arcPaint.setAlpha(Math.round(255 * arcAlpha));
            canvas.drawArc(arcRect, arcAngle - 90f, ARC_SWEEP, false, arcPaint);
        }

        if (icon != null) {
            float ih = Math.min(iconSize / 2f, ringR * 0.4f);
            icon.setBounds(Math.round(cx - ih), Math.round(cy - ih), Math.round(cx + ih), Math.round(cy + ih));
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
        cancel(pressAnim);
        super.onDetachedFromWindow();
    }

    private void animatePress(float to, long duration, TimeInterpolator interpolator) {
        cancel(pressAnim);
        pressAnim = ValueAnimator.ofFloat(press, to);
        pressAnim.setDuration(duration);
        pressAnim.setInterpolator(interpolator);
        pressAnim.addUpdateListener(a -> {
            press = (float) a.getAnimatedValue();
            invalidate();
        });
        pressAnim.start();
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
        breathing = ValueAnimator.ofFloat(0f, 1f);
        breathing.setDuration(BREATH_MS);
        breathing.setRepeatMode(ValueAnimator.REVERSE);
        breathing.setRepeatCount(ValueAnimator.INFINITE);
        breathing.setInterpolator(new DecelerateInterpolator());
        breathing.addUpdateListener(a -> {
            breath = (float) a.getAnimatedValue();
            invalidate();
        });
        breathing.start();
    }

    private static void cancel(@Nullable ValueAnimator a) {
        if (a != null) a.cancel();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void updateDescription() {
        int res = state == ConnectionState.ON ? R.string.cd_power_on
                : state == ConnectionState.CONNECTING ? R.string.cd_power_connecting : R.string.cd_power_off;
        setContentDescription(getContext().getString(res));
    }
}
