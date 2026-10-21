package com.vanvatcorporation.drivekit;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import com.google.android.material.progressindicator.BaseProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import android.widget.TextView;

/** Android bits of the quota UI: the "Continue anyway" warning, tone-coloured bars and the upgrade link. */
public final class QuotaUi {
    private QuotaUi() {}

    /** Warns that a transfer may not fit; the user can still continue. onCancel may be null. */
    public static void warn(Context c, String title, String message, Runnable onConfirm, Runnable onCancel) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, (d, w) -> { if (onCancel != null) onCancel.run(); })
                .setPositiveButton("Continue anyway", (d, w) -> onConfirm.run())
                .setOnCancelListener(d -> { if (onCancel != null) onCancel.run(); })
                .show();
    }

    /** A blocking notice with a "Get more resources" button (used when something definitely cannot fit). */
    public static void blocked(Context c, String title, String message) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton("Get more resources", (d, w) -> openPlans(c))
                .show();
    }

    /** Opens the account tier upgrade in the browser. */
    public static void openPlans(Context c) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Quota.PLANS_URL));
        if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (ActivityNotFoundException ignored) { /* no browser */ }
    }

    /** Sets a max=1000 bar to used/total and colours it by how much is left (works for Material indicators and plain bars). */
    public static void apply(ProgressBar bar, double used, double total) {
        bar.setMax(1000);
        bar.setProgress(Quota.progress(used, total));
        int color = Quota.color(Quota.tone(used, total));
        if (bar instanceof BaseProgressIndicator) ((BaseProgressIndicator<?>) bar).setIndicatorColor(color);
        else bar.setProgressTintList(ColorStateList.valueOf(color));
    }

    /** A thin rounded bar with a small caption, spaced apart from the next one. Add to a vertical LinearLayout. */
    public static void addBar(LinearLayout parent, double used, double total, String label) {
        Context c = parent.getContext();
        float d = c.getResources().getDisplayMetrics().density;

        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(t.getTextColors().withAlpha(200));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = (int) (parent.getChildCount() == 0 ? 0 : 14 * d);   // clear gap between one quota and the next
        parent.addView(t, tp);

        LinearProgressIndicator bar = new LinearProgressIndicator(c);
        bar.setTrackThickness((int) (6 * d));
        bar.setTrackCornerRadius((int) (3 * d));
        apply(bar, used, total);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (6 * d);
        parent.addView(bar, bp);
    }

    /**
     * Minimalist quota: only the main bar shows; tapping {@code header} slides {@code details} open (and turns
     * {@code arrow} around). {@code container} is the layout both sit in, so the change animates.
     */
    public static void bindCollapse(ViewGroup container, View header, View details, View arrow) {
        header.setOnClickListener(v -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            TransitionManager.beginDelayedTransition(container, new AutoTransition().setDuration(220));
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(show ? 180f : 0f).setDuration(220).start();
        });
    }
}
