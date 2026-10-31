package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import androidx.preference.PreferenceManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The app's language (Settings > General > Language), next to the Theme setting. Same idea as iOS's AppLanguage.
 *
 * <ul>
 *   <li>The choice is stored under {@link #PREF_KEY} in the default preferences: {@link #SYSTEM} (follow the phone) or a code from
 *       {@link #SUPPORTED}.</li>
 *   <li>English is the language the app is written in: {@code res/values/strings.xml}. Every other language is a
 *       {@code res/values-<code>/strings.xml} with the same names; a string missing there simply shows in English.</li>
 *   <li>Every activity wraps its context with {@link #wrap(Context)} (AppCompatActivityImpl does it for all of them), so the
 *       language applies to layouts, dialogs, toasts and everything else read through resources. A change takes effect at
 *       once: the Settings screen recreates itself, and any other open screen recreates itself when it comes back
 *       (see {@link #version()}).</li>
 *   <li>To add a language: add a row to {@link #SUPPORTED} and a {@code values-<code>/strings.xml} (copy {@code values-vi}).</li>
 * </ul>
 */
public final class AppLanguage {

    /** Where the choice is kept (the default preferences, like "theme_mode"). */
    public static final String PREF_KEY = "app_language";
    /** The choice "follow the phone's language". */
    public static final String SYSTEM = "system";
    /** What "system" falls back to when the phone's languages are none of ours. */
    public static final String FALLBACK = "en";

    /** One language the app can show. */
    public static final class Language {
        /** BCP-47 language code, also the suffix of the resource folder (values-vi). */
        public final String code;
        /** The language's own name: never translated, so anyone can always find theirs. */
        public final String nativeName;

        Language(String code, String nativeName) {
            this.code = code;
            this.nativeName = nativeName;
        }
    }

    /** Every language the app can show. */
    public static final List<Language> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
            new Language("en", "English"),
            new Language("vi", "Tiếng Việt")
    ));

    private static volatile int version;

    private AppLanguage() { }

    // ---- the list in Settings ------------------------------------------------------------------------

    /** For the picker: alphabetical by the language's own name ("System Default" goes first, added by the caller). */
    public static List<Language> sortedForPicker() {
        List<Language> sorted = new ArrayList<>(SUPPORTED);
        final Collator collator = Collator.getInstance(Locale.ENGLISH);
        Collections.sort(sorted, (a, b) -> collator.compare(a.nativeName, b.nativeName));
        return sorted;
    }

    // ---- choosing ------------------------------------------------------------------------------------

    /**
     * The code to show for a stored choice: the choice itself when it is a supported language, else (for {@link #SYSTEM} or
     * anything unknown) the first of the phone's preferred languages that the app has, else {@link #FALLBACK}.
     *
     * @param preferredLanguages the phone's languages in order of preference, as language codes or tags ("vi", "vi-VN")
     */
    public static String resolve(String choice, List<String> preferredLanguages) {
        if (choice != null && !SYSTEM.equals(choice) && isSupported(choice)) return choice;
        if (preferredLanguages != null) {
            for (String tag : preferredLanguages) {
                if (tag == null) continue;
                String base = tag.replace('_', '-');
                int dash = base.indexOf('-');
                if (dash > 0) base = base.substring(0, dash);
                base = base.toLowerCase(Locale.ROOT);
                if (isSupported(base)) return base;
            }
        }
        return FALLBACK;
    }

    public static boolean isSupported(String code) {
        for (Language language : SUPPORTED) if (language.code.equals(code)) return true;
        return false;
    }

    /** The phone's languages in order of preference (not the app's own override). */
    private static List<String> devicePreferredLanguages() {
        List<String> out = new ArrayList<>();
        Configuration system = Resources.getSystem().getConfiguration();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList list = system.getLocales();
            for (int i = 0; i < list.size(); i++) out.add(list.get(i).toLanguageTag());
        } else {
            out.add(system.locale.toLanguageTag());
        }
        return out;
    }

    /** The stored choice ({@link #SYSTEM} or a code). */
    public static String storedChoice(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(PREF_KEY, SYSTEM);
    }

    /** The code the app shows right now. */
    public static String currentCode(Context context) {
        return resolve(storedChoice(context), devicePreferredLanguages());
    }

    // ---- applying ------------------------------------------------------------------------------------

    /**
     * {@code base} with the app's language: call it from {@code attachBaseContext} of every activity. Also makes the language
     * the default Locale, so numbers and dates that are formatted in code follow it.
     */
    public static Context wrap(Context base) {
        Locale locale = Locale.forLanguageTag(currentCode(base));
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) config.setLocales(new LocaleList(locale));
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }

    /**
     * Puts the language on the application's own resources too (what services, notifications and toasts made from the application
     * context read), so they match the screens.
     */
    @SuppressWarnings("deprecation")
    public static void applyToApplication(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) return;
        Locale locale = Locale.forLanguageTag(currentCode(app));
        Resources resources = app.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        config.setLocale(locale);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    /** Changes with every language change: a screen that was created under an older number is showing the old language. */
    public static int version() {
        return version;
    }

    /** Call after the stored choice changed. */
    public static void changed() {
        version++;
    }
}
