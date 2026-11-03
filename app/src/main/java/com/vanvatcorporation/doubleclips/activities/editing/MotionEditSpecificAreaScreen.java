package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.vanvatcorporation.doubleclips.ClipMotionBlur;
import com.vanvatcorporation.doubleclips.ClipOpticalFlow;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.impl.RulerSliderView;

import java.util.Arrays;
import java.util.Locale;

/**
 * The "Motion" panel (toolbar: Motion), the same two blocks as iOS and Desktop:
 * <ul>
 *   <li><b>Optical flow</b>: a switch and the frame rate the clip's in-between frames are made at (typed, any positive
 *       number). Above the project's frame rate the extra frames can't be shown, and the panel says so.</li>
 *   <li><b>Motion blur</b>: a switch, Blur and Blend (0 ... 100, each a ruler with a number box and a keyframe diamond like every
 *       other slider), the streak Direction, and Speed: how many times the blur is applied (typed, 1 or more).</li>
 * </ul>
 * The panel only shows and reports: what a change means (a plain value or a keyframe, undo) is MotionEditController's job.
 * The rows stay while their values change, so a drag or a typed number is never interrupted by the panel refreshing.
 */
public class MotionEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    /** What the panel tells its owner. */
    public interface Listener {
        void onOpticalFlow(boolean on);
        /** The frame rate box lost focus / Done with a usable number. */
        void onFps(float fps);
        void onMotionBlur(boolean on);
        void onDirection(ClipMotionBlur.Direction direction);
        void onPasses(int passes);
        /** A drag or a typed number begins: snapshot for undo. */
        void onValueStart();
        /** A slider moved (apply live, no undo step yet). {@code param}: {@link #BLUR} or {@link #BLEND}. */
        void onValueChanging(int param, float value);
        /** The gesture is over: this is the value. */
        void onValueCommit(int param, float value);
        /** The keyframe diamond of a slider was tapped. */
        void onDiamond(int param);
    }

    public static final int BLUR = 0, BLEND = 1;

    /** Everything the panel shows. */
    public static final class State {
        public boolean flowOn;
        public float fps = ClipOpticalFlow.DEFAULT_FPS;
        /** Optical flow asks for more frames a second than the project plays. */
        public boolean exceedsProject;
        public int projectFps = 30;
        public boolean blurOn;
        public ClipMotionBlur.Direction direction = ClipMotionBlur.Direction.BOTH;
        public int passes = 1;
        public final float[] values = {ClipMotionBlur.DEFAULT_BLUR, ClipMotionBlur.DEFAULT_BLEND};
        public final ClipEditSpecificAreaScreen.KeyMode[] modes = new ClipEditSpecificAreaScreen.KeyMode[2];
        /** False while the playhead is outside the clip (a keyframe can't be placed there). */
        public boolean diamondsEnabled = true;

        public State() {
            Arrays.fill(modes, ClipEditSpecificAreaScreen.KeyMode.OFF);
        }
    }

    private Listener listener;
    private LinearLayout content;
    private boolean suppress;

    // optical flow
    private SwitchMaterial flowSwitch;
    private LinearLayout flowOnBlock;
    private EditText fpsField;
    private TextView exceedsNote, flowNoteOn, flowNoteOff;

    // motion blur
    private SwitchMaterial blurSwitch;
    private LinearLayout blurOnBlock;
    private final ParamRow[] rows = new ParamRow[2];
    private MaterialButtonToggleGroup directionGroup;
    private final MaterialButton[] directionButtons = new MaterialButton[3];
    private EditText passesField;
    private TextView blurNoteOn, blurNoteOff;

    public MotionEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public MotionEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MotionEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void init() {
        super.init();
        content = findViewById(R.id.motionContent);
        build();
    }

    // ---- showing ---------------------------------------------------------------------------------

    public void show(State s) {
        suppress = true;
        try {
            flowSwitch.setChecked(s.flowOn);
            flowOnBlock.setVisibility(s.flowOn ? VISIBLE : GONE);
            flowNoteOff.setVisibility(s.flowOn ? GONE : VISIBLE);
            if (!fpsField.hasFocus()) fpsField.setText(ClipOpticalFlow.format(s.fps));
            exceedsNote.setVisibility(s.flowOn && s.exceedsProject ? VISIBLE : GONE);
            exceedsNote.setText(getContext().getString(R.string.motion_exceeds_project, s.projectFps));

            blurSwitch.setChecked(s.blurOn);
            blurOnBlock.setVisibility(s.blurOn ? VISIBLE : GONE);
            blurNoteOff.setVisibility(s.blurOn ? GONE : VISIBLE);
            for (int p = 0; p < 2; p++) rows[p].set(s.values[p], s.modes[p], s.diamondsEnabled);
            directionGroup.check(directionButtons[s.direction.ordinal()].getId());
            if (!passesField.hasFocus()) passesField.setText(String.valueOf(s.passes));
        } finally {
            suppress = false;
        }
    }

    // ---- building ----------------------------------------------------------------------------------

    private void build() {
        Context context = getContext();

        // ---- Optical flow
        flowSwitch = header(R.string.motion_optical_flow, on -> {
            if (!suppress && listener != null) listener.onOpticalFlow(on);
        });

        flowOnBlock = new LinearLayout(context);
        flowOnBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout fpsRow = row();
        fpsRow.addView(label(R.string.motion_frame_rate), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fpsField = numberBox(true);
        fpsField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitFps();
                fpsField.clearFocus();
            }
            return false;
        });
        fpsField.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) commitFps(); });
        fpsRow.addView(fpsField, new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        flowOnBlock.addView(fpsRow);
        exceedsNote = note(R.string.motion_exceeds_project);
        exceedsNote.setTextColor(0xFFFF9500);   // orange: the same warning colour as iOS
        exceedsNote.setVisibility(GONE);
        flowOnBlock.addView(exceedsNote);
        flowNoteOn = note(R.string.motion_flow_note_on);
        flowOnBlock.addView(flowNoteOn);
        content.addView(flowOnBlock);
        flowNoteOff = note(R.string.motion_flow_note_off);
        content.addView(flowNoteOff);

        View divider = new View(context);
        divider.setBackgroundColor(0x33808080);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(16);
        dividerParams.bottomMargin = dp(8);
        content.addView(divider, dividerParams);

        // ---- Motion blur
        blurSwitch = header(R.string.motion_motion_blur, on -> {
            if (!suppress && listener != null) listener.onMotionBlur(on);
        });

        blurOnBlock = new LinearLayout(context);
        blurOnBlock.setOrientation(LinearLayout.VERTICAL);
        rows[BLUR] = new ParamRow(BLUR, R.string.motion_blur_slider);
        rows[BLEND] = new ParamRow(BLEND, R.string.motion_blend);
        blurOnBlock.addView(rows[BLUR].view);
        blurOnBlock.addView(rows[BLEND].view);

        LinearLayout directionRow = row();
        directionRow.addView(label(R.string.motion_direction), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        directionGroup = new MaterialButtonToggleGroup(context);
        directionGroup.setSingleSelection(true);
        directionGroup.setSelectionRequired(true);
        int[] names = {R.string.motion_both, R.string.motion_forward, R.string.motion_backward};
        for (int i = 0; i < 3; i++) {
            MaterialButton b = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(View.generateViewId());
            b.setText(names[i]);
            b.setTextSize(12f);
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            directionButtons[i] = b;
            directionGroup.addView(b);
        }
        directionGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (suppress || !isChecked || listener == null) return;
            for (int i = 0; i < 3; i++) {
                if (directionButtons[i].getId() == checkedId) listener.onDirection(ClipMotionBlur.Direction.values()[i]);
            }
        });
        directionRow.addView(directionGroup, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        blurOnBlock.addView(directionRow);

        LinearLayout passesRow = row();
        passesRow.addView(label(R.string.motion_speed_times), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        passesField = numberBox(false);
        passesField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitPasses();
                passesField.clearFocus();
            }
            return false;
        });
        passesField.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) commitPasses(); });
        passesRow.addView(passesField, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT));
        blurOnBlock.addView(passesRow);
        blurNoteOn = note(R.string.motion_speed_note);
        blurOnBlock.addView(blurNoteOn);
        content.addView(blurOnBlock);
        blurNoteOff = note(R.string.motion_blur_note_off);
        content.addView(blurNoteOff);
    }

    private interface OnSwitch { void onChanged(boolean on); }

    /** A bold title with its switch at the right, added to the content. */
    private SwitchMaterial header(int titleRes, OnSwitch onSwitch) {
        Context context = getContext();
        LinearLayout h = new LinearLayout(context);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(4), dp(8), dp(4), dp(4));
        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(primaryColor());
        h.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        SwitchMaterial toggle = new SwitchMaterial(context);
        toggle.setOnCheckedChangeListener((b, checked) -> onSwitch.onChanged(checked));
        h.addView(toggle);
        content.addView(h);
        return toggle;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(6), dp(4), dp(6));
        return r;
    }

    private TextView label(int res) {
        TextView t = new TextView(getContext());
        t.setText(res);
        t.setTextSize(14f);
        t.setTextColor(primaryColor());
        return t;
    }

    private TextView note(int res) {
        TextView t = new TextView(getContext());
        t.setText(res);
        t.setTextSize(11f);
        t.setTextColor(secondaryColor());
        t.setPadding(dp(4), dp(4), dp(4), dp(4));
        return t;
    }

    private EditText numberBox(boolean decimal) {
        EditText e = new EditText(getContext());
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setSingleLine(true);
        e.setTextSize(15f);
        e.setTextColor(primaryColor());
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setSelectAllOnFocus(true);
        e.setPadding(dp(8), dp(6), dp(8), dp(6));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(0x1A808080);
        e.setBackground(bg);
        return e;
    }

    private void commitFps() {
        if (suppress || listener == null) return;
        try {
            float typed = Float.parseFloat(fpsField.getText().toString().trim().replace(',', '.'));
            if (!Float.isNaN(typed) && !Float.isInfinite(typed) && typed > 0f) {
                listener.onFps(typed);
                return;
            }
        } catch (NumberFormatException ignored) { /* not a number: the shown rate is put back below */ }
        listener.onFps(Float.NaN);   // not usable: the controller refreshes the box with what is kept
    }

    private void commitPasses() {
        if (suppress || listener == null) return;
        try {
            int typed = Integer.parseInt(passesField.getText().toString().trim());
            if (typed >= 1) {
                listener.onPasses(typed);
                return;
            }
        } catch (NumberFormatException ignored) { /* see commitFps */ }
        listener.onPasses(-1);
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

    // ---- one slider row (Blur / Blend): ruler, number, keyframe diamond -----------------------------

    private final class ParamRow {
        final int param;
        final LinearLayout view;
        final RulerSliderView slider;
        final EditText number;
        final ImageView diamond;

        ParamRow(int param, int labelRes) {
            this.param = param;
            Context context = getContext();
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(context);
            label.setText(labelRes);
            label.setTextSize(14f);
            label.setTextColor(primaryColor());
            view.addView(label, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

            slider = new RulerSliderView(context);
            slider.setRange(0f, ClipMotionBlur.SCALE_MAX);
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
            diamond.setOnClickListener(v -> {
                if (listener != null) listener.onDiamond(ParamRow.this.param);
            });
            view.addView(diamond, new LinearLayout.LayoutParams(dp(36), dp(36)));
            setKeyMode(ClipEditSpecificAreaScreen.KeyMode.OFF, true);
        }

        /** A typed number: clamped into the range, then it is a one-step gesture. */
        private void commitTyped() {
            if (suppress) return;
            float typed;
            try {
                typed = Float.parseFloat(number.getText().toString().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                number.setText(format(slider.getValue()));
                return;
            }
            float clamped = Math.round(Math.max(0f, Math.min(ClipMotionBlur.SCALE_MAX, typed)));   // whole numbers, like iOS
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
}
