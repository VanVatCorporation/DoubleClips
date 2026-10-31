package com.vanvatcorporation.doubleclips.activities.editing;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.vanvatcorporation.doubleclips.ChromaKey;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.impl.RulerSliderView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The "Background" panel (toolbar: Background), in the style of the Mask panel: a Remove background row (a stub for now, like
 * desktop), then a card per chroma key (see ChromaKey, up to 6) with an on/off switch, the colour (a swatch that opens a
 * picker, and an eyedropper that takes it from the preview), and four 0 ... 100 sliders (Intensity, Shadow, Feather edge,
 * Clean up edge), each a CapCut-style ruler with a number box and a keyframe diamond. "Add chroma key" at the end.
 * <p>
 * The panel only shows and reports: what a change means (static value or keyframe, undo) is ChromaEditController's job.
 * Cards stay while their values change, so a drag or a typed number is never interrupted by the panel refreshing.
 */
public class ChromaEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    /** What the panel tells its owner; {@code key} is the card's place in the clip's list. */
    public interface Listener {
        void onAddKey();
        void onRemoveKey(int key);
        void onEnabled(int key, boolean enabled);
        /** A colour was chosen in the picker (opaque 0xFFRRGGBB). */
        void onColor(int key, int argb);
        /** The eyedropper of a card was tapped: start (or stop) taking the colour from the preview. */
        void onEyedropper(int key);
        /** A drag or a typed number begins: snapshot for undo. */
        void onValueStart();
        /** A slider moved (apply live, no undo step yet). */
        void onValueChanging(int key, int param, float value);
        /** The gesture is over: this is the value. */
        void onValueCommit(int key, int param, float value);
        /** The keyframe diamond of a slider was tapped. */
        void onDiamond(int key, int param);
    }

    /** What one key card shows. */
    public static final class KeyState {
        public boolean enabled = true;
        public int argb = 0xFF00FF00;
        public final float[] values = new float[ChromaKey.PARAM_COUNT];
        public final ClipEditSpecificAreaScreen.KeyMode[] modes = new ClipEditSpecificAreaScreen.KeyMode[ChromaKey.PARAM_COUNT];

        public KeyState() {
            Arrays.fill(modes, ClipEditSpecificAreaScreen.KeyMode.OFF);
        }
    }

    private static final String[] PARAM_LABELS = {"Intensity", "Shadow", "Feather edge", "Clean up edge"};
    private static final int[] PRESETS = {0xFF00FF00, 0xFF00B140, 0xFF0000FF, 0xFF0047BB, 0xFFFFFFFF, 0xFF000000};

    private Listener listener;
    private LinearLayout content;
    private TextView pickHint;
    private TextView addButton;
    private final List<KeyCard> cards = new ArrayList<>();
    private boolean suppress;

    public ChromaEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public ChromaEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ChromaEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void init() {
        super.init();
        content = findViewById(R.id.chromaContent);
        buildStaticParts();
    }

    // ---- showing ---------------------------------------------------------------------------------

    /**
     * Shows the clip's keys.
     *
     * @param states         one per key, in the clip's order
     * @param diamondsEnabled false while the playhead is outside the clip (a keyframe can't be placed there)
     * @param pickingKey     the card whose eyedropper is waiting for a tap on the preview, or -1
     */
    public void show(List<KeyState> states, boolean diamondsEnabled, int pickingKey) {
        suppress = true;
        try {
            if (cards.size() != states.size()) rebuildCards(states.size());
            for (int i = 0; i < states.size(); i++) cards.get(i).set(states.get(i), diamondsEnabled, i == pickingKey);
            addButton.setVisibility(states.size() < ChromaKey.MAX_KEYS ? VISIBLE : GONE);
            pickHint.setVisibility(pickingKey >= 0 ? VISIBLE : GONE);
        } finally {
            suppress = false;
        }
    }

    // ---- building ----------------------------------------------------------------------------------

    private void buildStaticParts() {
        Context context = getContext();

        // Remove background: a stub (the switch does nothing yet), like desktop's.
        LinearLayout removeRow = new LinearLayout(context);
        removeRow.setOrientation(LinearLayout.HORIZONTAL);
        removeRow.setGravity(Gravity.CENTER_VERTICAL);
        removeRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.remove_background_2);
        title.setTextSize(16f);
        title.setTextColor(primaryColor());
        texts.addView(title);
        TextView soon = new TextView(context);
        soon.setText(R.string.coming_soon_2);
        soon.setTextSize(12f);
        soon.setTextColor(secondaryColor());
        texts.addView(soon);
        removeRow.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        SwitchMaterial removeSwitch = new SwitchMaterial(context);
        removeSwitch.setEnabled(false);
        removeRow.addView(removeSwitch);
        removeRow.setAlpha(0.6f);
        content.addView(removeRow);

        TextView chromaTitle = new TextView(context);
        chromaTitle.setText(R.string.chroma_key);
        chromaTitle.setTextSize(13f);
        chromaTitle.setTextColor(secondaryColor());
        chromaTitle.setPadding(dp(4), dp(14), dp(4), dp(6));
        content.addView(chromaTitle);

        pickHint = new TextView(context);
        pickHint.setText(R.string.tap_the_preview_to_pick_the_colour);
        pickHint.setTextSize(14f);
        pickHint.setTextColor(getContext().getColor(R.color.ios_blue));
        pickHint.setPadding(dp(4), dp(2), dp(4), dp(8));
        pickHint.setVisibility(GONE);
        content.addView(pickHint);

        addButton = new TextView(context);
        addButton.setText(R.string.add_chroma_key);
        addButton.setTextSize(15f);
        addButton.setGravity(Gravity.CENTER);
        addButton.setTextColor(getContext().getColor(R.color.ios_blue));
        addButton.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x14808080);
        addButton.setBackground(bg);
        addButton.setOnClickListener(v -> {
            if (listener != null) listener.onAddKey();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        content.addView(addButton, params);
    }

    /** Throws the cards away and makes {@code count} new ones (the chroma key list got longer or shorter). */
    private void rebuildCards(int count) {
        for (KeyCard card : cards) content.removeView(card.view);
        cards.clear();
        int insertAt = content.indexOfChild(pickHint) + 1;
        for (int i = 0; i < count; i++) {
            KeyCard card = new KeyCard(i);
            cards.add(card);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            content.addView(card.view, insertAt + i, params);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int primaryColor() {
        TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        int c = a.getColor(0, 0xFFFFFFFF);
        a.recycle();
        return c;
    }

    private int secondaryColor() {
        TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
        int c = a.getColor(0, 0xFF888888);
        a.recycle();
        return c;
    }

    private static String format(float v) {
        return Math.abs(v - Math.round(v)) < 0.05f ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v);
    }

    // ---- one key ------------------------------------------------------------------------------------

    private final class KeyCard {
        final int key;
        final LinearLayout view;
        final SwitchMaterial enabledSwitch;
        final View swatch;
        final ImageView eyedropper;
        final ParamRow[] rows = new ParamRow[ChromaKey.PARAM_COUNT];
        int argb = 0xFF00FF00;

        KeyCard(int key) {
            this.key = key;
            Context context = getContext();
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(12), dp(8), dp(8), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(0x14808080);
            view.setBackground(bg);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            enabledSwitch = new SwitchMaterial(context);
            enabledSwitch.setText("Key " + (key + 1) + "  ");
            enabledSwitch.setTextColor(primaryColor());
            enabledSwitch.setTextSize(16f);
            enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
                if (!suppress && listener != null) listener.onEnabled(KeyCard.this.key, checked);
            });
            header.addView(enabledSwitch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            swatch = new View(context);
            swatch.setOnClickListener(v -> showColorDialog(KeyCard.this));
            swatch.setContentDescription("Key colour");
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(30), dp(30));
            swatchParams.setMarginEnd(dp(6));
            header.addView(swatch, swatchParams);

            eyedropper = new ImageView(context);
            eyedropper.setImageResource(R.drawable.baseline_colorize_24);
            eyedropper.setPadding(dp(7), dp(7), dp(7), dp(7));
            eyedropper.setContentDescription("Pick the colour from the preview");
            eyedropper.setOnClickListener(v -> {
                if (listener != null) listener.onEyedropper(KeyCard.this.key);
            });
            header.addView(eyedropper, new LinearLayout.LayoutParams(dp(40), dp(40)));

            ImageView delete = new ImageView(context);
            delete.setImageResource(R.drawable.baseline_delete_24);
            delete.setImageTintList(ColorStateList.valueOf(secondaryColor()));
            delete.setPadding(dp(8), dp(8), dp(8), dp(8));
            delete.setContentDescription("Remove key");
            delete.setOnClickListener(v -> {
                if (listener != null) listener.onRemoveKey(KeyCard.this.key);
            });
            header.addView(delete, new LinearLayout.LayoutParams(dp(40), dp(40)));
            view.addView(header);

            for (int p = 0; p < ChromaKey.PARAM_COUNT; p++) {
                rows[p] = new ParamRow(key, p);
                view.addView(rows[p].view);
            }
        }

        void set(KeyState state, boolean diamondsEnabled, boolean picking) {
            enabledSwitch.setChecked(state.enabled);
            argb = state.argb;
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(state.argb);
            dot.setStroke(dp(2), picking ? getContext().getColor(R.color.ios_blue) : 0x66808080);
            swatch.setBackground(dot);
            eyedropper.setImageTintList(ColorStateList.valueOf(picking ? getContext().getColor(R.color.ios_blue) : secondaryColor()));
            view.setAlpha(state.enabled ? 1f : 0.55f);
            for (int p = 0; p < ChromaKey.PARAM_COUNT; p++) rows[p].set(state.values[p], state.modes[p], diamondsEnabled);
        }
    }

    /** label, ruler slider, number box, diamond. */
    private final class ParamRow {
        final int key, param;
        final LinearLayout view;
        final RulerSliderView slider;
        final EditText number;
        final ImageView diamond;

        ParamRow(int key, int param) {
            this.key = key;
            this.param = param;
            Context context = getContext();
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(context);
            label.setText(PARAM_LABELS[param]);
            label.setTextSize(14f);
            label.setTextColor(primaryColor());
            view.addView(label, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));

            slider = new RulerSliderView(context);
            slider.setRange(0f, ChromaKey.SCALE_MAX);
            slider.setListener(new RulerSliderView.Listener() {
                @Override public void onStart() {
                    if (listener != null) listener.onValueStart();
                }

                @Override public void onChanging(float value) {
                    number.setText(format(value));
                    if (listener != null) listener.onValueChanging(ParamRow.this.key, ParamRow.this.param, value);
                }

                @Override public void onCommit(float value) {
                    if (listener != null) listener.onValueCommit(ParamRow.this.key, ParamRow.this.param, value);
                }
            });
            view.addView(slider, new LinearLayout.LayoutParams(0, dp(44), 1f));

            number = new EditText(context);
            number.setBackground(null);
            number.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            number.setSingleLine(true);
            number.setTextSize(15f);
            number.setTextColor(secondaryColor());
            number.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            number.setImeOptions(EditorInfo.IME_ACTION_DONE);
            number.setSelectAllOnFocus(true);
            number.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitTyped();
                    number.clearFocus();
                }
                return false;
            });
            number.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) commitTyped();
            });
            view.addView(number, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));

            diamond = new ImageView(context);
            diamond.setScaleType(ImageView.ScaleType.FIT_CENTER);
            diamond.setPadding(dp(5), dp(5), dp(5), dp(5));
            diamond.setContentDescription("Keyframe " + PARAM_LABELS[param]);
            diamond.setOnClickListener(v -> {
                if (listener != null) listener.onDiamond(ParamRow.this.key, ParamRow.this.param);
            });
            view.addView(diamond, new LinearLayout.LayoutParams(dp(36), dp(36)));
            setKeyMode(ClipEditSpecificAreaScreen.KeyMode.OFF, true);
        }

        /** A typed number: clamped into the range, then it is a one-step gesture. */
        private void commitTyped() {
            if (suppress) return;
            float typed;
            try {
                typed = Float.parseFloat(number.getText().toString().trim());
            } catch (NumberFormatException e) {
                number.setText(format(slider.getValue()));
                return;
            }
            float clamped = Math.max(0f, Math.min(ChromaKey.SCALE_MAX, typed));
            if (Math.abs(clamped - slider.getValue()) < 0.0005f) {
                number.setText(format(clamped));
                return;
            }
            slider.setValue(clamped);
            number.setText(format(clamped));
            if (listener != null) {
                listener.onValueStart();
                listener.onValueCommit(key, param, clamped);
            }
        }

        void set(float value, ClipEditSpecificAreaScreen.KeyMode mode, boolean diamondEnabled) {
            slider.setValue(value);
            if (!number.hasFocus()) number.setText(format(value));
            setKeyMode(mode, diamondEnabled);
        }

        void setKeyMode(ClipEditSpecificAreaScreen.KeyMode mode, boolean enabled) {
            diamond.setImageResource(mode == ClipEditSpecificAreaScreen.KeyMode.ON_KEY ? R.drawable.ic_key_on
                    : (mode == ClipEditSpecificAreaScreen.KeyMode.ANIMATED ? R.drawable.ic_key_animated : R.drawable.ic_key_off));
            if (mode == ClipEditSpecificAreaScreen.KeyMode.OFF) {
                diamond.setImageTintList(ColorStateList.valueOf(secondaryColor()));
            } else {
                diamond.setImageTintList(ColorStateList.valueOf(getContext().getColor(R.color.keyframe_active)));
            }
            diamond.setEnabled(enabled);
            diamond.setAlpha(enabled ? 1f : 0.3f);
        }
    }

    // ---- the colour picker ------------------------------------------------------------------------------

    /** A small picker: preset swatches, hue / saturation / brightness, and the hex text. */
    private void showColorDialog(KeyCard card) {
        Context context = getContext();
        final int[] color = {card.argb};
        final float[] hsv = new float[3];
        Color.colorToHSV(color[0], hsv);
        final boolean[] updating = {false};

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(4));

        final View preview = new View(context);
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout presets = new LinearLayout(context);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, dp(10), 0, dp(6));
        root.addView(presets);

        final EditText hex = new EditText(context);
        hex.setSingleLine(true);
        hex.setHint("#RRGGBB");
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        final SeekBar hue = new SeekBar(context), sat = new SeekBar(context), val = new SeekBar(context);
        hue.setMax(360);
        sat.setMax(100);
        val.setMax(100);

        final Runnable refresh = () -> {
            updating[0] = true;
            color[0] = Color.HSVToColor(hsv);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(color[0]);
            preview.setBackground(bg);
            hue.setProgress(Math.round(hsv[0]));
            sat.setProgress(Math.round(hsv[1] * 100f));
            val.setProgress(Math.round(hsv[2] * 100f));
            String text = ChromaKey.toHex(color[0]);
            if (!hex.getText().toString().equalsIgnoreCase(text)) hex.setText(text);
            updating[0] = false;
        };

        for (final int preset : PRESETS) {
            View dot = new View(context);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(preset);
            d.setStroke(dp(1), 0x66808080);
            dot.setBackground(d);
            dot.setOnClickListener(v -> {
                Color.colorToHSV(preset, hsv);
                refresh.run();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(34), dp(34));
            p.setMarginEnd(dp(10));
            presets.addView(dot, p);
        }

        SeekBar.OnSeekBarChangeListener seek = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || updating[0]) return;
                hsv[0] = hue.getProgress();
                hsv[1] = sat.getProgress() / 100f;
                hsv[2] = val.getProgress() / 100f;
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
        String[] names = {"Hue", "Saturation", "Brightness"};
        SeekBar[] bars = {hue, sat, val};
        for (int i = 0; i < 3; i++) {
            TextView name = new TextView(context);
            name.setText(names[i]);
            name.setTextSize(12f);
            name.setTextColor(secondaryColor());
            root.addView(name);
            bars[i].setOnSeekBarChangeListener(seek);
            root.addView(bars[i]);
        }
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                String text = s.toString().trim();
                if (!ChromaKey.isValidHex(text)) return;
                Color.colorToHSV(Color.parseColor(text.startsWith("#") ? text.substring(0, 7) : "#" + text.substring(0, 6)), hsv);
                updating[0] = true;
                color[0] = Color.HSVToColor(hsv);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(10));
                bg.setColor(color[0]);
                preview.setBackground(bg);
                hue.setProgress(Math.round(hsv[0]));
                sat.setProgress(Math.round(hsv[1] * 100f));
                val.setProgress(Math.round(hsv[2] * 100f));
                updating[0] = false;
            }
        });
        root.addView(hex);
        refresh.run();

        new AlertDialog.Builder(context)
                .setTitle(R.string.key_colour)
                .setView(root)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    int chosen = 0xFF000000 | (color[0] & 0xFFFFFF);
                    if (listener != null) listener.onColor(card.key, chosen);
                })
                .show();
    }
}
