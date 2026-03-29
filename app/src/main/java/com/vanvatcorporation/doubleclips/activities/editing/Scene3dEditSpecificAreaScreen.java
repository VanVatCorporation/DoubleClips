package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;

import com.vanvatcorporation.doubleclips.R;

public class Scene3dEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    public EditText sceneConfigContent;
    public EditText textureClipNameContent;

    public Scene3dEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public Scene3dEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public Scene3dEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public Scene3dEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init()
    {
        super.init();
        sceneConfigContent = findViewById(R.id.sceneConfigContent);
        textureClipNameContent = findViewById(R.id.textureClipNameContent);

        onClose.add(() -> {
            sceneConfigContent.clearFocus();
            textureClipNameContent.clearFocus();
        });
    }
}
