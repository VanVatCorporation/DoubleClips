package com.vanvatcorporation.doubleclips.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.vanvatcorporation.doubleclips.AppLanguage;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.assets.HiddenContentSheet;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.RetrofitClient;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import com.vanvatcorporation.drivekit.DriveClient;
import com.vanvatcorporation.drivekit.Models;
import com.vanvatcorporation.drivekit.Thumbs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends PreferenceFragmentCompat {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private DriveClient drive;

    /** "Open-source licences": the third-party software inside the app (LicensesActivity). */
    private void setUpLicenses() {
        Preference pref = findPreference("open_source_licenses");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(p -> {
            startActivity(new android.content.Intent(requireContext(), com.vanvatcorporation.doubleclips.activities.LicensesActivity.class));
            return true;
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_preferences, rootKey);

        setUpHiddenContent();
        setUpLicenses();

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

        setUpLanguage();
        setUpDriveThumbnails();
    }

    /** Settings > General > Language: "System Default" at the top, then the languages by their own names. */
    private void setUpLanguage() {
        ListPreference languagePref = findPreference(AppLanguage.PREF_KEY);
        if (languagePref == null) return;
        List<AppLanguage.Language> languages = AppLanguage.sortedForPicker();
        CharSequence[] names = new CharSequence[languages.size() + 1];
        CharSequence[] codes = new CharSequence[languages.size() + 1];
        names[0] = getString(R.string.language_system_default);
        codes[0] = AppLanguage.SYSTEM;
        for (int i = 0; i < languages.size(); i++) {
            names[i + 1] = languages.get(i).nativeName; // never translated: anyone can find theirs
            codes[i + 1] = languages.get(i).code;
        }
        languagePref.setEntries(names);
        languagePref.setEntryValues(codes);
        languagePref.setOnPreferenceChangeListener((preference, newValue) -> {
            // The preference only saves the value after this returns: save it now, the new screen reads it as it starts.
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit().putString(AppLanguage.PREF_KEY, newValue.toString()).commit();
            AppLanguage.changed(); // every other open screen recreates itself when it comes back
            if (getActivity() != null) getActivity().recreate();
            return true;
        });
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
            thumbs.setSummary(R.string.sign_in_from_the_profile_tab_to_ch);
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

    /** "Hidden content": the asset authors and templates the user hid, with a way back. The summary counts them. */
    private void setUpHiddenContent() {
        Preference hidden = findPreference("hidden_content");
        if (hidden == null) return;
        hidden.setSummary(HiddenContentSheet.summary(requireContext()));
        hidden.setOnPreferenceClickListener(p -> {
            HiddenContentSheet.show((FragmentActivity) requireActivity(), this::updateHiddenSummary);
            return true;
        });
    }

    private void updateHiddenSummary() {
        Preference hidden = findPreference("hidden_content");
        if (hidden != null && getContext() != null) hidden.setSummary(HiddenContentSheet.summary(getContext()));
    }

    @Override
    public void onResume() {
        super.onResume();
        updateHiddenSummary();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
