package com.vanvatcorporation.doubleclips.impl;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.vanvatcorporation.doubleclips.R;

/**
 * Drag-to-reorder for the timeline's tracks. While reorder mode is on, each track label turns into a grip
 * ("≡ T3"); dragging one moves its track, and the rows it passes slide out of the way.
 * <p>
 * The drag only moves views (translations). The tracks themselves are untouched until the finger lifts, then
 * {@link Host#move} applies the change once, as one undoable command. The geometry is {@link TrackReorderDrag}.
 */
public final class TrackReorderController {

    public interface Host {
        /** How many tracks there are. */
        int trackCount();

        /** A drag starts: playback would fight with moving rows. */
        void onDragStart();

        /** Apply "track {@code from} goes to {@code to}" (an undoable command). */
        void move(int from, int to);
    }

    private static final int GRIP_BACKGROUND = 0xFF33405A;
    private static final long SLIDE_MILLIS = 120;

    private final ViewGroup labels;   // label per track, then the add-track button
    private final ViewGroup rows;     // row per track, then the blank spacer
    private final Host host;
    private final int rowHeight;
    private final float density;

    private boolean enabled;
    private TrackReorderDrag drag;
    private float downRawY;
    private int lastTarget;
    private float[] shown = new float[0]; // the offset each slot is currently animating to
    private android.graphics.drawable.Drawable draggedBackground;

    public TrackReorderController(ViewGroup labels, ViewGroup rows, int rowHeightPx, float density, Host host) {
        this.labels = labels;
        this.rows = rows;
        this.rowHeight = rowHeightPx;
        this.density = density;
        this.host = host;
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean on) {
        if (!on) cancelDrag();
        enabled = on;
        refresh();
    }

    /** Re-applies the mode to the current labels; call after a track was added or removed. */
    public void refresh() {
        int n = host.trackCount();
        for (int i = 0; i < n && i < labels.getChildCount(); i++) {
            View v = labels.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView label = (TextView) v;
            if (enabled) {
                if (label.getTag(TAG_ORIGINAL_TEXT) == null) label.setTag(TAG_ORIGINAL_TEXT, label.getText());
                label.setText("≡\nT" + (i + 1));
                label.setOnTouchListener(this::onLabelTouch);
            } else {
                Object original = label.getTag(TAG_ORIGINAL_TEXT);
                if (original instanceof CharSequence) label.setText((CharSequence) original);
                label.setTag(TAG_ORIGINAL_TEXT, null);
                label.setOnTouchListener(null);
            }
        }
    }

    /** Key for the label text that reorder mode replaces, so leaving the mode can put it back. */
    private static final int TAG_ORIGINAL_TEXT = R.id.trackReorderOriginalText;

    private boolean onLabelTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                int index = labels.indexOfChild(v);
                if (index < 0 || index >= host.trackCount() || host.trackCount() < 2) return true;
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                begin(index, e.getRawY(), v);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drag != null) update(e.getRawY());
                return true;
            case MotionEvent.ACTION_UP:
                finish(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                finish(false);
                return true;
            default:
                return true;
        }
    }

    private void begin(int index, float rawY, View label) {
        host.onDragStart();
        label.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        drag = new TrackReorderDrag(index, host.trackCount(), rowHeight);
        downRawY = rawY;
        lastTarget = index;
        shown = new float[drag.count];
        draggedBackground = label.getBackground();
        label.setBackgroundColor(GRIP_BACKGROUND);
        float lift = 8 * density;
        label.setTranslationZ(lift);
        View row = rows.getChildAt(index);
        if (row != null) row.setTranslationZ(lift);
    }

    private void update(float rawY) {
        drag.setTranslation(rawY - downRawY);
        int target = drag.targetIndex();
        if (target != lastTarget) {
            lastTarget = target;
            labels.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        for (int i = 0; i < drag.count; i++) {
            float offset = drag.offsetForRow(i);
            View label = labels.getChildAt(i), row = rows.getChildAt(i);
            if (i == drag.startIndex) { // the grabbed row sticks to the finger, no animation
                if (label != null) label.setTranslationY(offset);
                if (row != null) row.setTranslationY(offset);
            } else if (offset != shown[i]) { // the rows it passes slide
                shown[i] = offset;
                if (label != null) label.animate().translationY(offset).setDuration(SLIDE_MILLIS).start();
                if (row != null) row.animate().translationY(offset).setDuration(SLIDE_MILLIS).start();
            }
        }
    }

    private void finish(boolean apply) {
        if (drag == null) return;
        int from = drag.startIndex, to = drag.targetIndex();
        View dragged = labels.getChildAt(from);
        resetViews();
        if (dragged != null) dragged.setBackground(draggedBackground);
        drag = null;
        if (apply && to != from) host.move(from, to);
    }

    /** Puts every label and row back at rest (also cancels slides in flight). */
    private void resetViews() {
        for (int i = 0; i < host.trackCount(); i++) {
            View label = labels.getChildAt(i), row = rows.getChildAt(i);
            if (label != null) { label.animate().cancel(); label.setTranslationY(0); label.setTranslationZ(0); }
            if (row != null) { row.animate().cancel(); row.setTranslationY(0); row.setTranslationZ(0); }
        }
    }

    private void cancelDrag() {
        if (drag == null) return;
        View dragged = labels.getChildAt(drag.startIndex);
        resetViews();
        if (dragged != null) dragged.setBackground(draggedBackground);
        drag = null;
    }
}
