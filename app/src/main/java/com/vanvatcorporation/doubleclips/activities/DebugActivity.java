package com.vanvatcorporation.doubleclips.activities;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;

public class DebugActivity extends AppCompatActivityImpl {



    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.layout_debug);

        String debugFilePath = IOHelper.CombinePath(IOHelper.getPersistentDataPath(this), Constants.DEFAULT_LOGGING_DIRECTORY, "debug.txt");

        findViewById(R.id.backButton).setOnClickListener(v -> {
            finish();
        });
        findViewById(R.id.clearButton).setOnClickListener(v -> {
            IOHelper.writeToFile(this, debugFilePath, "");
            ((TextView) findViewById(R.id.debugText)).setText("");
        });

        ((TextView) findViewById(R.id.debugText)).setText(IOHelper.readFromFile(this, debugFilePath));
    }
}
