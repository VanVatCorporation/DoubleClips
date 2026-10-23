package com.vanvatcorporation.doubleclips.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.RetrofitClient;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import com.vanvatcorporation.drivekit.DriveClient;
import com.vanvatcorporation.drivekit.Models;
import com.vanvatcorporation.drivekit.Thumbs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends PreferenceFragmentCompat {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private DriveClient drive;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_preferences, rootKey);

        ListPreference themePref = findPreference("theme_mode");
        if (themePref != null) {
            themePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String value = newValue.toString();
                switch (value) {
                    case "dark":
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                        break;
                    case "light":
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                        break;
                    case "system":
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                        break;
                }

                // Recreate the activity to apply the theme immediately
                if (getActivity() != null) {
                    getActivity().recreate();
                }

                return true;
            });
        }

        setUpDriveThumbnails();
    }

    private DriveClient drive() {
        if (drive == null) {
            drive = new DriveClient(DriveClient.DEFAULT_BASE, new DriveClient.Auth() {
                @Override public String token() { return RetrofitClient.getInstance(requireContext()).getAuthToken(); }
                @Override public void onRefreshed(String token) { /* the cookie jar stays the source of truth */ }
                @Override public void onUnauthorized() { /* the Profile tab handles signing in again */ }
            });
        }
        return drive;
    }

    /** "Van Vat Drive" section: the thumbnails switch lives on the account (like the web), not on this device. */
    private void setUpDriveThumbnails() {
        SwitchPreference thumbs = findPreference("drive_preview_thumbnails");
        if (thumbs == null) return;
        if (RetrofitClient.getInstance(requireContext()).getAuthToken() == null) {
            thumbs.setEnabled(false);
            thumbs.setSummary("Sign in from the Profile tab to change this.");
            return;
        }
        thumbs.setEnabled(false);                       // until the account's current value is known
        io.execute(() -> {
            try {
                Models.Me me = drive().me();
                ui.post(() -> { thumbs.setChecked(Thumbs.enabled(me)); thumbs.setEnabled(true); });
            } catch (Exception e) {
                ui.post(() -> thumbs.setSummary("Couldn’t load this setting: " + e.getMessage()));
            }
        });
        thumbs.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean on = (Boolean) newValue;
            io.execute(() -> {
                try {
                    drive().updateSettings(on);
                } catch (Exception e) {
                    ui.post(() -> {
                        thumbs.setChecked(!on);         // the server refused: roll back
                        if (getContext() != null) LoggingManager.LogToToast(getContext(), e.getMessage());
                    });
                }
            });
            return true;
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
