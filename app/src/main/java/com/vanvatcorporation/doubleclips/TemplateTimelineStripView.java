package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;

/**
 * The preview timeline of a template (iOS: TemplateTimelineStrip): a legend and a clock above a strip of the template's
 * clips. Time runs left to right, tracks are rows, the strip slides under a fixed red playhead as the preview video plays,
 * and a horizontal drag scrubs the video.
 */
public class TemplateTimelineStripView extends View {

    public interface Listener {
        /** The user drags the strip: show this time (seconds). */
        void onScrub(double timeSeconds);
        /** The drag ended. */
        void onScrubEnd();
    }

    private static final float PIXELS_PER_SECOND_DP = 36f;
    private static final float STRIP_HEIGHT_DP = 64f;
    private static final float LEGEND_HEIGHT_DP = 18f;
    private static final float GAP_DP = 2f;

    private TemplateTimelineInfo info;
    private double currentTime;
    private Listener listener;

    private boolean scrubbing;
    private float downX, downY;
    private double timeAtDown;

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();

    public TemplateTimelineStripView(Context context) { this(context, null); }

    public TemplateTimelineStripView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(density);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setInfo(@Nullable TemplateTimelineInfo info) {
        this.info = info;
        invalidate();
    }

    @Nullable public TemplateTimelineInfo getInfo() { return info; }

    public boolean isScrubbing() { return scrubbing; }

    /** Seconds into the preview video. Ignored while the user is dragging the strip. */
    public void setCurrentTime(double seconds) {
        if (scrubbing) return;
        currentTime = Math.max(0, seconds);
        invalidate();
    }

    private float dp(float v) { return v * density; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = Math.round(dp(LEGEND_HEIGHT_DP + 6f + STRIP_HEIGHT_DP));
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float stripTop = dp(LEGEND_HEIGHT_DP + 6f);
        float stripH = dp(STRIP_HEIGHT_DP);
        double duration = info == null ? 0 : info.duration;

        drawLegend(canvas, w, duration);

        // The strip's background and border.
        rect.set(0, stripTop, w, stripTop + stripH);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x73000000);
        canvas.drawRoundRect(rect, dp(8), dp(8), fill);

        // Everything inside is clipped to the rounded strip.
        int save = canvas.save();
        clip.reset();
        clip.addRoundRect(rect, dp(8), dp(8), Path.Direction.CW);
        canvas.clipPath(clip);
        if (info != null) drawClips(canvas, w, stripTop, stripH);
        drawPlayhead(canvas, w, stripTop, stripH);
        canvas.restoreToCount(save);

        stroke.setColor(0x40FFFFFF);
        canvas.drawRoundRect(rect, dp(8), dp(8), stroke);
    }

    private void drawLegend(Canvas canvas, float w, double duration) {
        text.setTextSize(dp(10));
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setColor(0xD9FFFFFF);
        text.setTextAlign(Paint.Align.LEFT);
        float baseline = dp(LEGEND_HEIGHT_DP) / 2f - (text.ascent() + text.descent()) / 2f;
        float x = 0;
        if (info != null) {
            for (TemplateTimelineInfo.Role role : info.roles()) {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(role.colorArgb);
                canvas.drawCircle(x + dp(4), dp(LEGEND_HEIGHT_DP) / 2f, dp(4), fill);
                canvas.drawText(role.title, x + dp(12), baseline, text);
                x += dp(12) + text.measureText(role.title) + dp(10);
            }
        }
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(TemplateStripMath.clock(currentTime) + " / " + TemplateStripMath.clock(duration), w, baseline, text);
    }

    private void drawClips(Canvas canvas, float w, float stripTop, float stripH) {
        float pps = dp(PIXELS_PER_SECOND_DP);
        float gap = dp(GAP_DP);
        float rowH = TemplateStripMath.rowHeight(stripH, info.rowCount, gap, dp(5), dp(18));
        for (TemplateTimelineInfo.StripClip c : info.clips) {
            float left = TemplateStripMath.clipLeft(w, currentTime, c.start, pps);
            float cw = Math.max((float) c.duration * pps, dp(3));
            float right = left + Math.max(cw - dp(1), dp(2));
            if (right < 0 || left > w) continue; // off screen
            float top = stripTop + dp(4) + c.row * (rowH + gap);
            rect.set(left, top, right, top + rowH);
            int alpha = c.role == TemplateTimelineInfo.Role.REPLACEABLE ? 0xF2 : 0xD9;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor((c.role.colorArgb & 0x00FFFFFF) | (alpha << 24));
            canvas.drawRoundRect(rect, dp(3), dp(3), fill);
            if (c.slotNumber > 0 && cw >= dp(14) && rowH >= dp(9)) {
                text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                text.setTextSize(Math.min(rowH - dp(2), dp(11)));
                text.setColor(0xBF000000);
                text.setTextAlign(Paint.Align.CENTER);
                float baseline = rect.centerY() - (text.ascent() + text.descent()) / 2f;
                canvas.drawText(String.valueOf(c.slotNumber), rect.centerX(), baseline, text);
            }
        }
    }

    private void drawPlayhead(Canvas canvas, float w, float stripTop, float stripH) {
        float cx = w / 2f;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFF3B30);
        canvas.drawRect(cx - dp(1), stripTop, cx + dp(1), stripTop + stripH, fill);
        canvas.drawCircle(cx, stripTop + dp(2), dp(4), fill);
    }

    // --------------------------------------------------------------------------------------------- drag to scrub

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (info == null || info.duration <= 0) return super.onTouchEvent(e);
        float slop = dp(3);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); timeAtDown = currentTime;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (!scrubbing) {
                    // Horizontal drags are ours; a mostly-vertical one belongs to the page swipe.
                    if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(e.getY() - downY)) return true;
                    scrubbing = true;
                    ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                }
                double t = TemplateStripMath.scrubTime(timeAtDown, dx, dp(PIXELS_PER_SECOND_DP), info.duration);
                currentTime = t;
                invalidate();
                if (listener != null) listener.onScrub(t);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scrubbing) {
                    scrubbing = false;
                    if (listener != null) listener.onScrubEnd();
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
