package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The text inside the Templates tab's search bar. With hot searches it shows one for about 3 seconds, then that one flies
 * up and out while the next flies up in from below (the same ticker as iOS and Desktop); without them it shows the plain
 * hint. Two TextViews take turns; the view clips them, so the text only shows inside the bar.
 */
public class TrendingTickerView extends FrameLayout {
    private static final long HOLD_MS = 3000;
    private static final long FLY_MS = 450;

    private final TextView[] slots = new TextView[2];
    private int front = 0;
    private final List<String> terms = new ArrayList<>();
    private int index = 0;
    private String hint = "";
    private boolean flying = false;

    private final Runnable tick = this::flyToNext;

    public TrendingTickerView(Context context) { this(context, null); }

    public TrendingTickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(true);
        setClipToPadding(true);
        for (int i = 0; i < 2; i++) {
            TextView t = new TextView(context);
            t.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTextColor(themeColor(context, android.R.attr.textColorSecondary));
            t.setVisibility(i == 0 ? VISIBLE : INVISIBLE);
            slots[i] = t;
            addView(t);
        }
    }

    private static int themeColor(Context context, int attr) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            android.content.res.ColorStateList list = a.getColorStateList(0);
            return list == null ? 0xFF808080 : list.getDefaultColor();
        } finally {
            a.recycle();
        }
    }

    /** What the bar says when there are no hot searches (the plain prompt). */
    public void setHint(String text) {
        hint = text == null ? "" : text;
        if (terms.isEmpty()) slots[front].setText(hint);
    }

    /** The hot searches to rotate through. The same list again changes nothing, so a refresh does not restart the ticker. */
    public void setTerms(List<String> newTerms) {
        List<String> clean = new ArrayList<>();
        if (newTerms != null) for (String t : newTerms) if (t != null && !t.trim().isEmpty()) clean.add(t.trim());
        if (clean.equals(terms)) return;
        stopTicker();
        terms.clear();
        terms.addAll(clean);
        index = 0;
        slots[front].setText(terms.isEmpty() ? hint : terms.get(0));
        startTicker();
    }

    /** The hot search on show right now, or null when the bar shows the plain hint. */
    public String currentTerm() {
        return terms.isEmpty() ? null : terms.get(Math.min(index, terms.size() - 1));
    }

    private void startTicker() {
        removeCallbacks(tick);
        if (terms.size() > 1 && isAttachedToWindow()) postDelayed(tick, HOLD_MS);
    }

    private void stopTicker() {
        removeCallbacks(tick);
        slots[0].animate().cancel();
        slots[1].animate().cancel();
        flying = false;
        // whatever was mid-flight: the front slot shows the current term, in place; the other waits out of sight
        TextView shown = slots[front], other = slots[1 - front];
        shown.setTranslationY(0);
        shown.setAlpha(1);
        shown.setVisibility(VISIBLE);
        other.setTranslationY(0);
        other.setAlpha(0);
        other.setVisibility(INVISIBLE);
    }

    private void flyToNext() {
        if (terms.size() < 2 || !isAttachedToWindow() || flying) return;
        if (getHeight() == 0) { postDelayed(tick, 200); return; }   // not laid out yet: try again shortly
        flying = true;
        final int next = (index + 1) % terms.size();
        final TextView out = slots[front], in = slots[1 - front];
        final float h = getHeight();

        in.setText(terms.get(next));
        in.setTranslationY(h);       // waits below the bar
        in.setAlpha(0);
        in.setVisibility(VISIBLE);

        out.animate().translationY(-h).alpha(0).setDuration(FLY_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        in.animate().translationY(0).alpha(1).setDuration(FLY_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    out.setVisibility(INVISIBLE);
                    out.setTranslationY(0);
                    front = 1 - front;
                    index = next;
                    flying = false;
                    startTicker();
                }).start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startTicker();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTicker();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView != this) return;
        if (visibility == VISIBLE) startTicker(); else stopTicker();
    }
}
