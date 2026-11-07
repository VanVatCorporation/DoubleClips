package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.EasingType;

/**
 * The keyframe easing picker, replacing the plain dropdown of 33 names (Desktop and iOS have this too): the field is a small
 * graph of the current curve with its name; tapping it opens a bottom sheet with a big live preview (a dot rides the curve) and
 * a grid of square tiles, each drawing its own curve. Picking a tile selects it right away; the sheet stays open so several can
 * be tried, and "Done" closes it.
 */
public class EasingPickerField extends LinearLayout {

    public interface OnEasingPicked { void onPicked(EasingType easing); }

    private EasingType easing = EasingType.LINEAR;
    private OnEasingPicked listener;
    private final EasingCurveView chipCurve;
    private final TextView nameView;

    private static final int GRID_COLUMNS = 4;

    public EasingPickerField(Context context) { this(context, null); }

    public EasingPickerField(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        setClickable(true);
        setFocusable(true);

        chipCurve = new EasingCurveView(context);
        chipCurve.setLineWidthDp(1.6f);
        chipCurve.setTint(accent());
        LayoutParams chipParams = new LayoutParams(dp(30), dp(30));
        LinearLayout chipBox = new LinearLayout(context);
        chipBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        chipBox.setBackground(rounded(0x14808080, dp(7), 0, 0));
        chipBox.addView(chipCurve, chipParams);
        addView(chipBox, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        nameView.setTextColor(themeColor(android.R.attr.textColorSecondary));
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams nameParams = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginStart(dp(8));
        addView(nameView, nameParams);

        TextView chevron = new TextView(context);
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setTextColor(themeColor(android.R.attr.textColorSecondary));
        LayoutParams chevronParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chevronParams.setMarginStart(dp(6));
        addView(chevron, chevronParams);

        setOnClickListener(v -> showSheet());
        refresh();
    }

    /** What is picked right now (never null). */
    public EasingType getEasing() { return easing; }

    /** Shows {@code easing} as the pick, without calling the listener. */
    public void setEasing(EasingType easing) {
        this.easing = easing == null ? EasingType.LINEAR : easing;
        refresh();
    }

    /** Called when a tile is tapped in the sheet. */
    public void setOnEasingPicked(OnEasingPicked listener) { this.listener = listener; }

    private void refresh() {
        chipCurve.setEasing(easing);
        nameView.setText(shortName(getContext(), easing));
    }

    /** "EASE_IN_OUT_SINE" -> "In Out Sine"; the two non-curves read "Hold" / "Linear". */
    public static String shortName(Context context, EasingType e) {
        if (e == EasingType.NONE) return context.getString(R.string.easing_hold);
        if (e == EasingType.LINEAR) return context.getString(R.string.easing_linear);
        String[] words = e.name().replace("EASE_", "").split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.charAt(0)).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------ the sheet

    private void showSheet() {
        final Context context = getContext();
        final BottomSheetDialog dialog = new BottomSheetDialog(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(VERTICAL);

        // Title row: "Easing" ... "Done"
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView title = new TextView(context);
        title.setText(R.string.easing);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(themeColor(android.R.attr.textColorPrimary));
        titleRow.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = new TextView(context);
        done.setText(R.string.done);
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        done.setTypeface(done.getTypeface(), Typeface.BOLD);
        done.setTextColor(accent());
        done.setPadding(dp(8), dp(8), dp(8), dp(8));
        done.setOnClickListener(v -> dialog.dismiss());
        titleRow.addView(done);
        root.addView(titleRow);

        ScrollView scroll = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Big live preview of the current pick.
        final EasingCurveView preview = new EasingCurveView(context);
        preview.setEasing(easing);
        preview.setLineWidthDp(3f);
        preview.setTint(themeColor(android.R.attr.textColorPrimary));
        preview.setDotColor(accent());
        preview.setAnimated(true);
        LinearLayout previewBox = new LinearLayout(context);
        previewBox.setGravity(Gravity.CENTER);
        previewBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        previewBox.setBackground(rounded(0x0F808080, dp(12), 0, 0));
        previewBox.addView(preview, new LayoutParams(dp(150), dp(150)));
        content.addView(previewBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView previewName = new TextView(context);
        previewName.setGravity(Gravity.CENTER);
        previewName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        previewName.setTypeface(previewName.getTypeface(), Typeface.BOLD);
        previewName.setTextColor(themeColor(android.R.attr.textColorPrimary));
        previewName.setPadding(0, dp(8), 0, 0);
        content.addView(previewName);

        final TextView holdHint = new TextView(context);
        holdHint.setGravity(Gravity.CENTER);
        holdHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        holdHint.setTextColor(themeColor(android.R.attr.textColorSecondary));
        holdHint.setText(R.string.easing_hold_hint);
        content.addView(holdHint);

        // The grid of tiles.
        final EasingType[] all = EasingType.values();
        final GridLayout grid = new GridLayout(context);
        grid.setColumnCount(GRID_COLUMNS);
        final TileViews[] tiles = new TileViews[all.length];

        final Runnable refreshSelection = () -> {
            preview.setEasing(easing);
            previewName.setText(shortName(context, easing));
            holdHint.setVisibility(easing == EasingType.NONE ? View.VISIBLE : View.GONE);
            for (int i = 0; i < all.length; i++) tiles[i].setSelected(all[i] == easing);
        };

        final int gap = dp(10);
        final int tileWidth = (context.getResources().getDisplayMetrics().widthPixels - dp(32) - gap * (GRID_COLUMNS - 1)) / GRID_COLUMNS;
        for (int i = 0; i < all.length; i++) {
            final EasingType e = all[i];
            TileViews tile = new TileViews(context, e);
            tiles[i] = tile;
            tile.root.setOnClickListener(v -> {
                if (e == easing) return;
                easing = e;
                refresh();
                refreshSelection.run();
                if (listener != null) listener.onPicked(e);
            });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = tileWidth;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.setMargins(0, gap, (i % GRID_COLUMNS) == GRID_COLUMNS - 1 ? 0 : gap, 0);
            grid.addView(tile.root, p);
        }
        content.addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshSelection.run();

        dialog.setContentView(root);
        dialog.setOnShowListener(d -> {
            // Open fully, so the whole grid can be scrolled instead of a half-height sheet.
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> b =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
                b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                b.setSkipCollapsed(true);
            }
        });
        dialog.show();
    }

    /** One grid tile: the curve in a rounded square and its name below. */
    private final class TileViews {
        final LinearLayout root;
        final LinearLayout box;
        final EasingCurveView curve;
        final TextView label;

        TileViews(Context context, EasingType e) {
            root = new LinearLayout(context);
            root.setOrientation(VERTICAL);
            root.setClickable(true);
            root.setFocusable(true);

            box = new LinearLayout(context);
            box.setPadding(dp(8), dp(8), dp(8), dp(8));
            curve = new EasingCurveView(context);
            curve.setEasing(e);
            curve.setLineWidthDp(2f);
            box.addView(curve, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(box, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            label = new TextView(context);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            label.setText(shortName(context, e));
            label.setPadding(0, dp(4), 0, 0);
            root.addView(label, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void setSelected(boolean selected) {
            int accent = accent();
            curve.setTint(selected ? accent : themeColor(android.R.attr.textColorPrimary));
            box.setBackground(selected
                    ? rounded((accent & 0x00FFFFFF) | 0x2E000000, dp(10), accent, dp(2))
                    : rounded(0x12808080, dp(10), 0x20808080, dp(1)));
            label.setTextColor(selected ? themeColor(android.R.attr.textColorPrimary) : themeColor(android.R.attr.textColorSecondary));
            label.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static GradientDrawable rounded(int fill, int radiusPx, int strokeColor, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    private int accent() { return themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF0A84FF); }

    private int themeColor(int attr) { return themeColor(attr, 0xFF808080); }

    private int themeColor(int attr, int fallback) {
        TypedArray a = getContext().obtainStyledAttributes(new int[]{attr});
        try {
            ColorStateList list = a.getColorStateList(0);
            return list == null ? fallback : list.getDefaultColor();
        } finally {
            a.recycle();
        }
    }
}
