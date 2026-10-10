package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.Spinner;

import com.vanvatcorporation.doubleclips.ClipAnimationAssets;
import com.vanvatcorporation.doubleclips.FXCommandEmitter;
import com.vanvatcorporation.doubleclips.OpenGLEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.TransitionStyleCatalog;
import com.vanvatcorporation.doubleclips.TransitionStyle;
import com.vanvatcorporation.doubleclips.TransitionStyleLoader;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.util.ArrayList;
import java.util.List;

public class TransitionEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    public Button applyAllTransitionButton;
    public Spinner transitionEditContent;
    public EditText transitionDurationContent;
    public Spinner transitionModeEditContent;
    /** What the style spinner shows, in order: the built-in styles plus the data-driven ones. */
    public List<TransitionStyleCatalog.Entry> styleEntries = new ArrayList<>();
    /** True from the moment the user touches the style spinner until the next selection (or refreshStyles). */
    private boolean userPickingStyle = false;


    public TransitionEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public TransitionEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TransitionEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TransitionEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init()
    {
        super.init();

        applyAllTransitionButton = findViewById(R.id.applyAllButton);
        transitionEditContent = findViewById(R.id.transitionContent);
        transitionDurationContent = findViewById(R.id.durationContent);
        transitionModeEditContent = findViewById(R.id.transitionModeContent);

        refreshStyles(null);

        // Picking a data-driven style fills in its own default duration (Glitch Blur is 2.3 s, a new transition starts
        // at 0.2 s). Only for a pick the user made: selections the code makes (opening the picker on a knot) must
        // never overwrite the knot's saved duration, so the listener acts only after the spinner was touched.
        transitionEditContent.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) userPickingStyle = true;
            return false;
        });
        transitionEditContent.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!userPickingStyle) return;
                userPickingStyle = false;
                if (position < 0 || position >= styleEntries.size() || !styleEntries.get(position).dataDriven) return;
                TransitionStyle style = TransitionStyleLoader.get(styleEntries.get(position).id);
                if (style == null) return;
                java.text.DecimalFormat format = new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
                transitionDurationContent.setText(format.format(style.getDefaultDuration()));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        transitionModeEditContent.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, EditingActivity.TransitionClip.TransitionMode.values()));


        onClose.add(() -> {
            transitionEditContent.clearFocus();
            transitionDurationContent.clearFocus();
        });
    }

    /**
     * Rebuilds the style list (the data-driven styles may have loaded since) and selects {@code currentStyle}.
     * A stored style that is in neither list stays selectable as "[not installed]" so closing the picker
     * does not overwrite it.
     */
    public void refreshStyles(String currentStyle) {
        ClipAnimationAssets.loadAll(getContext()); // also loads the transition styles; no-op after the first call
        styleEntries = TransitionStyleCatalog.build(
                FXCommandEmitter.FXRegistry.transitionFXMap,
                OpenGLEdit.SUPPORTED_TRANSITION_STYLES,
                TransitionStyleLoader.list(),
                OpenGLEdit.SUPPORTS_DATA_DRIVEN_TRANSITIONS,
                currentStyle);
        userPickingStyle = false; // this selection is the code's, not the user's
        transitionEditContent.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, styleEntries));
        int index = TransitionStyleCatalog.indexOf(styleEntries, currentStyle);
        if (index >= 0) transitionEditContent.setSelection(index);
    }

    /** The style id at a spinner position, or {@code fallback} when the position is not valid. */
    public String styleIdAt(int position, String fallback) {
        return position >= 0 && position < styleEntries.size() ? styleEntries.get(position).id : fallback;
    }





}
