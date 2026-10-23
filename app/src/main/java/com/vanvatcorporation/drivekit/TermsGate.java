package com.vanvatcorporation.drivekit;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.Intent;
import android.net.Uri;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The blocking Terms of Service popup, same layout as the web: title, subtitle, the product's terms in a scrollable box, a
 * checkbox "I accept the Privacy Policy and Terms of Service" (both are links to the full pages) and a Continue button that
 * stays disabled until the box is ticked. It cannot be dismissed (no back button, no tap outside): the only way on is
 * accepting, which the server records. It shows for users who have not accepted yet and again for everyone whenever new
 * terms are published.
 *
 * Built in code, with its own colours, so it needs no layout or theme from the host app: the Drive app and OpenEditz share it.
 */
public final class TermsGate {
    private TermsGate() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int BLUE = 0xFF1E88E5;
    private static Dialog current;

    /** Shows the popup unless one is already showing. {@code onAccepted} runs on the main thread after the server recorded it. */
    public static void show(Activity act, DriveClient client, Models.LegalStatus status, Runnable onAccepted) {
        if (act == null || act.isFinishing() || status == null || !status.required) return;
        if (current != null && current.isShowing()) return;
        new Gate(act, client, status, onAccepted).open();
    }

    // ── texts (the app has no resource files for this: vi and en) ───────────

    private static boolean vi() { return "vi".equals(Locale.getDefault().getLanguage()); }
    private static String tr(String en, String vi) { return vi() ? vi : en; }

    private static final class Gate {
        final Activity act;
        final DriveClient client;
        final Models.LegalStatus status;
        final Runnable onAccepted;
        final float d;
        final boolean night;
        final int bg, fg, muted, box;

        Models.LegalDocument doc;
        String lang;                       // null = the device language
        TextView title, subtitle, langButton, label, error;
        LinearLayout content;
        CheckBox check;
        Button go;
        boolean busy;
        Dialog dialog;

        Gate(Activity act, DriveClient client, Models.LegalStatus status, Runnable onAccepted) {
            this.act = act; this.client = client; this.status = status; this.onAccepted = onAccepted;
            d = act.getResources().getDisplayMetrics().density;
            night = (act.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            bg = night ? 0xFF121212 : 0xFFFFFFFF;
            fg = night ? 0xFFEDEDED : 0xFF1F1F1F;
            muted = night ? 0xFFA0A0A0 : 0xFF666666;
            box = night ? 0xFF1E1E1E : 0xFFF3F4F6;
        }

        int dp(int v) { return Math.round(v * d); }

        void open() {
            dialog = new Dialog(act, android.R.style.Theme_Material_Light_NoActionBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(false);                       // no back button
            dialog.setCanceledOnTouchOutside(false);
            dialog.setContentView(build());
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.setBackgroundDrawable(new ColorDrawable(bg));
                w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            current = dialog;
            dialog.setOnDismissListener(x -> { if (current == dialog) current = null; });
            dialog.show();
            load();
        }

        View build() {
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(bg);
            root.setPadding(dp(20), dp(28), dp(20), dp(16));
            root.setFitsSystemWindows(true);

            // header: title + subtitle, language on the right
            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout titles = new LinearLayout(act);
            titles.setOrientation(LinearLayout.VERTICAL);
            title = text(tr("Terms of Service", "Điều khoản dịch vụ"), 22, fg, true);
            subtitle = text(tr("Please read and accept the Terms of Service and Privacy Policy to keep using Van Vat Drive.",
                    "Vui lòng đọc và chấp nhận Điều khoản dịch vụ và Chính sách bảo mật để tiếp tục sử dụng Van Vat Drive."), 14, muted, false);
            subtitle.setPadding(0, dp(4), 0, 0);
            titles.addView(title);
            titles.addView(subtitle);
            head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            langButton = text("", 14, BLUE, true);
            langButton.setVisibility(View.GONE);
            langButton.setPadding(dp(10), dp(6), dp(6), dp(6));
            head.addView(langButton);
            root.addView(head);

            // the terms, scrollable, in a rounded box
            ScrollView scroll = new ScrollView(act);
            GradientDrawable boxBg = new GradientDrawable();
            boxBg.setColor(box);
            boxBg.setCornerRadius(dp(12));
            scroll.setBackground(boxBg);
            content = new LinearLayout(act);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(14), dp(10), dp(14), dp(14));
            scroll.addView(content);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            sp.topMargin = dp(14);
            root.addView(scroll, sp);

            // footer: checkbox + label with links, error, Continue
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            check = new CheckBox(act);
            check.setButtonTintList(ColorStateList.valueOf(BLUE));
            check.setEnabled(false);
            check.setOnCheckedChangeListener((b, on) -> refresh());
            check.setContentDescription(tr("I accept the Privacy Policy and Terms of Service", "Tôi chấp nhận Chính sách bảo mật và Điều khoản dịch vụ"));
            row.addView(check);
            label = text("", 14, fg, false);
            label.setMovementMethod(LinkMovementMethod.getInstance());
            label.setPadding(0, dp(10), 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            label.setText(acceptLabel());                       // the links are there before the text finishes loading
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(12);
            root.addView(row, rp);

            error = text("", 13, 0xFFE53935, false);
            error.setVisibility(View.GONE);
            root.addView(error);

            go = new Button(act);
            go.setAllCaps(false);
            go.setText(tr("Continue", "Tiếp tục"));
            go.setTextColor(Color.WHITE);
            go.setBackgroundTintList(ColorStateList.valueOf(BLUE));
            go.setOnClickListener(v -> submit());
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.topMargin = dp(8);
            root.addView(go, gp);

            renderLoading();
            refresh();
            return root;
        }

        TextView text(CharSequence s, int sp, int color, boolean bold) {
            TextView t = new TextView(act);
            t.setText(s);
            t.setTextSize(sp);
            t.setTextColor(color);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        /** Continue is enabled only with the box ticked and the text on screen. */
        void refresh() {
            boolean ready = doc != null && !busy;
            check.setEnabled(ready);
            go.setEnabled(ready && check.isChecked());
            go.setAlpha(go.isEnabled() ? 1f : 0.45f);
            go.setText(busy ? tr("Saving…", "Đang lưu…") : tr("Continue", "Tiếp tục"));
        }

        // ── loading ─────────────────────────────────────────────────────────

        void renderLoading() {
            content.removeAllViews();
            content.addView(text(tr("Loading…", "Đang tải…"), 14, muted, false));
        }

        void load() {
            doc = null;
            check.setChecked(false);
            renderLoading();
            refresh();
            final String asked = lang != null ? lang : Locale.getDefault().getLanguage();
            IO.execute(() -> {
                try {
                    Models.LegalDocument loaded = client.legalDocument(status.inline, asked);
                    MAIN.post(() -> { doc = loaded; renderDoc(); refresh(); });
                } catch (Exception e) {
                    MAIN.post(this::renderLoadError);
                }
            });
        }

        void renderLoadError() {
            content.removeAllViews();
            content.addView(text(tr("The Terms of Service could not be loaded. Check your connection and try again.",
                    "Không thể tải Điều khoản dịch vụ. Hãy kiểm tra kết nối và thử lại."), 14, fg, false));
            Button retry = new Button(act);
            retry.setAllCaps(false);
            retry.setText(tr("Try again", "Thử lại"));
            retry.setOnClickListener(v -> load());
            content.addView(retry);
        }

        // ── the document ────────────────────────────────────────────────────

        void renderDoc() {
            content.removeAllViews();
            if (doc.languages != null && doc.languages.size() > 1) {
                langButton.setVisibility(View.VISIBLE);
                langButton.setText(doc.lang.toUpperCase(Locale.ROOT) + " ▾");
                langButton.setOnClickListener(v -> {
                    PopupMenu menu = new PopupMenu(act, v);
                    for (String l : doc.languages) menu.getMenu().add(l.toUpperCase(Locale.ROOT));
                    menu.setOnMenuItemClickListener(item -> { lang = item.getTitle().toString().toLowerCase(Locale.ROOT); load(); return true; });
                    menu.show();
                });
            }
            if (doc.fallback) {
                TextView note = text(tr("This document is not available in your language yet, so it is shown in English.",
                        "Tài liệu này chưa có bản dịch cho ngôn ngữ của bạn nên đang hiển thị bằng tiếng Anh."), 13, BLUE, false);
                note.setPadding(0, dp(4), 0, dp(8));
                content.addView(note);
            }
            for (Models.LegalBlock b : doc.blocks) content.addView(block(b));
            label.setText(acceptLabel());
        }

        View block(Models.LegalBlock b) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            String type = b.type == null ? "p" : b.type;
            switch (type) {
                case "h": {
                    // the page's # title is one level above the popup's own title, so shift down (same as the web popup)
                    int level = (b.level == null ? 1 : b.level) + 2;
                    TextView t = text(spans(b.spans), level <= 2 ? 19 : level == 3 ? 17 : 15, fg, true);
                    t.setMovementMethod(LinkMovementMethod.getInstance());
                    lp.topMargin = dp(14);
                    t.setLayoutParams(lp);
                    return t;
                }
                case "li": {
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    lp.topMargin = dp(4);
                    lp.leftMargin = dp(18) * (b.depth == null ? 0 : b.depth);
                    row.setLayoutParams(lp);
                    TextView marker = text(Boolean.TRUE.equals(b.ordered) ? (b.number == null ? 0 : b.number) + "." : "•", 14, muted, false);
                    marker.setMinWidth(dp(22));
                    marker.setGravity(Gravity.END);
                    marker.setPadding(0, 0, dp(6), 0);
                    row.addView(marker);
                    TextView t = text(spans(b.spans), 14, fg, false);
                    t.setMovementMethod(LinkMovementMethod.getInstance());
                    row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    return row;
                }
                case "quote": {
                    TextView t = text(spans(b.spans), 14, fg, false);
                    t.setBackgroundColor(0x22FFA000);
                    t.setPadding(dp(12), dp(10), dp(12), dp(10));
                    lp.topMargin = dp(10);
                    t.setLayoutParams(lp);
                    t.setMovementMethod(LinkMovementMethod.getInstance());
                    return t;
                }
                case "rule": {
                    View v = new View(act);
                    v.setBackgroundColor(0x33888888);
                    lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                    lp.topMargin = dp(10);
                    lp.bottomMargin = dp(4);
                    v.setLayoutParams(lp);
                    return v;
                }
                default: {
                    TextView t = text(spans(b.spans), 14, fg, false);
                    t.setMovementMethod(LinkMovementMethod.getInstance());
                    lp.topMargin = dp(8);
                    t.setLayoutParams(lp);
                    return t;
                }
            }
        }

        /** Bold / italic / code / link runs as a Spannable. */
        CharSequence spans(List<Models.LegalSpan> runs) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (runs == null) return sb;
            for (Models.LegalSpan s : runs) {
                int start = sb.length();
                sb.append(s.t == null ? "" : s.t);
                int end = sb.length();
                if (end == start) continue;
                if (Boolean.TRUE.equals(s.b)) sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (Boolean.TRUE.equals(s.i)) sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (Boolean.TRUE.equals(s.c)) sb.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (s.href != null && !s.href.isEmpty()) {
                    sb.setSpan(new URLSpan(s.href), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(BLUE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            return sb;
        }

        /** "I accept the {privacy} and {terms}": the two names are blue links to the full pages (the word order differs per language). */
        CharSequence acceptLabel() {
            String template = tr("I accept the {privacy} and {terms}", "Tôi chấp nhận {privacy} và {terms}");
            String pageLang = doc != null && doc.lang != null ? doc.lang : Locale.getDefault().getLanguage();
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int i = 0;
            while (i < template.length()) {
                int p = template.indexOf("{privacy}", i), t = template.indexOf("{terms}", i);
                int next = p < 0 ? t : t < 0 ? p : Math.min(p, t);
                if (next < 0) { sb.append(template.substring(i)); break; }
                sb.append(template, i, next);
                boolean isPrivacy = next == p;
                String name = isPrivacy ? tr("Privacy Policy", "Chính sách bảo mật") : tr("Terms of Service", "Điều khoản dịch vụ");
                final String url = client.legalPageUrl(isPrivacy ? "privacy" : "terms", pageLang);
                int start = sb.length();
                sb.append(name);
                sb.setSpan(new ClickableSpan() {
                    @Override public void onClick(View widget) {
                        try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { /* no browser */ }
                    }
                    @Override public void updateDrawState(TextPaint ds) { ds.setColor(BLUE); ds.setUnderlineText(true); }
                }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = next + (isPrivacy ? "{privacy}".length() : "{terms}".length());
            }
            return sb;
        }

        // ── accept ──────────────────────────────────────────────────────────

        void submit() {
            if (busy || doc == null || !check.isChecked()) return;
            busy = true;
            error.setVisibility(View.GONE);
            refresh();
            final Map<String, String> versions = new HashMap<>();
            for (Models.LegalStatusDoc s : status.documents) versions.put(s.key, s.version);
            final String shownLang = doc.lang;
            IO.execute(() -> {
                try {
                    client.acceptLegal(versions, shownLang);
                    MAIN.post(() -> { busy = false; dialog.dismiss(); if (onAccepted != null) onAccepted.run(); });
                } catch (Exception e) {
                    final boolean changed = e instanceof DriveException && "version_changed".equals(((DriveException) e).code);
                    MAIN.post(() -> {
                        busy = false;
                        error.setText(e.getMessage());
                        error.setVisibility(View.VISIBLE);
                        refresh();
                        // New terms were published while this one was open: reload /me so the new text is what gets accepted.
                        if (changed) { dialog.dismiss(); if (onAccepted != null) onAccepted.run(); }
                    });
                }
            });
        }
    }
}
