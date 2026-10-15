package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.vanvatcorporation.doubleclips.R;

/**
 * The ruler slider of the mask panel (iOS design): ticks slide under a fixed cursor in the middle, one tick is one unit
 * with a taller one every 5, and dragging RIGHT lowers the value (the ruler follows the finger). A light haptic tick per
 * step. Whole numbers while dragging; {@link #setValue} may show any value in the range.
 * <p>
 * One gesture = {@link Listener#onStart()}, any number of {@link Listener#onChanging}, then {@link Listener#onCommit}.
 */
public final class RulerSliderView extends View {

    public interface Listener {
        /** A drag begins (take the "before" snapshot for undo here). */
        void onStart();

        /** The value moved to a new step (apply it live, no undo step yet). */
        void onChanging(float value);

        /** The finger lifted: the gesture is over. */
        void onCommit(float value);
    }

    private static final float TICK_DP = 9f;

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float tickPx;

    private float min = 0f, max = 100f, value;
    private Listener listener;
    private boolean dragging;
    private float downX, downValue;

    public RulerSliderView(Context context) {
        this(context, null);
    }

    public RulerSliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        tickPx = TICK_DP * density;
        TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
        int secondary = a.getColor(0, 0xFF888888);
        a.recycle();
        tick.setColor(secondary);
        tick.setAlpha(110);
        tick.setStrokeWidth(1f * density);
        tall.setColor(secondary);
        tall.setStrokeWidth(1.5f * density);
        cursor.setColor(context.getColor(R.color.ios_blue));
        cursor.setStrokeWidth(2f * density);
        cursor.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The values the slider covers; the value is kept inside. */
    public void setRange(float min, float max) {
        this.min = min;
        this.max = Math.max(max, min);
        value = clamp(value);
        invalidate();
    }

    /** Shows this value (not while a drag is running: the finger decides then). */
    public void setValue(float v) {
        if (dragging) return;
        value = clamp(v);
        invalidate();
    }

    public float getValue() {
        return value;
    }

    private float clamp(float v) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(Math.round(200 * density), widthMeasureSpec),
                resolveSize(Math.round(44 * density), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float centerX = w / 2f;
        float halfSpan = centerX / tickPx;
        int from = (int) Math.max(Math.ceil(value - halfSpan), Math.ceil(min));
        int to = (int) Math.min(Math.floor(value + halfSpan), Math.floor(max));
        float midY = h / 2f;
        for (int v = from; v <= to; v++) {
            float x = centerX + (v - value) * tickPx;
            boolean big = ((v % 5) + 5) % 5 == 0;
            float half = (big ? 0.28f : 0.16f) * h;
            canvas.drawLine(x, midY - half, x, midY + half, big ? tall : tick);
        }
        canvas.drawLine(centerX, 0.12f * h, centerX, 0.88f * h, cursor);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                downX = e.getX();
                downValue = value;
                if (listener != null) listener.onStart();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float dx = e.getX() - downX;
                if (Math.abs(dx) < tickPx * 0.5f) return true; // not a step yet (and a value like 33.4 doesn't jump on touch)
                float next = clamp(Math.round(downValue - dx / tickPx)); // right = lower
                if (next != value) {
                    value = next;
                    invalidate();
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.onChanging(value);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (listener != null) listener.onCommit(value);
                }
                return true;
            default:
                return true;
        }
    }
}
