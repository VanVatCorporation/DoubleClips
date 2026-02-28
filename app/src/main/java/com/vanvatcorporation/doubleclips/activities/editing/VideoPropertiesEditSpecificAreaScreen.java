package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.R;

public class VideoPropertiesEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {


    public ArrayAdapter<String> presetAdapter, tuneAdapter;

    public Spinner presetSpinner, tuneSpinner;
    public EditText resolutionXField, resolutionYField, frameRateField, bitrateField, clipCapField;
    public SwitchMaterial stretchMediaToFullCheckbox; // Media
    public EditText previewFpsField, previewSpeedField;
    public SwitchMaterial reversePlaybackCheckbox; // Playback
    public SwitchMaterial keepPlaybackWhenClipSelectedCheckbox; // Playback
    public EditText audioBarWidthField, audioBarGapField;

    public VideoPropertiesEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public VideoPropertiesEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VideoPropertiesEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public VideoPropertiesEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init() {
        super.init();


        resolutionXField = findViewById(R.id.resolutionXField);
        resolutionYField = findViewById(R.id.resolutionYField);
        frameRateField = findViewById(R.id.exportFrameRate);
        bitrateField = findViewById(R.id.bitrateField);
        clipCapField = findViewById(R.id.exportClipCap);

        presetSpinner = findViewById(R.id.exportPreset);
        presetAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1,
                FFmpegEdit.FFmpegUtilities.presetStringList);
        presetSpinner.setAdapter(presetAdapter);
        presetSpinner.setSelection(9); // ULTRAFAST
        tuneSpinner = findViewById(R.id.exportTune);
        tuneAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1,
                FFmpegEdit.FFmpegUtilities.tuneStringList);
        tuneSpinner.setAdapter(tuneAdapter);
        tuneSpinner.setSelection(5); // ZEROLATENCY

        stretchMediaToFullCheckbox = findViewById(R.id.stretchToFullCheckbox);

        previewFpsField = findViewById(R.id.previewFpsField);
        previewSpeedField = findViewById(R.id.previewSpeedField);
        reversePlaybackCheckbox = findViewById(R.id.reversePlaybackCheckbox);
        keepPlaybackWhenClipSelectedCheckbox = findViewById(R.id.keepPlayingClipSelectedCheckbox);

        audioBarWidthField = findViewById(R.id.audioBarWidthField);
        audioBarGapField = findViewById(R.id.audioBarGapField);


        onClose.add(() -> {
            resolutionXField.clearFocus();
            resolutionYField.clearFocus();
            frameRateField.clearFocus();
            bitrateField.clearFocus();
            clipCapField.clearFocus();
            presetSpinner.clearFocus();
            tuneSpinner.clearFocus();
            previewFpsField.clearFocus();
            previewSpeedField.clearFocus();
            audioBarWidthField.clearFocus();
            audioBarGapField.clearFocus();
        });

        animationScreen = AnimationScreen.ToBottom;
    }
}
