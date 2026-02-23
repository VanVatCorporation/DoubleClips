package com.vanvatcorporation.doubleclips.activities;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * Canvas-drawn timeline ruler for the editing screen.
 *
 * Coordinate system:
 *   x = startOffset + t * pixelsPerSecond
 *   where startOffset == EditingActivity.centerOffset so that
 *   t=0 aligns with the centre playhead, matching the track layout.
 *
 * Tick levels
 * ───────────
 *   BIG   | every whole second          label: "0s", "1s", "1m30s" …
 *   MEDIUM / every rulerInterval step   label: "Nf"  (frames within that second,
 *                                                      reset to 1f after each |)
 *   SMALL  \ sub-divisions between      no label
 *             successive medium ticks
 *
 * rulerInterval (seconds) is supplied by EditingActivity.currentRulerInterval.
 * When it equals 1 s, the medium tier collapses to zero and only seconds show.
 * When it equals 0.5 s → "15f" marks appear between each second.
 * When it equals 0.1 s → "3f","6f",…"27f" marks appear, etc.
 *
 * Frame numbers restart at 1f after every second boundary.
 */
public class TimelineRulerView extends View {

    // ── Parameters ────────────────────────────────────────────────────────────
    private float pixelsPerSecond = 100f;
    private float totalDuration   = 60f;
    private int   fps             = 30;
    /** Left blank padding in px — must equal EditingActivity.centerOffset */
    private int   startOffset     = 0;
    /**
     * The interval between major (labelled) ticks, in seconds.
     * Values like 0.5, 0.2, 0.1, 0.05 … from updateRulerEfficiently().
     * Default 1 = one labelled tick per second.
     */
    private float rulerInterval   = 1f;

    // ── Paint ─────────────────────────────────────────────────────────────────
    private final Paint bigPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint medPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Tick height fractions ─────────────────────────────────────────────────
    private static final float BIG_H   = 1.00f;
    private static final float MED_H   = 0.60f;
    private static final float SMALL_H = 0.35f;

    // ─────────────────────────────────────────────────────────────────────────
    public TimelineRulerView(Context c) { super(c); init(c); }
    public TimelineRulerView(Context c, AttributeSet a) { super(c, a); init(c); }
    public TimelineRulerView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context ctx) {
        float sp8 = sp(ctx, 8f);

        bigPaint.setColor(Color.WHITE); bigPaint.setAlpha(220);
        bigPaint.setStrokeWidth(1.5f);  bigPaint.setStyle(Paint.Style.STROKE);

        medPaint.setColor(Color.WHITE); medPaint.setAlpha(160);
        medPaint.setStrokeWidth(1f);    medPaint.setStyle(Paint.Style.STROKE);

        smallPaint.setColor(Color.WHITE); smallPaint.setAlpha(80);
        smallPaint.setStrokeWidth(0.8f);  smallPaint.setStyle(Paint.Style.STROKE);

        textPaint.setColor(Color.WHITE); textPaint.setAlpha(180);
        textPaint.setTextSize(sp8);      textPaint.setAntiAlias(true);
    }

    private static float sp(Context ctx, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                ctx.getResources().getDisplayMetrics());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setPixelsPerSecond(float pps) {
        if (pixelsPerSecond == pps) return;
        pixelsPerSecond = pps;
        requestLayout(); invalidate();
    }

    public void setTotalDuration(float sec) {
        if (totalDuration == sec) return;
        totalDuration = sec;
        requestLayout(); invalidate();
    }

    public void setFps(int f) {
        fps = Math.max(1, f);
        invalidate();
    }

    public void setStartOffset(int offset) {
        if (startOffset == offset) return;
        startOffset = offset;
        requestLayout(); invalidate();
    }

    /**
     * Set the major tick interval in seconds.
     * Must match EditingActivity.currentRulerInterval.
     */
    public void setRulerInterval(float intervalSeconds) {
        if (rulerInterval == intervalSeconds) return;
        rulerInterval = Math.max(0.001f, intervalSeconds);
        invalidate();
    }

    // ── Measurement ───────────────────────────────────────────────────────────
    // Width = startOffset  +  duration×pps  +  startOffset  (mirrors track layout)

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int desired = startOffset + (int)(pixelsPerSecond * (totalDuration + 2f)) + startOffset;
        int w = resolveSize(desired, wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (h == 0) {
            h = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f,
                    getResources().getDisplayMetrics());
        }
        setMeasuredDimension(w, h);
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float h   = getHeight();
        final float pps = pixelsPerSecond;

        // How many sub-ticks to place between two successive major ticks?
        // We want them only if there is enough room.
        final float majorPixels = pps * rulerInterval;  // px gap between two major ticks
        final int   subCount    = pickSubCount(majorPixels);

        // Iterate major ticks (in seconds)
        // Use integer steps to avoid floating-point accumulation
        final long steps = (long)(Math.ceil((totalDuration + 2f) / rulerInterval));

        for (long step = 0; step <= steps; step++) {
            float t = step * rulerInterval;             // time in seconds
            float x = startOffset + t * pps;

            // Determine if this major tick falls on a whole second
            boolean isWholeSecond = isNearInteger(t, rulerInterval);

            if (isWholeSecond) {
                // ── BIG tick + "Xs" label ──────────────────────────────────
                canvas.drawLine(x, h, x, h - h * BIG_H, bigPaint);
                int sec = Math.round(t);
                String lbl = sec == 0 ? "0s"
                        : sec < 60  ? sec + "s"
                        : (sec / 60) + "m" + (sec % 60) + "s";
                canvas.drawText(lbl, x + 2f, textPaint.getTextSize() + 1f, textPaint);

            } else {
                // ── MEDIUM tick + "Nf" label ───────────────────────────────
                // Frame number within the current second (reset to 1 after each |)
                float secondFraction = (float) (t - Math.floor(t));   // 0.0 … <1.0
                int frameInSec = Math.max(1, Math.round(secondFraction * fps));
                String lbl = frameInSec + "f";

                canvas.drawLine(x, h, x, h - h * MED_H, medPaint);
                // Only draw text label if there is enough horizontal room
                if (majorPixels >= 14f) {
                    canvas.drawText(lbl, x + 1.5f, textPaint.getTextSize() + 1f, textPaint);
                }
            }

            // ── SMALL sub-ticks between this and the next major tick ───────
            if (subCount > 0) {
                float subInterval = rulerInterval / (subCount + 1);
                for (int s = 1; s <= subCount; s++) {
                    float subT = t + subInterval * s;
                    float subX = startOffset + subT * pps;
                    canvas.drawLine(subX, h, subX, h - h * SMALL_H, smallPaint);
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Return true if t is within half-interval of a whole second.
     * Avoids floating-point drift like 0.9999999 ≠ 1.0.
     */
    private static boolean isNearInteger(float t, float interval) {
        float frac = t - (float) Math.floor(t);
        float tol  = interval * 0.02f; // 2% tolerance
        return frac < tol || frac > (1f - tol);
    }

    /**
     * Pick sub-tick count based on available pixel width for the major interval.
     * 0 means no sub-ticks (too crowded).
     */
    private static int pickSubCount(float majorPixels) {
        if (majorPixels >= 60f) return 4;   // 4 sub-ticks → 5 equal segments
        if (majorPixels >= 28f) return 1;   // 1 sub-tick  → 2 equal halves
        return 0;
    }
}
