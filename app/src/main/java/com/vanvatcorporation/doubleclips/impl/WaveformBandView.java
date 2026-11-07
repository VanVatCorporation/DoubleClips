package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * The sound of a video clip as a thin waveform band along the bottom of its timeline view.
 * <p>
 * It used to be baked into the clip's thumbnail strip (one bitmap, capped at 4096 px wide, then stretched by the image view to
 * the clip's width), so zooming in on a long video stretched the bars horizontally. Now the loudness of the clip's audio is
 * decoded ONCE into a fine array of levels, and this view draws its bars straight from that array at the clip's CURRENT width
 * every time it is drawn: the bars keep the same width and gap at any zoom, and nothing has to be regenerated when zooming.
 * <p>
 * Only the part of the view that is on screen is drawn (the view can be tens of thousands of pixels wide), and a scroll of the
 * timeline redraws a wide band so the bars follow the viewport.
 */
public class WaveformBandView extends View {

    /** Where the clip's trim currently starts in the source file and how many source seconds the clip shows. */
    public interface Window {
        float sourceStartSeconds();
        float sourceSpanSeconds();
    }

    private float[] levels;
    private float levelsStartSec, levelsSpanSec;
    private Window window;
    private int barWidthPx = 1, barGapPx = 0;
    /** An audio clip's own look (opaque navy, centre line, blue bars that fade with loudness) instead of the video band's. */
    private boolean audioClipStyle = false;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final Rect visible = new Rect();

    /** A band wider than this follows the timeline's scroll (a narrow one is fully drawn once and never needs to). */
    private static final int FOLLOW_SCROLL_MIN_WIDTH_PX = 3000;

    private final ViewTreeObserver.OnScrollChangedListener scrollListener = () -> {
        if (levels != null && getWidth() > FOLLOW_SCROLL_MIN_WIDTH_PX) invalidate();
    };

    public WaveformBandView(Context context) {
        super(context);
        barPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    /**
     * @param levels         loudness 0..1 of equal slices of the decoded span
     * @param startSec       source second where the first slice begins
     * @param spanSec        source seconds the slices cover
     * @param window         the clip's current trim (read on every draw, so a trim needs no new decode)
     */
    public void setLevels(float[] levels, float startSec, float spanSec, Window window, int barWidthPx, int barGapPx) {
        this.levels = levels;
        this.levelsStartSec = startSec;
        this.levelsSpanSec = Math.max(1e-3f, spanSec);
        this.window = window;
        this.barWidthPx = Math.max(1, barWidthPx);
        this.barGapPx = Math.max(0, barGapPx);
        invalidate();
    }

    public boolean hasLevels() { return levels != null; }

    /** The look of a standalone audio clip's waveform (the whole clip view) rather than a video clip's thin band. */
    public void setAudioClipStyle(boolean audioClipStyle) {
        this.audioClipStyle = audioClipStyle;
        invalidate();
    }

    /** Gives another band (the drag ghost's copy) the same sound. */
    public void copyFrom(WaveformBandView other) {
        if (other == null || other.levels == null) return;
        audioClipStyle = other.audioClipStyle;
        setLevels(other.levels, other.levelsStartSec, other.levelsSpanSec, other.window, other.barWidthPx, other.barGapPx);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (levels == null || levels.length == 0 || window == null || w <= 0 || h <= 0) return;

        if (audioClipStyle) {
            canvas.drawColor(0xFF0D1B2A); // dark navy, nothing behind it
            barPaint.setColor(0x3C1E90FF);
            barPaint.setStrokeWidth(1f);
            canvas.drawLine(0, h / 2f, w, h / 2f, barPaint); // thin centre line
        } else {
            canvas.drawColor(0x66000000); // translucent: the frames still show through
        }

        final float windowStart = window.sourceStartSeconds();
        final float windowSpan = Math.max(1e-3f, window.sourceSpanSeconds());
        final float secPerPx = windowSpan / w;
        final int stride = barWidthPx + barGapPx;

        // Only what is on screen (plus a bar), in this view's own coordinates.
        int from = 0, to = w;
        if (w > FOLLOW_SCROLL_MIN_WIDTH_PX) {
            if (!getLocalVisibleRect(visible)) return;
            from = Math.max(0, visible.left - stride);
            to = Math.min(w, visible.right + stride);
        }
        from -= from % stride; // bars stay on the same x whatever part is drawn

        final float mid = h / 2f;
        final float maxHalf = mid - 1.5f;
        final float levelsPerSec = levels.length / levelsSpanSec;

        for (int x = from; x < to; x += stride) {
            // The source seconds this bar stands for (the whole stride's worth), as a range in the levels array.
            float t0 = windowStart + x * secPerPx;
            float t1 = t0 + stride * secPerPx;
            if (t1 <= levelsStartSec || t0 >= levelsStartSec + levelsSpanSec) continue; // outside what was decoded

            int i0 = (int) Math.floor((t0 - levelsStartSec) * levelsPerSec);
            int i1 = (int) Math.ceil((t1 - levelsStartSec) * levelsPerSec);
            i0 = Math.max(0, Math.min(levels.length - 1, i0));
            i1 = Math.max(i0 + 1, Math.min(levels.length, i1));

            float level = 0f; // the loudest slice under the bar, so a zoomed-out clip keeps its peaks
            for (int i = i0; i < i1; i++) if (levels[i] > level) level = levels[i];

            float half;
            if (audioClipStyle) {
                half = Math.max(2f, Math.min(1f, level) * maxHalf); // at least 2 px visible
                barPaint.setColor(Color.argb((int) (10 + 245 * Math.min(1f, level)), 0x1E, 0x90, 0xFF)); // #1E90FF, louder = brighter
            } else {
                float loud = Math.min(1f, level * 2f);
                // sqrt: quiet passages stay visible, like most editors' waveforms
                half = Math.max(1f, (float) Math.sqrt(loud) * maxHalf);
                barPaint.setColor(Color.argb(120 + (int) (120 * loud), 255, 255, 255));
            }
            barRect.set(x, mid - half, x + barWidthPx, mid + half);
            canvas.drawRoundRect(barRect, 1f, 1f, barPaint);
        }
    }
}
