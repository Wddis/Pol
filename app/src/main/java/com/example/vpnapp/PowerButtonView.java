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
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Круглая кнопка включения, рисуется на Canvas.
 * Выкл: красный круг. Подключение: вокруг вращается дуга 90°.
 * Вкл: зелёный круг с мягким «дыханием» масштаба.
 */
public class PowerButtonView extends View {

    private static final long TRANSITION_MS = 450;
    private static final long PRESS_MS = 120;
    private static final long RELEASE_MS = 300;
    private static final long SPIN_MS = 1200;
    private static final long BREATH_MS = 1800;
    private static final float BREATH_SCALE = 1.04f;
    private static final float PRESS_SCALE = 0.92f;
    private static final float ARC_DP = 2f;
    private static final float ARC_GAP_DP = 6f;
    private static final float ICON_DP = 48f;
    private static final float ARC_SWEEP = 90f;
    private static final float ICON_ALPHA_CONNECTING = 0.5f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final Drawable icon;
    private final int colorOff;
    private final int colorOn;
    private final int colorTrack;
    private final float arcWidth;
    private final float arcGap;
    private final float iconSize;

    private ConnectionState state = ConnectionState.OFF;

    // Текущие значения, которые плавно анимируются
    private float fill;          // 0 — красный, 1 — зелёный
    private float iconAlpha = 1f;
    private float arcAlpha;      // видимость вращающейся дуги
    private float arcAngle;
    private float breath = 1f;   // масштаб «дыхания»
    private float press = 1f;    // масштаб нажатия

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
        arcWidth = ARC_DP * density;
        arcGap = ARC_GAP_DP * density;
        iconSize = ICON_DP * density;
        colorOff = ContextCompat.getColor(context, R.color.power_off);
        colorOn = ContextCompat.getColor(context, R.color.power_on);
        colorTrack = ContextCompat.getColor(context, R.color.line);

        fillPaint.setStyle(Paint.Style.FILL);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(arcWidth);
        trackPaint.setColor(colorTrack);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(arcWidth);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(Color.WHITE);

        // Иконка белая во всех состояниях, без перекраски
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
        // Запас под «дыхание» и кольцо дуги, чтобы ничего не обрезалось
        float outer = Math.min(getWidth(), getHeight()) / 2f - arcWidth;
        float baseR = outer / BREATH_SCALE - arcGap;
        float r = baseR * breath * press;

        // Заливка: от красного к зелёному
        fillPaint.setColor((int) argb.evaluate(fill, colorOff, colorOn));
        canvas.drawCircle(cx, cy, r, fillPaint);

        // Кольцо и вращающаяся дуга вокруг круга
        if (arcAlpha > 0f) {
            float ar = r + arcGap;
            arcRect.set(cx - ar, cy - ar, cx + ar, cy + ar);
            int a = Math.round(255 * arcAlpha);
            trackPaint.setAlpha(a);
            canvas.drawCircle(cx, cy, ar, trackPaint);
            arcPaint.setAlpha(a);
            canvas.drawArc(arcRect, arcAngle - 90f, ARC_SWEEP, false, arcPaint);
        }

        // Белая иконка питания
        if (icon != null) {
            float half = iconSize * breath * press / 2f;
            icon.setBounds(Math.round(cx - half), Math.round(cy - half),
                    Math.round(cx + half), Math.round(cy + half));
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
}
