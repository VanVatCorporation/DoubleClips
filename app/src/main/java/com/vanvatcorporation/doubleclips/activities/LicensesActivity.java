package com.vanvatcorporation.doubleclips.activities;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.arthenica.ffmpegkit.Packages;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings > Open-source licences: the third-party software inside the app, what it is used for, its licence, where its source is and,
 * when the full text is bundled, the licence text. The texts are plain files in src/main/assets/licenses/ (tools/licenses/
 * fetch-license-texts.sh downloads them: LGPL-3.0.txt, GPL-3.0.txt, Apache-2.0.txt, MIT.txt, BSD-2/3-Clause.txt); without them the notice and the
 * link are shown.
 *
 * The ffmpeg-kit entry is read from the library at run time (its package name and external libraries), so a "-gpl" package is reported
 * as GPL, never as the LGPL by mistake.
 *
 * The list is from the code's imports: add a library here when you add one to build.gradle.
 */
public class LicensesActivity extends AppCompatActivityImpl {

    private static final class Notice {
        final String name, usedFor, license, copyright, note;
        final String[][] links;      // { title, url }
        final String[] textFiles;    // assets/licenses/<name>.txt
        Notice(String name, String usedFor, String license, String copyright, String note, String[][] links, String... textFiles) {
            this.name = name; this.usedFor = usedFor; this.license = license; this.copyright = copyright;
            this.note = note; this.links = links; this.textFiles = textFiles;
        }
    }

    private static final String APACHE = "Apache License, Version 2.0";
    private static final String[] APACHE_TEXT = {"Apache-2.0"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.open_source_licenses);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(list);

        TextView title = text(getString(R.string.open_source_licenses), 24, true);
        list.addView(title);
        TextView intro = text(getString(R.string.licenses_summary), 14, false);
        intro.setPadding(0, dp(4), 0, dp(12));
        list.addView(intro);

        for (Notice notice : notices()) list.addView(card(notice));
        setContentView(scroll);
    }

    private List<Notice> notices() {
        List<Notice> n = new ArrayList<>();
        n.add(ffmpegKit());
        n.add(new Notice("Gson", "Reading and writing project files (JSON).", APACHE, "Copyright © Google Inc.", null,
                new String[][]{{"Source code", "https://github.com/google/gson"}}, APACHE_TEXT));
        n.add(new Notice("OkHttp, Okio and Retrofit", "Talking to the Van Vat servers (account, Drive, templates, assets).", APACHE,
                "Copyright © Square, Inc.", null,
                new String[][]{{"OkHttp", "https://github.com/square/okhttp"}, {"Okio", "https://github.com/square/okio"},
                        {"Retrofit", "https://github.com/square/retrofit"}}, APACHE_TEXT));
        n.add(new Notice("Rajawali", "3D drawing (the 3D preview of the editor).", APACHE, "Copyright © Dennis Ippel and contributors", null,
                new String[][]{{"Source code", "https://github.com/Rajawali/Rajawali"}}, APACHE_TEXT));
        n.add(new Notice("jsoup", "Reading web pages (HTML).", "MIT License", "Copyright © Jonathan Hedley", null,
                new String[][]{{"Source code", "https://github.com/jhy/jsoup"}}, "MIT"));
        n.add(new Notice("IndicatorSeekBar", "The sliders of the editor.", APACHE, "Copyright © zhuangguangquan", null,
                new String[][]{{"Source code", "https://github.com/warkiz/IndicatorSeekBar"}}, APACHE_TEXT));
        n.add(new Notice("Google ML Kit", "On-device machine learning (subject recognition).", "Google ML Kit Terms of Service",
                "Copyright © Google LLC", "ML Kit is provided by Google under its own terms; it is not open-source software.",
                new String[][]{{"Terms", "https://developers.google.com/ml-kit/terms"}}));
        n.add(new Notice("AndroidX, Material Components and the Android platform", "The app's building blocks (screens, preferences, lists).",
                APACHE, "Copyright © The Android Open Source Project", null,
                new String[][]{{"Source code", "https://source.android.com"}}, APACHE_TEXT));
        return n;
    }

    /** ffmpeg-kit, described by what is really inside this build. */
    private Notice ffmpegKit() {
        String pkg = null, libs = null;
        try {
            pkg = Packages.getPackageName();
            libs = TextUtils.join(", ", Packages.getExternalLibraries());
        } catch (Throwable ignored) { /* a build without that information */ }
        boolean gpl = pkg != null && pkg.toLowerCase().contains("gpl");
        String license = pkg == null
                ? "GNU Lesser General Public License v3.0 (GPL v3.0 for builds that contain GPL libraries)"
                : gpl ? "GNU General Public License, version 3 (GPL-3.0): this build contains GPL libraries"
                      : "GNU Lesser General Public License, version 3 (LGPL-3.0)";
        StringBuilder note = new StringBuilder();
        if (pkg != null) note.append("Package: ").append(pkg).append('.');
        if (libs != null && !libs.isEmpty()) note.append(" Libraries inside: ").append(libs).append('.');
        return new Notice("FFmpeg (ffmpeg-kit)", "Cutting, joining, filtering and exporting video and audio.", license,
                "Copyright © the FFmpeg developers; ffmpeg-kit © ARTHENICA LTD and contributors", note.toString().trim(),
                new String[][]{{"FFmpeg website", "https://ffmpeg.org"}, {"ffmpeg-kit source code", "https://github.com/arthenica/ffmpeg-kit"},
                        {"Licence text", gpl ? "https://www.gnu.org/licenses/gpl-3.0.html" : "https://www.gnu.org/licenses/lgpl-3.0.html"}},
                gpl ? new String[]{"GPL-3.0"} : new String[]{"LGPL-3.0", "GPL-3.0"});
    }

    // ---- views -------------------------------------------------------------------------------------------------------

    private View card(Notice n) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(14), 0, dp(14));

        card.addView(text(n.name, 17, true));
        card.addView(text(getString(R.string.licenses_used_for, n.usedFor), 14, false));
        card.addView(text(getString(R.string.licenses_license, n.license), 14, true));
        card.addView(text(n.copyright, 12, false));
        if (n.note != null && !n.note.isEmpty()) card.addView(text(n.note, 12, false));
        for (String[] link : n.links) {
            TextView tv = text(link[0] + ": " + link[1], 13, false);
            Linkify.addLinks(tv, Linkify.WEB_URLS);
            card.addView(tv);
        }
        if (n.textFiles.length > 0) {
            Button toggle = new Button(this);
            toggle.setAllCaps(false);
            toggle.setText(R.string.licenses_show_text);
            TextView full = text("", 10, false);
            full.setTypeface(Typeface.MONOSPACE);
            full.setTextIsSelectable(true);
            full.setVisibility(View.GONE);
            toggle.setOnClickListener(v -> {
                boolean show = full.getVisibility() != View.VISIBLE;
                if (show && full.length() == 0) full.setText(readTexts(n.textFiles));
                full.setVisibility(show ? View.VISIBLE : View.GONE);
                toggle.setText(show ? R.string.licenses_hide_text : R.string.licenses_show_text);
            });
            card.addView(toggle);
            card.addView(full);
        }
        View line = new View(this);
        line.setBackgroundColor(0x33888888);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(14);
        card.addView(line, lp);
        return card;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    /** The bundled texts one after the other, or a pointer to the link when none is bundled. */
    private String readTexts(String[] files) {
        StringBuilder out = new StringBuilder();
        for (String file : files) {
            try (InputStream in = getAssets().open("licenses/" + file + ".txt")) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
                if (out.length() > 0) out.append("\n\n————————————————————\n\n");
                out.append(buffer.toString("UTF-8"));
            } catch (Exception missing) { /* not bundled */ }
        }
        return out.length() == 0 ? getString(R.string.licenses_text_missing) : out.toString();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
