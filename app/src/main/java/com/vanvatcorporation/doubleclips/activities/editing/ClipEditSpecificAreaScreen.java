package com.vanvatcorporation.doubleclips.activities.editing;

import android.app.AlertDialog;
import android.content.Context;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.impl.NavigationIconLayout;
import com.warkiz.widget.IndicatorSeekBar;

public class ClipEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    public TextView durationText, totalDurationText;
    public EditText clipNameField, startTrimField, endTrimField, positionXField, positionYField, rotationField, scaleXField, scaleYField, hueField, additionFFmpegCommandField, inAnimationDurationField;
    public IndicatorSeekBar opacitySeekbar, speedSeekbar, saturationSeekbar, brightnessSeekbar, temperatureSeekbar;
    public SwitchMaterial muteAudioCheckbox, lockMediaForTemplateCheckbox, reverseCheckbox, removeBackgroundCheckbox;
    public android.widget.ProgressBar removeBackgroundProgress;
    public LinearLayout keyframeScrollFrame;
    public Button clearKeyframeButton;
    public ArrayAdapter<EditingActivity.EasingType> easingTypeArrayAdapter;
    public Spinner easingSpinner, inAnimationTypeSpinner;
    public NavigationIconLayout importKeyframesButton, exportKeyframesButton;


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

        ArrayAdapter<String> animationTypeAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, new String[]{"none", "unfold"});
        inAnimationTypeSpinner.setAdapter(animationTypeAdapter);

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
            hueField.clearFocus();
            additionFFmpegCommandField.clearFocus();
            inAnimationDurationField.clearFocus();
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

    public void createKeyframeElement(EditingActivity.Clip clip, EditingActivity.Keyframe keyframe, Runnable onClickKeyframe, Runnable onLongClickKeyframe)
    {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(getContext());
        text.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        text.setText("Keyframe | Local clip time: " + keyframe.getLocalTime());
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
