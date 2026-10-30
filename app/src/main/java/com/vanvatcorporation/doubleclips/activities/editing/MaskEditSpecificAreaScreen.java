package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.vanvatcorporation.doubleclips.CanvasMaskPainter;
import com.vanvatcorporation.doubleclips.ClipMask;
import com.vanvatcorporation.doubleclips.ClipMaskGeometry;
import com.vanvatcorporation.doubleclips.ClipMaskResolver;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.impl.RulerSliderView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The clip mask's panel (iOS design): a row of shape tiles (None, Linear, Mirror, Circle, Rectangle, Heart, Star), tabs
 * (Position, Size, Rotate, Feather, Corner), value rows (label, ruler slider, number box, keyframe diamond) for the open
 * tab, then Reset and an Invert switch.
 * <p>
 * The panel only shows and reports: what a change means (static value or keyframe, undo) is MaskEditController's job.
 * Values are in the panel's units (x 50 = centre of the box), see ClipMaskResolver.
 */
public class MaskEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    /** What the panel tells its owner. */
    public interface Listener {
        /** A shape tile was tapped; null = None (removes the mask). */
        void onShape(String shapeOrNull);

        /** A drag or a typed number begins: snapshot for undo. */
        void onValueStart();

        /** A value moved (apply live, no undo step yet). */
        void onValueChanging(String param, float panelValue);

        /** The gesture is over: this is the value. */
        void onValueCommit(String param, float panelValue);

        /** The keyframe diamond of a value was tapped. */
        void onDiamond(String param);

        void onReset();

        void onInvert(boolean invert);

        /** A mask chip was tapped (0 = the first). */
        void onSelectMask(int index);

        /** "+ Add mask". */
        void onAddMask();

        /** The open mask's combine mode was chosen: {@link ClipMask#ADD}, {@link ClipMask#SUBTRACT} or {@link ClipMask#INTERSECT}. */
        void onMode(String mode);
    }

    private static final String[] PARAMS = {
            ClipMaskResolver.X, ClipMaskResolver.Y, ClipMaskResolver.WIDTH, ClipMaskResolver.HEIGHT,
            ClipMaskResolver.ROTATION, ClipMaskResolver.FEATHER, ClipMaskResolver.ROUNDNESS};

    private enum Tab {
        POSITION("Position"), SIZE("Size"), ROTATE("Rotate"), FEATHER("Feather"), CORNER("Corner");
        final String title;
        Tab(String title) { this.title = title; }
    }

    private Listener listener;
    private LinearLayout shapeRow, tabRow, valueRows, maskChipRow, maskModeRow;
    private View maskChipScroll;
    private View bottomRow;
    private SwitchMaterial invertSwitch;
    private final Map<String, ParamRow> rows = new LinkedHashMap<>();
    private final Map<String, ShapeTile> tiles = new LinkedHashMap<>();
    private final Map<Tab, TextView> tabViews = new LinkedHashMap<>();

    /** The mask's shape, null = no mask. */
    private String shape;
    private Tab tab = Tab.POSITION;
    private boolean suppress;

    public MaskEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public MaskEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaskEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MaskEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init() {
        super.init();
        shapeRow = findViewById(R.id.maskShapeRow);
        tabRow = findViewById(R.id.maskTabRow);
        valueRows = findViewById(R.id.maskValueRows);
        bottomRow = findViewById(R.id.maskBottomRow);
        invertSwitch = findViewById(R.id.maskInvertSwitch);
        maskChipScroll = findViewById(R.id.maskChipScroll);
        maskChipRow = findViewById(R.id.maskChipRow);
        maskModeRow = findViewById(R.id.maskModeRow);

        buildShapeTiles();
        buildTabs();
        buildRows();

        findViewById(R.id.maskResetButton).setOnClickListener(v -> {
            if (listener != null) listener.onReset();
        });
        invertSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!suppress && listener != null) listener.onInvert(checked);
        });
        refreshVisibility();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ---- showing ---------------------------------------------------------------------------------

    /**
     * Shows the clip's list of masks: a chip for each (number, shape, and how it combines from the second on), "+ Add mask", and
     * for the second mask on the Add / Subtract / Intersect choice of the open one. Nothing is shown for a clip without masks.
     *
     * @param count    how many masks the clip has
     * @param selected the open one (0 = the first)
     * @param mode     the open mask's mode
     * @param shapes   each mask's shape, null when there are none
     */
    public void showMasks(int count, int selected, String mode, String[] shapes) {
        maskChipRow.removeAllViews();
        maskModeRow.removeAllViews();
        maskChipScroll.setVisibility(count > 0 ? VISIBLE : GONE);
        maskModeRow.setVisibility(count > 0 && selected > 0 ? VISIBLE : GONE);
        if (count <= 0 || shapes == null) return;
        for (int i = 0; i < count && i < shapes.length; i++) {
            final int index = i;
            String shape = shapes[i] == null || shapes[i].isEmpty() ? ClipMask.CIRCLE : shapes[i];
            StringBuilder label = new StringBuilder().append(i + 1).append("  ")
                    .append(Character.toUpperCase(shape.charAt(0))).append(shape.substring(1));
            addChip(maskChipRow, label.toString(), i == selected, () -> {
                if (listener != null) listener.onSelectMask(index);
            });
        }
        if (count < ClipMask.MAX_MASKS) {
            addChip(maskChipRow, "+ Add mask", false, () -> {
                if (listener != null) listener.onAddMask();
            });
        }
        if (selected > 0) {
            String[][] modes = {{ClipMask.ADD, "Add"}, {ClipMask.SUBTRACT, "Subtract"}, {ClipMask.INTERSECT, "Intersect"}};
            for (String[] def : modes) {
                final String id = def[0];
                addChip(maskModeRow, def[1], id.equals(mode), () -> {
                    if (listener != null) listener.onMode(id);
                });
            }
        }
    }

    private void addChip(LinearLayout row, String text, boolean selected, Runnable onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setTextSize(13f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        int accent = getContext().getColor(R.color.ios_blue);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(selected ? (accent & 0x00FFFFFF) | 0x33000000 : 0x14808080);
        bg.setStroke(dp(1.5f), selected ? accent : 0x00000000);
        chip.setBackground(bg);
        chip.setTextColor(selected ? accent : secondaryColor());
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(8));
        row.addView(chip, params);
    }

    /**
     * Shows a mask.
     *
     * @param shapeOrNull     the shape, null = no mask (only the shape tiles are shown then)
     * @param values          the seven values in panel units, in the order x, y, width, height, rotation, feather, roundness
     * @param modes           each value's diamond (same order)
     * @param diamondsEnabled false while the playhead is outside the clip (no keyframe can be placed there)
     */
    public void show(String shapeOrNull, boolean invert, float[] values,
                     ClipEditSpecificAreaScreen.KeyMode[] modes, boolean diamondsEnabled) {
        shape = shapeOrNull;
        suppress = true;
        invertSwitch.setChecked(invert);
        if (shape != null) {
            for (int i = 0; i < PARAMS.length; i++) {
                rows.get(PARAMS[i]).set(values[i], modes[i], diamondsEnabled);
            }
        }
        suppress = false;
        for (Map.Entry<String, ShapeTile> e : tiles.entrySet()) {
            e.getValue().setSelected(shape == null ? e.getKey().isEmpty() : e.getKey().equals(shape));
        }
        refreshVisibility();
    }

    // ---- tabs and rows ---------------------------------------------------------------------------

    private List<Tab> tabsForShape() {
        List<Tab> list = new ArrayList<>();
        list.add(Tab.POSITION);
        if (shape != null && !shape.equals(ClipMask.LINEAR)) list.add(Tab.SIZE); // a half plane has no size
        list.add(Tab.ROTATE);
        list.add(Tab.FEATHER);
        if (ClipMask.RECTANGLE.equals(shape)) list.add(Tab.CORNER);
        return list;
    }

    private void refreshVisibility() {
        boolean has = shape != null;
        tabRow.setVisibility(has ? VISIBLE : GONE);
        valueRows.setVisibility(has ? VISIBLE : GONE);
        bottomRow.setVisibility(has ? VISIBLE : GONE);
        if (!has) return;

        List<Tab> available = tabsForShape();
        if (!available.contains(tab)) tab = Tab.POSITION;
        for (Map.Entry<Tab, TextView> e : tabViews.entrySet()) {
            boolean shown = available.contains(e.getKey());
            e.getValue().setVisibility(shown ? VISIBLE : GONE);
            styleTab(e.getValue(), e.getKey() == tab);
        }
        boolean mirror = ClipMask.MIRROR.equals(shape);
        for (String param : PARAMS) {
            boolean visible;
            switch (tab) {
                case POSITION: visible = param.equals(ClipMaskResolver.X) || param.equals(ClipMaskResolver.Y); break;
                case SIZE: visible = mirror ? param.equals(ClipMaskResolver.HEIGHT)
                        : (param.equals(ClipMaskResolver.WIDTH) || param.equals(ClipMaskResolver.HEIGHT)); break;
                case ROTATE: visible = param.equals(ClipMaskResolver.ROTATION); break;
                case FEATHER: visible = param.equals(ClipMaskResolver.FEATHER); break;
                default: visible = param.equals(ClipMaskResolver.ROUNDNESS); break;
            }
            ParamRow row = rows.get(param);
            row.view.setVisibility(visible ? VISIBLE : GONE);
            if (param.equals(ClipMaskResolver.HEIGHT)) row.label.setText(mirror ? "Thickness" : "Height");
        }
    }

    private void styleTab(TextView view, boolean selected) {
        view.setTextColor(selected ? getContext().getColor(R.color.ios_blue) : secondaryColor());
        view.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private int secondaryColor() {
        TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
        int c = a.getColor(0, 0xFF888888);
        a.recycle();
        return c;
    }

    private void buildShapeTiles() {
        String[][] defs = {
                {"", "None"}, {ClipMask.LINEAR, "Linear"}, {ClipMask.MIRROR, "Mirror"}, {ClipMask.CIRCLE, "Circle"},
                {ClipMask.RECTANGLE, "Rectangle"}, {ClipMask.HEART, "Heart"}, {ClipMask.STAR, "Star"}};
        for (String[] def : defs) {
            final String key = def[0];
            ShapeTile tile = new ShapeTile(getContext(), key.isEmpty() ? null : key, def[1]);
            tile.setOnClickListener(v -> {
                if (listener != null) listener.onShape(key.isEmpty() ? null : key);
            });
            tiles.put(key, tile);
            shapeRow.addView(tile, new LinearLayout.LayoutParams(dp(64), dp(72)));
        }
    }

    private void buildTabs() {
        for (final Tab t : Tab.values()) {
            TextView view = new TextView(getContext());
            view.setText(t.title);
            view.setTextSize(14f);
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(12), dp(8), dp(12), dp(8));
            view.setOnClickListener(v -> {
                tab = t;
                refreshVisibility();
            });
            tabViews.put(t, view);
            tabRow.addView(view);
        }
    }

    private void buildRows() {
        String[] labels = {"X", "Y", "Width", "Height", "Rotate", "Feather", "Corner"};
        for (int i = 0; i < PARAMS.length; i++) {
            ParamRow row = new ParamRow(PARAMS[i], labels[i]);
            rows.put(PARAMS[i], row);
            valueRows.addView(row.view);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String format(float v) {
        return Math.abs(v - Math.round(v)) < 0.05f ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v);
    }

    /** label, ruler slider, number box, diamond. */
    private final class ParamRow {
        final String param;
        final LinearLayout view;
        final TextView label;
        final RulerSliderView slider;
        final EditText number;
        final ImageView diamond;

        ParamRow(String param, String labelText) {
            this.param = param;
            Context context = getContext();
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);

            label = new TextView(context);
            label.setText(labelText);
            label.setTextSize(15f);
            label.setTextColor(primaryColor());
            view.addView(label, new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));

            slider = new RulerSliderView(context);
            slider.setRange(ClipMaskResolver.panelMin(param), ClipMaskResolver.panelMax(param));
            slider.setListener(new RulerSliderView.Listener() {
                @Override public void onStart() {
                    if (listener != null) listener.onValueStart();
                }

                @Override public void onChanging(float value) {
                    number.setText(format(value));
                    if (listener != null) listener.onValueChanging(ParamRow.this.param, value);
                }

                @Override public void onCommit(float value) {
                    if (listener != null) listener.onValueCommit(ParamRow.this.param, value);
                }
            });
            view.addView(slider, new LinearLayout.LayoutParams(0, dp(44), 1f));

            number = new EditText(context);
            number.setBackground(null);
            number.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            number.setSingleLine(true);
            number.setTextSize(15f);
            number.setTextColor(secondaryColor());
            number.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
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
            view.addView(number, new LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT));

            diamond = new ImageView(context);
            diamond.setScaleType(ImageView.ScaleType.FIT_CENTER);
            diamond.setPadding(dp(5), dp(5), dp(5), dp(5));
            diamond.setContentDescription("Keyframe " + labelText);
            diamond.setOnClickListener(v -> {
                if (listener != null) listener.onDiamond(ParamRow.this.param);
            });
            view.addView(diamond, new LinearLayout.LayoutParams(dp(36), dp(36)));
            setKeyMode(ClipEditSpecificAreaScreen.KeyMode.OFF, true);
        }

        private int primaryColor() {
            TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
            int c = a.getColor(0, 0xFFFFFFFF);
            a.recycle();
            return c;
        }

        /** A typed number: clamped into the range, then it is a one-step gesture. */
        private void commitTyped() {
            if (suppress) return;
            String text = number.getText().toString().trim();
            float typed;
            try {
                typed = Float.parseFloat(text);
            } catch (NumberFormatException e) {
                number.setText(format(slider.getValue()));
                return;
            }
            float clamped = Math.max(ClipMaskResolver.panelMin(param), Math.min(ClipMaskResolver.panelMax(param), typed));
            if (Math.abs(clamped - slider.getValue()) < 0.0005f) {
                number.setText(format(clamped));
                return;
            }
            slider.setValue(clamped);
            number.setText(format(clamped));
            if (listener != null) {
                listener.onValueStart();
                listener.onValueCommit(param, clamped);
            }
        }

        void set(float value, ClipEditSpecificAreaScreen.KeyMode mode, boolean enabled) {
            slider.setValue(value);
            if (!number.hasFocus()) number.setText(format(value));
            setKeyMode(mode, enabled);
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

    /** One shape tile: the shape drawn small, its name under it. */
    private static final class ShapeTile extends View {
        private final String shapeOrNull;
        private final String name;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float density;
        private final int primary, accent;
        private boolean selected;

        ShapeTile(Context context, String shapeOrNull, String name) {
            super(context);
            this.shapeOrNull = shapeOrNull;
            this.name = name;
            density = context.getResources().getDisplayMetrics().density;
            TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
            primary = a.getColor(0, 0xFFFFFFFF);
            a.recycle();
            accent = context.getColor(R.color.ios_blue);
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f * density);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(11f * density);
            setContentDescription(name);
        }

        @Override
        public void setSelected(boolean selected) {
            if (this.selected == selected) return;
            this.selected = selected;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (selected) {
                box.set(2 * density, 2 * density, w - 2 * density, h - 2 * density);
                fill.setColor(accent);
                fill.setAlpha(40);
                canvas.drawRoundRect(box, 10 * density, 10 * density, fill);
                stroke.setColor(accent);
                canvas.drawRoundRect(box, 10 * density, 10 * density, stroke);
            }
            int color = selected ? accent : primary;
            float g = 32 * density;
            float gx = (w - g) / 2f, gy = 10 * density;

            canvas.save();
            canvas.translate(gx, gy);
            canvas.clipRect(0, 0, g, g);
            fill.setColor(color);
            fill.setAlpha(210);
            if (shapeOrNull == null) {
                stroke.setColor(color);
                canvas.drawCircle(g / 2f, g / 2f, g * 0.38f, stroke); // "no mask": a crossed-out circle
                canvas.drawLine(g * 0.22f, g * 0.78f, g * 0.78f, g * 0.22f, stroke);
            } else {
                ClipMaskResolver.State state = ClipMaskResolver.resolve(ClipMask.of(shapeOrNull), null, 0f, (n, t) -> t);
                ClipMaskGeometry.draw(state, g, g, new CanvasMaskPainter(canvas, fill));
            }
            canvas.restore();

            text.setColor(color);
            canvas.drawText(name, w / 2f, h - 12 * density, text);
        }
    }
}
