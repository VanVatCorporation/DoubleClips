package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.Spinner;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.TextStyle;

import java.util.ArrayList;
import java.util.List;

public class TextEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    public EditText textEditContent;
    public EditText textSizeContent;
    public EditText colorContent, outlineWidthContent, outlineColorContent;
    public Spinner stylePresetSpinner;

    private final List<TextStyle> presets = TextStyle.builtIns();
    private boolean suppressPresetSelection;


    public TextEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public TextEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TextEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TextEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init()
    {
        super.init();
        textEditContent = findViewById(R.id.textContent);
        textSizeContent = findViewById(R.id.sizeContent);
        colorContent = findViewById(R.id.colorContent);
        outlineWidthContent = findViewById(R.id.outlineWidthContent);
        outlineColorContent = findViewById(R.id.outlineColorContent);
        stylePresetSpinner = findViewById(R.id.stylePresetSpinner);

        List<String> names = new ArrayList<>();
        for (TextStyle preset : presets) names.add(preset.name);
        names.add("Custom");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stylePresetSpinner.setAdapter(adapter);
        stylePresetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressPresetSelection || position >= presets.size()) return;
                // Picking a preset copies its values into the fields (they are saved with the clip on close).
                TextStyle preset = presets.get(position);
                colorContent.setText(TextStyle.toHex(preset.colorArgb));
                outlineWidthContent.setText(String.valueOf(preset.outlineWidth));
                outlineColorContent.setText(TextStyle.toHex(preset.outlineColorArgb));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        onClose.add(() -> {
            textEditContent.clearFocus();
            textSizeContent.clearFocus();
            colorContent.clearFocus();
            outlineWidthContent.clearFocus();
            outlineColorContent.clearFocus();
        });
    }

    /** Fills the style fields from a clip's style (null = the default look) and selects the matching preset, or "Custom". */
    public void showStyle(TextStyle style) {
        TextStyle shown = style != null ? style : TextStyle.DEFAULT;
        colorContent.setText(TextStyle.toHex(shown.colorArgb));
        outlineWidthContent.setText(String.valueOf(shown.outlineWidth));
        outlineColorContent.setText(TextStyle.toHex(shown.outlineColorArgb));
        int position = presets.size(); // Custom
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).sameLookAs(shown) && shown.fontPath == null) { position = i; break; }
        }
        suppressPresetSelection = true;
        stylePresetSpinner.setSelection(position, false);
        suppressPresetSelection = false;
    }

    /**
     * The style the fields describe. Keeps the clip's font (no font picker yet); if the values equal a
     * preset's it is recorded as that preset (id / name), otherwise as a custom look.
     */
    public TextStyle readStyle(TextStyle previous) {
        TextStyle base = previous != null ? previous : TextStyle.DEFAULT;
        TextStyle result = new TextStyle();
        result.fontPath = base.fontPath;
        result.colorArgb = TextStyle.parseHex(colorContent.getText().toString(), base.colorArgb);
        result.outlineColorArgb = TextStyle.parseHex(outlineColorContent.getText().toString(), base.outlineColorArgb);
        float width = base.outlineWidth;
        try {
            width = Float.parseFloat(outlineWidthContent.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // keep the previous value
        }
        result.outlineWidth = Math.max(0f, Math.min(64f, width));
        result.supportedEngines = base.supportedEngines == null ? null : new ArrayList<>(base.supportedEngines);
        for (TextStyle preset : presets) {
            if (preset.sameLookAs(result)) {
                result.id = preset.id;
                result.name = preset.name;
                result.author = preset.author;
                return result;
            }
        }
        result.id = null;
        result.name = "Custom";
        return result;
    }


}
