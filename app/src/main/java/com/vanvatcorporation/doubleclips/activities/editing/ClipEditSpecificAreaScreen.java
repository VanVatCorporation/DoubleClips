package com.vanvatcorporation.doubleclips.activities.editing;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import com.google.android.material.switchmaterial.SwitchMaterial;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import com.vanvatcorporation.doubleclips.ClipAnimation;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.impl.NavigationIconLayout;
import com.warkiz.widget.IndicatorSeekBar;

import java.util.EnumMap;
import java.util.Locale;

public class ClipEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    public TextView durationText, totalDurationText;
    public EditText clipNameField, startTrimField, endTrimField, positionXField, positionYField, rotationField, scaleXField, scaleYField, pivotXField, pivotYField, hueField, volumeField, additionFFmpegCommandField, inAnimationDurationField, outAnimationDurationField;
    public IndicatorSeekBar opacitySeekbar, speedSeekbar, saturationSeekbar, brightnessSeekbar, temperatureSeekbar;
    public SwitchMaterial muteAudioCheckbox, lockMediaForTemplateCheckbox, reverseCheckbox, removeBackgroundCheckbox;
    public android.widget.ProgressBar removeBackgroundProgress;
    public LinearLayout keyframeScrollFrame;
    public Button clearKeyframeButton;
    public ArrayAdapter<EditingActivity.EasingType> easingTypeArrayAdapter;
    public Spinner easingSpinner, inAnimationTypeSpinner, outAnimationTypeSpinner;
    public AnimationPicker inAnimationPicker, outAnimationPicker;
    /** The "Effect" section: an effect for this clip only (Clip.clipEffect). */
    public ClipEffectPicker clipEffectPicker;
    public View clipEffectSection;
    public android.view.View animationPacksButton;
    public NavigationIconLayout importKeyframesButton, exportKeyframesButton;

    /** The state of the keyframe diamond beside a property (the iOS design). */
    public enum KeyMode {
        /** Hollow: no keyframe holds this property; it is just its value. */
        OFF,
        /** Left half filled: the property is animated, but no keyframe of it sits at the playhead. */
        ANIMATED,
        /** Filled: a keyframe of this property sits at the playhead (edits go into that keyframe). */
        ON_KEY
    }

    /** A keyframe diamond was tapped. */
    public interface KeyDiamondListener {
        void onKeyDiamond(EditingActivity.VideoProperties.ValueType type);
    }

    private final EnumMap<EditingActivity.VideoProperties.ValueType, ImageView> keyDiamonds =
            new EnumMap<>(EditingActivity.VideoProperties.ValueType.class);
    private KeyDiamondListener keyDiamondListener;


    public ClipEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public ClipEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ClipEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ClipEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init()
    {
        super.init();

        totalDurationText = findViewById(R.id.totalDurationText);
        durationText = findViewById(R.id.durationCText);
        startTrimField = findViewById(R.id.startTrimContent);
        endTrimField = findViewById(R.id.endTrimContent);
        clipNameField = findViewById(R.id.clipNameField);
        keyframeScrollFrame = findViewById(R.id.keyframeScrollFrame);
        clearKeyframeButton = findViewById(R.id.clearKeyframeButton);
        positionXField = findViewById(R.id.positionXField);
        positionYField = findViewById(R.id.positionYField);
        rotationField = findViewById(R.id.rotationField);
        scaleXField = findViewById(R.id.scaleXField);
        scaleYField = findViewById(R.id.scaleYField);
        pivotXField = findViewById(R.id.pivotXField);
        pivotYField = findViewById(R.id.pivotYField);
        volumeField = findViewById(R.id.volumeField);
        opacitySeekbar = findViewById(R.id.opacitySeekbar);
        speedSeekbar = findViewById(R.id.speedSeekbar);
        hueField = findViewById(R.id.hueField);
        additionFFmpegCommandField = findViewById(R.id.additionFFmpegCommandField);
        saturationSeekbar = findViewById(R.id.saturationSeekbar);
        brightnessSeekbar = findViewById(R.id.brightnessSeekbar);
        temperatureSeekbar = findViewById(R.id.temperatureSeekbar);
        muteAudioCheckbox = findViewById(R.id.muteAudioCheckbox);
        lockMediaForTemplateCheckbox = findViewById(R.id.lockMediaForTemplateCheckbox);
        reverseCheckbox = findViewById(R.id.reverseCheckbox);
        removeBackgroundCheckbox = findViewById(R.id.removeBackgroundCheckbox);
        removeBackgroundProgress = findViewById(R.id.removeBackgroundProgress);

        inAnimationTypeSpinner = findViewById(R.id.inAnimationTypeContent);
        inAnimationDurationField = findViewById(R.id.inAnimationDurationContent);

        outAnimationTypeSpinner = findViewById(R.id.outAnimationTypeContent);
        outAnimationDurationField = findViewById(R.id.outAnimationDurationContent);

        // Choices come from the animation registry (none + installed animations of that direction)
        inAnimationPicker = new AnimationPicker(getContext(), inAnimationTypeSpinner, inAnimationDurationField, ClipAnimation.Direction.IN);
        outAnimationPicker = new AnimationPicker(getContext(), outAnimationTypeSpinner, outAnimationDurationField, ClipAnimation.Direction.OUT);
        animationPacksButton = findViewById(R.id.animationPacksButton);

        clipEffectSection = findViewById(R.id.clipEffectSection);
        clipEffectPicker = new ClipEffectPicker(getContext(), clipEffectSection);

        opacitySeekbar.setDecimalScale(2);
        speedSeekbar.setDecimalScale(2);
        saturationSeekbar.setDecimalScale(2);
        brightnessSeekbar.setDecimalScale(2);
        temperatureSeekbar.setDecimalScale(2);


        findViewById(R.id.opacityResetButton).setOnClickListener(v -> opacitySeekbar.setProgress(1));
        findViewById(R.id.speedResetButton).setOnClickListener(v -> speedSeekbar.setProgress(1));
        findViewById(R.id.saturationResetButton).setOnClickListener(v -> saturationSeekbar.setProgress(1));
        findViewById(R.id.brightnessResetButton).setOnClickListener(v -> brightnessSeekbar.setProgress(0));
        findViewById(R.id.temperatureResetButton).setOnClickListener(v -> temperatureSeekbar.setProgress(6500));



        easingSpinner = findViewById(R.id.easingContent);
        easingTypeArrayAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, EditingActivity.EasingType.values());
        easingSpinner.setAdapter(easingTypeArrayAdapter);

        importKeyframesButton = findViewById(R.id.importKeyframesButton);
        exportKeyframesButton = findViewById(R.id.exportKeyframesButton);





        installKeyDiamonds();

        // Clear focus after edit
        onClose.add(() -> {
            clipNameField.clearFocus();
            startTrimField.clearFocus();
            endTrimField.clearFocus();
            positionXField.clearFocus();
            positionYField.clearFocus();
            rotationField.clearFocus();
            scaleXField.clearFocus();
            scaleYField.clearFocus();
            if (pivotXField != null) pivotXField.clearFocus();
            if (pivotYField != null) pivotYField.clearFocus();
            hueField.clearFocus();
            volumeField.clearFocus();
            additionFFmpegCommandField.clearFocus();
            inAnimationDurationField.clearFocus();
            outAnimationDurationField.clearFocus();
        });
    }

    private Drawable getThumb(int progress) {
        View thumbView = LayoutInflater.from(getContext()).inflate(R.layout.seekbar_tv, null, false);
        ((TextView) thumbView.findViewById(R.id.tvProgress)).setText(progress + "");
        thumbView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        Bitmap bitmap = Bitmap.createBitmap(thumbView.getMeasuredWidth(), thumbView.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        thumbView.layout(0, 0, thumbView.getMeasuredWidth(), thumbView.getMeasuredHeight());
        thumbView.draw(canvas);
        return new BitmapDrawable(getResources(), bitmap);
    }

    // ---- keyframe diamonds ---------------------------------------------------------------

    public void setKeyDiamondListener(KeyDiamondListener listener) {
        keyDiamondListener = listener;
    }

    /**
     * Puts one diamond beside every property (hollow = not animated, half = animated but no keyframe of it at the
     * playhead, filled = a keyframe of it is at the playhead). Added in code so the layout stays as it is: after the
     * field of a "X / Y" pair, or at the end of a row whose value or reset button sits at the row's end.
     */
    private void installKeyDiamonds() {
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.PosX, positionXField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.PosY, positionYField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.Rot, rotationField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.ScaleX, scaleXField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.ScaleY, scaleYField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.PivotX, pivotXField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.PivotY, pivotYField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.Volume, volumeField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.Hue, hueField);
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.Opacity, findViewById(R.id.opacityResetButton));
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.Speed, findViewById(R.id.speedResetButton));
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.Saturation, findViewById(R.id.saturationResetButton));
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.Brightness, findViewById(R.id.brightnessResetButton));
        insertKeyDiamond(EditingActivity.VideoProperties.ValueType.Temperature, findViewById(R.id.temperatureResetButton));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void insertKeyDiamond(EditingActivity.VideoProperties.ValueType type, View anchor) {
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) anchor.getParent();

        ImageView diamond = new ImageView(getContext());
        diamond.setId(View.generateViewId());
        diamond.setScaleType(ImageView.ScaleType.FIT_CENTER);
        diamond.setPadding(dp(5), dp(5), dp(5), dp(5));
        diamond.setContentDescription("Keyframe " + type.displayName());
        diamond.setOnClickListener(v -> {
            if (keyDiamondListener != null) keyDiamondListener.onKeyDiamond(type);
        });

        int size = dp(28); // two of them sit in each X / Y row, so keep them compact
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            parent.addView(diamond, parent.indexOfChild(anchor) + 1, lp);
        } else if (parent instanceof RelativeLayout) {
            // The anchor was pinned to the end of the row: it now sits next to the diamond, which takes the end.
            RelativeLayout.LayoutParams anchorParams = (RelativeLayout.LayoutParams) anchor.getLayoutParams();
            anchorParams.removeRule(RelativeLayout.ALIGN_PARENT_END);
            anchorParams.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            anchorParams.addRule(RelativeLayout.START_OF, diamond.getId());
            anchor.setLayoutParams(anchorParams);

            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(size, size);
            lp.addRule(RelativeLayout.ALIGN_PARENT_END);
            lp.addRule(RelativeLayout.CENTER_VERTICAL);
            parent.addView(diamond, lp);
        } else {
            return;
        }
        keyDiamonds.put(type, diamond);
        setKeyMode(type, KeyMode.OFF, true);
    }

    /** Shows the diamond of one property; {@code enabled} is false while the playhead is outside the clip. */
    public void setKeyMode(EditingActivity.VideoProperties.ValueType type, KeyMode mode, boolean enabled) {
        ImageView diamond = keyDiamonds.get(type);
        if (diamond == null) return;
        int icon = mode == KeyMode.ON_KEY ? R.drawable.ic_key_on
                : (mode == KeyMode.ANIMATED ? R.drawable.ic_key_animated : R.drawable.ic_key_off);
        diamond.setImageResource(icon);
        if (mode == KeyMode.OFF) {
            TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
            ColorStateList off = a.getColorStateList(0);
            a.recycle();
            diamond.setImageTintList(off);
        } else {
            diamond.setImageTintList(ColorStateList.valueOf(getContext().getColor(R.color.keyframe_active)));
        }
        diamond.setEnabled(enabled);
        diamond.setAlpha(enabled ? 1f : 0.3f);
    }

    public void createKeyframeElement(EditingActivity.Clip clip, EditingActivity.Keyframe keyframe, Runnable onClickKeyframe, Runnable onLongClickKeyframe)
    {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(getContext());
        text.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        text.setText(String.format(Locale.US, "Keyframe | %.2fs | %s", keyframe.getLocalTime(), keyframe.channelSummary()));
        text.setOnClickListener(v -> {
            text.setBackgroundColor(getResources().getColor(R.color.colorHighlightedButton, null));
            onClickKeyframe.run();
        });
        text.setOnLongClickListener(v -> {
            onLongClickKeyframe.run();

            keyframeScrollFrame.removeView(layout);
            return true;
        });

        layout.addView(text);

        keyframeScrollFrame.addView(layout);
    }

}
