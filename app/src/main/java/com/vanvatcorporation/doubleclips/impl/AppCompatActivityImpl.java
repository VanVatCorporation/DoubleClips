package com.vanvatcorporation.doubleclips.impl;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.vanvatcorporation.doubleclips.AppLanguage;
import com.vanvatcorporation.doubleclips.R;

public class AppCompatActivityImpl extends AppCompatActivity {
    protected Bundle createrBundle;
    /** {@link AppLanguage#version()} when this screen was made: another number later means the language was changed meanwhile. */
    private int languageVersion;

    /** Every screen shows the app's language (Settings > General > Language). */
    @Override
    protected void attachBaseContext(Context newBase) {
        languageVersion = AppLanguage.version();
        super.attachBaseContext(AppLanguage.wrap(newBase));
    }

    /**
     * AppCompat hands its own configuration (dark mode...) to the activity, which would drop the language set in
     * {@link #attachBaseContext}: keep the base context's configuration and take over only what AppCompat changed (uiMode).
     */
    @Override
    public void applyOverrideConfiguration(Configuration overrideConfiguration) {
        if (overrideConfiguration != null) {
            int uiMode = overrideConfiguration.uiMode;
            overrideConfiguration.setTo(getBaseContext().getResources().getConfiguration());
            overrideConfiguration.uiMode = uiMode;
        }
        super.applyOverrideConfiguration(overrideConfiguration);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The language was changed in Settings while this screen was waiting underneath: show it in the new one.
        if (languageVersion != AppLanguage.version()) recreate();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        AppLanguage.applyToApplication(this); // services, notifications and toasts read the application's resources

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String theme = prefs.getString("theme_mode", "system");

        switch (theme) {
            case "dark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "light":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "system":
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }

        createrBundle = getIntent().getExtras();


    }
}
