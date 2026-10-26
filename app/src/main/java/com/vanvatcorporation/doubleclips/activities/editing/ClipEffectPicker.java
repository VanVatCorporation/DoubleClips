package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.vanvatcorporation.doubleclips.ClipAnimationAssets;
import com.vanvatcorporation.doubleclips.EffectStyle;
import com.vanvatcorporation.doubleclips.EffectStyleCatalog;
import com.vanvatcorporation.doubleclips.EffectStyleLoader;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.StyleTileRenderer;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.impl.TileGridDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The "Effect" section of a clip's editor (view_clip_effect_section.xml, shared by the clip and the text screens): an effect that
 * touches THIS clip only (Clip.clipEffect), as opposed to an effect clip, which filters every layer below it. Same styles, same
 * live tile grid and same strength (with its keyframe diamond) as an effect clip; plus a "None" tile to take the effect off, and a
 * start / duration for when it shows inside the clip.
 *
 * The picker only shows and edits what the user chose. What goes into the clip (the effect itself, the strength and its
 * keyframes) is written by the owner, through the callbacks, because that is where the timeline and the keyframes live.
 */
public final class ClipEffectPicker {

    private static final String NONE = "none";

    private final Context context;
    private final View section;
    private final TextView chooseText;
    private final View timingGroup;
    private final EditText startField, durationField;
    private final View strengthGroup;
    private final TextView strengthText, strengthValue;
    private final SeekBar strengthSeek;
    private ImageView strengthDiamond;

    /** The user picked another style (or "None"): the owner writes it into the clip. */
    public Runnable onStyleChanged;
    /** The strength slider moved (the owner applies it live), and the strength diamond was tapped. */
    public Runnable onStrengthEdited, onStrengthDiamond;

    /** The stored style id (may be one this build doesn't know: it is kept until the user picks another). Empty = none. */
    private String styleId = "";
    private float strength = 1f;
    /** True from picking a new style until the owner has written its default strength. */
    private boolean styleChanged;

    /** @param section the root of view_clip_effect_section.xml */
    public ClipEffectPicker(Context context, View section) {
        this.context = context;
        this.section = section;
        chooseText = section.findViewById(R.id.clipEffectContent);
        timingGroup = section.findViewById(R.id.clipEffectTimingGroup);
        startField = section.findViewById(R.id.clipEffectStartContent);
        durationField = section.findViewById(R.id.clipEffectDurationContent);
        strengthGroup = section.findViewById(R.id.clipEffectStrengthGroup);
        strengthText = section.findViewById(R.id.clipEffectStrengthText);
        strengthValue = section.findViewById(R.id.clipEffectStrengthValue);
        strengthSeek = section.findViewById(R.id.clipEffectStrengthContent);

        ClipAnimationAssets.loadAll(context); // registers the effect styles; no-op after the first call
        installStrengthDiamond();
        chooseText.setOnClickListener(v -> openGrid());
        strengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                EffectStyle style = EffectStyleLoader.get(EffectStyleLoader.canonical(styleId));
                if (style == null || !style.hasIntensity()) return;
                strength = style.getIntensityMin() + (style.getIntensityMax() - style.getIntensityMin()) * progress / 100f;
                showStrength();
                if (onStrengthEdited != null) onStrengthEdited.run();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    public View getSection() { return section; }

    // ---- state ---------------------------------------------------------------------------------

    /**
     * Shows this clip's effect (called when the panel opens). {@code strengthNow} is the strength at the playhead (keyframed or static).
     * The window shows 0 and the clip's length until the user changes them.
     */
    public void show(EditingActivity.Clip clip, float strengthNow) {
        EditingActivity.EffectTemplate effect = clip.clipEffect;
        styleId = effect == null || effect.style == null || NONE.equals(effect.style) ? "" : effect.style;
        strength = strengthNow;
        styleChanged = false;
        double start = effect == null ? 0.0 : Math.max(effect.offset, 0.0);
        double length = effect != null && effect.duration > 0.0 ? effect.duration : Math.max(clip.duration - start, 0.0);
        startField.setText(trim(start));
        durationField.setText(trim(length));
        refresh();
    }

    /** Shows another strength (the keyframed value at the playhead) without touching the style. */
    public void setStrengthValue(float value) {
        strength = value;
        refresh();
    }

    public boolean isStyleChanged() { return styleChanged; }
    public void clearStyleChanged() { styleChanged = false; }

    /** The style id the user has chosen ("" = none). */
    public String getStyleId() { return styleId; }

    /** The strength shown (1 when the style has nothing to scale). */
    public float getStrength() {
        EffectStyle style = styleId.isEmpty() ? null : EffectStyleLoader.get(EffectStyleLoader.canonical(styleId));
        return style != null && style.hasIntensity() ? strength : 1f;
    }

    /**
     * Writes the typed start / duration into the clip's effect (nothing when it has none). A window that reaches the end of the
     * clip is stored as duration 0 = "until the clip's end", so it keeps following the clip when the clip is trimmed or stretched.
     */
    public void applyTimingTo(EditingActivity.Clip clip) {
        EditingActivity.EffectTemplate effect = clip.clipEffect;
        if (effect == null) return;
        double start = parse(startField, 0.0);
        if (start < 0.0 || Double.isNaN(start)) start = 0.0;
        if (start >= clip.duration) start = 0.0; // it would never show: back to the beginning
        double room = clip.duration - start;
        double length = parse(durationField, room);
        effect.offset = start;
        effect.duration = (length <= 0.0 || length >= room - 0.001) ? 0.0 : length;
    }

    private static double parse(EditText field, double fallback) {
        try {
            return Double.parseDouble(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        s = s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        return s.isEmpty() ? "0" : s;
    }

    // ---- keyframe diamond ----------------------------------------------------------------------

    /** A diamond after the strength value (hollow = not animated, half = animated, filled = a key at the playhead). */
    private void installStrengthDiamond() {
        if (!(strengthValue.getParent() instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) strengthValue.getParent();
        strengthDiamond = new ImageView(context);
        strengthDiamond.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(5 * context.getResources().getDisplayMetrics().density);
        strengthDiamond.setPadding(pad, pad, pad, pad);
        strengthDiamond.setContentDescription("Keyframe Strength");
        strengthDiamond.setOnClickListener(v -> { if (onStrengthDiamond != null) onStrengthDiamond.run(); });
        int size = Math.round(32 * context.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(strengthDiamond, row.indexOfChild(strengthValue) + 1, lp);
        setStrengthKeyMode(ClipEditSpecificAreaScreen.KeyMode.OFF, true);
    }

    /** The diamond's state; {@code enabled} is false while the playhead is outside the clip. */
    public void setStrengthKeyMode(ClipEditSpecificAreaScreen.KeyMode mode, boolean enabled) {
        if (strengthDiamond == null) return;
        strengthDiamond.setImageResource(mode == ClipEditSpecificAreaScreen.KeyMode.ON_KEY ? R.drawable.ic_key_on
                : (mode == ClipEditSpecificAreaScreen.KeyMode.ANIMATED ? R.drawable.ic_key_animated : R.drawable.ic_key_off));
        if (mode == ClipEditSpecificAreaScreen.KeyMode.OFF) {
            TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
            strengthDiamond.setImageTintList(a.getColorStateList(0));
            a.recycle();
        } else {
            strengthDiamond.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.keyframe_active)));
        }
        strengthDiamond.setEnabled(enabled);
        strengthDiamond.setAlpha(enabled ? 1f : 0.3f);
    }

    // ---- display -------------------------------------------------------------------------------

    private void refresh() {
        EffectStyle style = styleId.isEmpty() ? null : EffectStyleLoader.get(EffectStyleLoader.canonical(styleId));
        chooseText.setText(styleId.isEmpty() ? "None" : (style != null ? style.getName() : styleId + " (not installed)"));
        timingGroup.setVisibility(styleId.isEmpty() ? View.GONE : View.VISIBLE);
        if (style != null && style.hasIntensity()) {
            // (A stored / keyframed value outside the slider's range only moves the thumb to its end; the value itself is kept.)
            strengthGroup.setVisibility(View.VISIBLE);
            strengthText.setText(style.getIntensityLabel());
            float span = style.getIntensityMax() - style.getIntensityMin();
            float shown = Math.min(Math.max(strength, style.getIntensityMin()), style.getIntensityMax());
            strengthSeek.setProgress(span <= 0f ? 0 : Math.round((shown - style.getIntensityMin()) / span * 100f));
            showStrength();
        } else {
            strengthGroup.setVisibility(View.GONE);
        }
    }

    private void showStrength() {
        strengthValue.setText(String.format(Locale.US, "%.2fx", strength));
    }

    // ---- the grid ------------------------------------------------------------------------------

    private void openGrid() {
        final StyleTileRenderer renderer = StyleTileRenderer.get(context);
        List<TileGridDialog.Tile> tiles = new ArrayList<>();
        tiles.add(new TileGridDialog.Tile(NONE, "None", null, null, () -> ImageHelper.createSolidColorBitmap(192, 192, 0xFF26262B), false));
        for (EffectStyle style : EffectStyleCatalog.ordered(EffectStyleLoader.list())) {
            final String id = style.getId();
            tiles.add(new TileGridDialog.Tile(id, style.getName(), style.getAuthor(), "OpenGL", new TileGridDialog.AsyncPreview() {
                @Override public void staticFrame(Consumer<Bitmap> onReady) {
                    renderer.staticTile(StyleTileRenderer.Kind.EFFECT, id, onReady::accept);
                }

                @Override public void loopFrames(BooleanSupplier wanted, Consumer<List<Bitmap>> onReady) {
                    renderer.loopFrames(StyleTileRenderer.Kind.EFFECT, id, wanted, onReady::accept);
                }
            }, false));
        }
        String selected = styleId.isEmpty() ? NONE : EffectStyleLoader.canonical(styleId);
        TileGridDialog.show(context, "Clip effect", tiles, selected, new TileGridDialog.Listener() {
            @Override public void onPick(TileGridDialog.Tile tile) {
                String picked = NONE.equals(tile.id) ? "" : tile.id;
                if (!picked.equals(styleId.isEmpty() ? "" : EffectStyleLoader.canonical(styleId))) {
                    styleId = picked;
                    strength = 1f; // a new style starts at its own look
                    styleChanged = true;
                    refresh();
                    if (onStyleChanged != null) onStyleChanged.run();
                } else {
                    refresh();
                }
            }

            @Override public void onDelete(TileGridDialog.Tile tile) { }
        }, null, null);
    }
}
