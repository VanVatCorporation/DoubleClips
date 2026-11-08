package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.vanvatcorporation.doubleclips.ClipProcessingTracker;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.util.List;

/**
 * The badge over the top left of the preview canvas (CapCut's "Applying 1 feature..."): shows while any clip feature is being
 * pre-processed (see {@link ClipProcessingTracker}); tap it to list which clip and feature are at what percent; says "... applied" with
 * a green tick for a moment when the last one finishes. Hidden (and out of the layout) when idle. Same as Desktop's ProcessingBadge.
 * <p>
 * {@link #refresh()} runs on the UI thread; the editor calls it, coalesced, whenever the tracker reports a change. The static helpers
 * build the localized texts the clip's timeline label and the badge share.
 */
public class ProcessingBadgeView extends LinearLayout {

    private final ProgressBar spinner;
    private final TextView tick, title, chevron;
    private final LinearLayout list;
    private boolean expanded;

    public ProcessingBadgeView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        int dp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        setPadding(10 * dp, 7 * dp, 12 * dp, 7 * dp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB1E1E22);
        bg.setCornerRadius(10 * dp);
        bg.setStroke(dp, 0x1AFFFFFF);
        setBackground(bg);
        setElevation(8 * dp);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);
        head.addView(spinner, new LayoutParams(16 * dp, 16 * dp));

        tick = new TextView(context);
        tick.setText("\u2713");
        tick.setTextColor(0xFF3CCB7F);
        tick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tick.setTypeface(tick.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(tick);

        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LayoutParams titleParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        titleParams.leftMargin = 8 * dp;
        head.addView(title, titleParams);

        chevron = new TextView(context);
        chevron.setTextColor(0xFFB9BCC4);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LayoutParams chevronParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chevronParams.leftMargin = 8 * dp;
        head.addView(chevron, chevronParams);

        head.setOnClickListener(v -> {
            expanded = !expanded;
            refresh();
        });
        addView(head);

        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        list.setPadding(24 * dp, 3 * dp, 0, 0);
        addView(list);

        setVisibility(GONE);
    }

    /** Re-reads the tracker and redraws. UI thread only. */
    public void refresh() {
        ClipProcessingTracker tracker = ClipProcessingTracker.get();
        List<ClipProcessingTracker.Job> running = tracker.running();
        ClipProcessingTracker.Job done = running.isEmpty() ? tracker.lastFinished() : null;

        boolean busy = !running.isEmpty();
        setVisibility(busy || done != null ? VISIBLE : GONE);
        spinner.setVisibility(busy ? VISIBLE : GONE);
        tick.setVisibility(busy ? GONE : VISIBLE);
        chevron.setVisibility(busy ? VISIBLE : GONE);
        list.removeAllViews();

        if (busy) {
            title.setText(badgeTitle(getContext(), running.size()));
            chevron.setText(expanded ? "\u25B4" : "\u25BE");
            if (expanded) {
                for (ClipProcessingTracker.Job job : running) {
                    TextView row = new TextView(getContext());
                    row.setTextColor(0xFFD5D7DD);
                    row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    row.setText(clipName(job) + " \u00B7 " + featureName(getContext(), job.feature) + "  " + ClipProcessingTracker.percent(job.progress()));
                    list.addView(row);
                }
            }
        } else if (done != null) {
            title.setText(getContext().getString(R.string.feature_applied, featureName(getContext(), done.feature)));
            expanded = false;
            // look again when the message has been shown long enough
            removeCallbacks(this::refresh);
            postDelayed(this::refresh, ClipProcessingTracker.DONE_VISIBLE_MS + 100);
        } else {
            expanded = false;
        }
        list.setVisibility(list.getChildCount() > 0 ? VISIBLE : GONE);
    }

    private static String clipName(ClipProcessingTracker.Job job) {
        return job.clip instanceof EditingActivity.Clip && ((EditingActivity.Clip) job.clip).getClipName() != null
                ? ((EditingActivity.Clip) job.clip).getClipName() : "Clip";
    }

    // ── texts shared with the timeline clip's label ───────────────────────

    /** "Reverse" in the user's language; a feature this build has no string for is shown as the tracker names it. */
    public static String featureName(Context context, String feature) {
        if (ClipProcessingTracker.REVERSE.equals(feature)) return context.getString(R.string.feature_reverse);
        return feature;
    }

    /** "Applying 1 feature…" / "Applying 3 features…". */
    public static String badgeTitle(Context context, int count) {
        return count == 1 ? context.getString(R.string.applying_one_feature) : context.getString(R.string.applying_n_features, count);
    }

    /**
     * The line a clip shows in the timeline while something is applied to it: "Applying Reverse… 52.6%", or "Applying 2 features… 40.0%"
     * (the average) for several; null when nothing is running for the clip.
     */
    public static String clipLabel(Context context, Object clip) {
        List<ClipProcessingTracker.Job> mine = ClipProcessingTracker.get().runningFor(clip);
        if (mine.isEmpty()) return null;
        double sum = 0;
        for (ClipProcessingTracker.Job j : mine) sum += j.progress();
        String percent = ClipProcessingTracker.percent(sum / mine.size());
        if (mine.size() == 1) return context.getString(R.string.applying_feature_percent, featureName(context, mine.get(0).feature), percent);
        return context.getString(R.string.applying_n_features_percent, mine.size(), percent);
    }
}
