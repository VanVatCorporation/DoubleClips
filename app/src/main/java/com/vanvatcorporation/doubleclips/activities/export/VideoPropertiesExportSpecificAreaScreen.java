package com.vanvatcorporation.doubleclips.activities.export;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ArrayAdapter;
import com.google.android.material.switchmaterial.SwitchMaterial;
import android.widget.EditText;
import android.widget.Spinner;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.editing.BaseEditSpecificAreaScreen;

public class VideoPropertiesExportSpecificAreaScreen extends BaseEditSpecificAreaScreen {


    public ArrayAdapter<String> presetAdapter, tuneAdapter;

    public Spinner presetSpinner, tuneSpinner;
    public EditText resolutionXField, resolutionYField, frameRateText, crfText, clipCapText;
    public SwitchMaterial stretchMediaToFullCheckbox; // Media




    public VideoPropertiesExportSpecificAreaScreen(Context context) {
        super(context);
    }

    public VideoPropertiesExportSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VideoPropertiesExportSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public VideoPropertiesExportSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init() {
        super.init();

        resolutionXField = findViewById(R.id.resolutionXField);
        resolutionYField = findViewById(R.id.resolutionYField);
        frameRateText = findViewById(R.id.exportFrameRate);
        crfText = findViewById(R.id.exportCRF);
        clipCapText = findViewById(R.id.exportClipCap);

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


        onClose.add(() -> {
            resolutionXField.clearFocus();
            resolutionYField.clearFocus();
            frameRateText.clearFocus();
            crfText.clearFocus();
            clipCapText.clearFocus();
            presetSpinner.clearFocus();
            tuneSpinner.clearFocus();
        });

        animationScreen = AnimationScreen.ToBottom;
    }
}
