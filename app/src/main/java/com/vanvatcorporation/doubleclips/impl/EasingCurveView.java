package com.vanvatcorporation.doubleclips.impl;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.EasingType;

/**
 * One easing drawn as its curve: time runs left to right, value bottom to top, so linear is the straight diagonal (the iOS
 * EasingCurveGraph and Desktop's picker draw the same). The curve is sampled from {@code AnimatedProperty.ease}, the very
 * function the renderer uses, so the picture is always the curve you get. NONE ("hold") is a step: the value stays until the
 * next keyframe, then jumps. Overshooting curves (back, elastic) get a taller range so they are not cut off.
 * <p>
 * {@link #setAnimated(boolean)} adds a dot that rides the curve, to show the motion.
 */
public class EasingCurveView extends View {

    private EasingType easing = EasingType.LINEAR;
    private float lineWidthPx;
    private int tint = 0xFFFFFFFF;
    private int dotColor = 0xFF0A84FF;
    private boolean animated = false;

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ridePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rideRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float rangeLo = 0f, rangeHi = 1f;
    private ValueAnimator rideAnimator;
    private float ridePhase = 0f; // 0..1 over the whole cycle (the ride plus a short rest at each end)

    /** The ride takes 1.6 s of a 2.2 s cycle, so the dot rests at the end like on iOS. */
    private static final float CYCLE_SECONDS = 2.2f, RIDE_SECONDS = 1.6f;

    public EasingCurveView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        lineWidthPx = 2f * d;
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(Math.max(1f, d));
        guidePaint.setPathEffect(new DashPathEffect(new float[]{3f * d, 3f * d}, 0));
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        endPaint.setStyle(Paint.Style.FILL);
        ridePaint.setStyle(Paint.Style.FILL);
        rideRing.setStyle(Paint.Style.STROKE);
        rideRing.setColor(0xFFFFFFFF);
        rideRing.setStrokeWidth(2f * d);
        updateRange();
    }

    public void setEasing(EasingType easing) {
        this.easing = easing == null ? EasingType.LINEAR : easing;
        updateRange();
        invalidate();
    }

    public EasingType getEasing() { return easing; }

    /** Line thickness in dp. */
    public void setLineWidthDp(float dp) {
        lineWidthPx = dp * getResources().getDisplayMetrics().density;
        invalidate();
    }

    /** The curve's colour (the guide lines and end dots are a faded version of it). */
    public void setTint(int color) {
        tint = color;
        invalidate();
    }

    /** The colour of the riding dot. */
    public void setDotColor(int color) {
        dotColor = color;
        invalidate();
    }

    public void setAnimated(boolean animated) {
        if (this.animated == animated) return;
        this.animated = animated;
        if (isAttachedToWindow()) {
            if (animated) startRide(); else stopRide();
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animated) startRide();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRide();
        super.onDetachedFromWindow();
    }

    private void startRide() {
        if (rideAnimator != null) return;
        rideAnimator = ValueAnimator.ofFloat(0f, 1f);
        rideAnimator.setDuration((long) (CYCLE_SECONDS * 1000));
        rideAnimator.setInterpolator(new LinearInterpolator());
        rideAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rideAnimator.addUpdateListener(a -> {
            ridePhase = (float) a.getAnimatedValue();
            invalidate();
        });
        rideAnimator.start();
    }

    private void stopRide() {
        if (rideAnimator != null) {
            rideAnimator.cancel();
            rideAnimator = null;
        }
    }

    /** The value range to show: 0..1, taller for curves that overshoot. */
    private void updateRange() {
        rangeLo = 0f;
        rangeHi = 1f;
        if (easing == EasingType.NONE) return;
        for (int i = 0; i <= 120; i++) {
            float v = EditingActivity.AnimatedProperty.ease(i / 120f, easing);
            if (v < rangeLo) rangeLo = v;
            if (v > rangeHi) rangeHi = v;
        }
    }

    private float yOf(float value, float top, float height) {
        float span = Math.max(rangeHi - rangeLo, 0.0001f);
        return top + height - ((value - rangeLo) / span) * height;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Square, whichever side is given (like iOS's aspectRatio(1)).
        int w = MeasureSpec.getSize(widthMeasureSpec), h = MeasureSpec.getSize(heightMeasureSpec);
        int wMode = MeasureSpec.getMode(widthMeasureSpec), hMode = MeasureSpec.getMode(heightMeasureSpec);
        int side;
        if (wMode != MeasureSpec.UNSPECIFIED && hMode != MeasureSpec.UNSPECIFIED) side = Math.min(w, h);
        else if (wMode != MeasureSpec.UNSPECIFIED) side = w;
        else if (hMode != MeasureSpec.UNSPECIFIED) side = h;
        else side = (int) (48 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(side, side);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float pad = lineWidthPx * 1.5f;
        final float left = getPaddingLeft() + pad, top = getPaddingTop() + pad;
        final float width = getWidth() - getPaddingLeft() - getPaddingRight() - pad * 2;
        final float height = getHeight() - getPaddingTop() - getPaddingBottom() - pad * 2;
        if (width <= 0 || height <= 0) return;

        final float y0 = yOf(0f, top, height), y1 = yOf(1f, top, height);

        // Faint guides at value 0 and 1.
        guidePaint.setColor(withAlpha(tint, 0x24));
        canvas.drawLine(left, y0, left + width, y0, guidePaint);
        canvas.drawLine(left, y1, left + width, y1, guidePaint);

        // The curve.
        curvePaint.setColor(tint);
        curvePaint.setStrokeWidth(lineWidthPx);
        path.reset();
        if (easing == EasingType.NONE) {
            path.moveTo(left, y0);
            path.lineTo(left + width, y0);
            path.lineTo(left + width, y1);
        } else {
            final int steps = 120;
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                float x = left + t * width;
                float y = yOf(EditingActivity.AnimatedProperty.ease(t, easing), top, height);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, curvePaint);

        // Dots at both ends.
        endPaint.setColor(withAlpha(tint, 0x8C));
        float r = lineWidthPx * 1.2f;
        canvas.drawCircle(left, y0, r, endPaint);
        canvas.drawCircle(left + width, y1, r, endPaint);

        // The riding dot.
        if (animated) {
            float seconds = ridePhase * CYCLE_SECONDS;
            float t = Math.min(seconds / RIDE_SECONDS, 1f);
            float v = easing == EasingType.NONE ? (t >= 1f ? 1f : 0f) : EditingActivity.AnimatedProperty.ease(t, easing);
            float dotR = 7f * getResources().getDisplayMetrics().density;
            ridePaint.setColor(dotColor);
            float cx = left + t * width, cy = yOf(v, top, height);
            canvas.drawCircle(cx, cy, dotR, ridePaint);
            canvas.drawCircle(cx, cy, dotR, rideRing);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
