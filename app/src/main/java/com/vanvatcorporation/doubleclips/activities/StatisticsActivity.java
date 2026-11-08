package com.vanvatcorporation.doubleclips.activities;

import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen.ProjectData;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.helper.AuthorStats;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.TemplateApi;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Profile -> Statistics. Two parts: what is on THIS phone (projects, footage, storage, activity by month; works signed out), and
 * how the account's templates perform, which the SERVER counts (GET /templates/stats): all-time totals, the last 7 / 30 / 90 days
 * against the same number of days before, a per-day chart of other people's activity, and the top templates.
 */
public class StatisticsActivity extends AppCompatActivityImpl {

    private enum Metric { USES, VIEWS, LIKES, SAVES }

    /** What the project list on this phone adds up to (from each project's saved metadata, like the Home list). */
    private static final class LocalStats {
        int count;
        long totalMs, totalBytes;
        ProjectData longest, biggest, latest;
        final int[] months = new int[6];          // the last six months, oldest first
        final String[] monthNames = new String[6];
    }

    private LinearLayout deviceBox, templatesBox;
    private LocalStats local;

    private int days = 30;
    private Metric metric = Metric.USES;
    private AuthorStats stats;
    private String error;
    private boolean loading;
    private int ticket;                // only the newest request may answer (a period picked meanwhile makes older ones stale)
    private String selectedDay;
    private TextView chartCaption;

    private ColorStateList secondaryText, primaryText;
    private Locale locale;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_statistics);

        locale = androidx.core.os.ConfigurationCompat.getLocales(getResources().getConfiguration()).get(0);
        secondaryText = themeText(android.R.attr.textColorSecondary);
        primaryText = themeText(android.R.attr.textColorPrimary);

        findViewById(R.id.statsBackButton).setOnClickListener(v -> finish());
        deviceBox = findViewById(R.id.statsDeviceBox);
        templatesBox = findViewById(R.id.statsTemplatesBox);

        renderDevice();
        renderTemplates();

        // The phone's own numbers: reading every project's properties file is disk work, so off the main thread.
        new Thread(() -> {
            final LocalStats result = loadLocal();
            runOnUiThread(() -> { if (!isFinishing()) { local = result; renderDevice(); } });
        }, "stats-local").start();

        loadRemote();
    }

    // ── On this phone ────────────────────────────────────────────────────────────

    private LocalStats loadLocal() {
        LocalStats s = new LocalStats();
        File dir = new File(IOHelper.CombinePath(IOHelper.getPersistentDataPath(this), "projects"));
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) continue;
                ProjectData p;
                try { p = ProjectData.loadProperties(this, child.getAbsolutePath()); } catch (RuntimeException e) { continue; }
                if (p == null) continue;
                s.count++;
                s.totalMs += Math.max(0, p.getProjectDuration());
                s.totalBytes += Math.max(0, p.getProjectSize());
                if (s.longest == null || p.getProjectDuration() > s.longest.getProjectDuration()) s.longest = p;
                if (s.biggest == null || p.getProjectSize() > s.biggest.getProjectSize()) s.biggest = p;
                if (s.latest == null || p.getProjectTimestamp() > s.latest.getProjectTimestamp()) s.latest = p;

                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(p.getProjectTimestamp());
                Calendar now = Calendar.getInstance();
                int diff = (now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)) - (c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH));
                if (diff >= 0 && diff < 6) s.months[5 - diff]++;
            }
        }
        SimpleDateFormat name = new SimpleDateFormat("MMM", locale);
        for (int i = 0; i < 6; i++) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.add(Calendar.MONTH, i - 5);
            s.monthNames[i] = name.format(c.getTime());
        }
        return s;
    }

    private void renderDevice() {
        deviceBox.removeAllViews();
        deviceBox.addView(sectionTitle(getString(R.string.stats_section_device)));
        if (local == null) return;                      // still reading the projects

        List<View> tiles = new ArrayList<>();
        tiles.add(tile(getString(R.string.stats_projects), String.valueOf(local.count), null, 0, 26));
        tiles.add(tile(getString(R.string.stats_total_footage), DateHelper.convertTimestampToHHMMSSFormat(local.totalMs), null, 0, 26));
        tiles.add(tile(getString(R.string.stats_storage_used), Formatter.formatFileSize(this, local.totalBytes), null, 0, 26));
        tiles.add(tile(getString(R.string.stats_last_edited),
                local.latest == null ? "—" : DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(new Date(local.latest.getProjectTimestamp())),
                local.latest == null ? null : local.latest.getProjectTitle(), 0, 20));
        if (local.longest != null && local.longest.getProjectDuration() > 0)
            tiles.add(tile(getString(R.string.stats_longest_project), DateHelper.convertTimestampToHHMMSSFormat(local.longest.getProjectDuration()),
                    local.longest.getProjectTitle(), 0, 26));
        if (local.biggest != null && local.biggest.getProjectSize() > 0)
            tiles.add(tile(getString(R.string.stats_biggest_project), Formatter.formatFileSize(this, local.biggest.getProjectSize()),
                    local.biggest.getProjectTitle(), 0, 26));
        addGrid(deviceBox, tiles);

        if (local.count > 0) {
            deviceBox.addView(smallTitle(getString(R.string.stats_by_month)));
            deviceBox.addView(monthBars());
        }
    }

    /** The last six months, one bar each: how many projects were last edited in that month. */
    private View monthBars() {
        int peak = 1;
        for (int c : local.months) peak = Math.max(peak, c);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        for (int i = 0; i < 6; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            col.setLayoutParams(colLp);

            TextView count = text(String.valueOf(local.months[i]), 11, false, secondaryText);
            count.setGravity(Gravity.CENTER);
            col.addView(count);

            int h = local.months[i] == 0 ? dp(4) : dp(10) + (int) (dp(100) * (local.months[i] / (float) peak));
            View bar = new View(this);
            bar.setBackground(rounded(attrColor(com.google.android.material.R.attr.colorOnPrimary), dp(6)));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(34), h);
            barLp.topMargin = dp(6);
            barLp.bottomMargin = dp(6);
            col.addView(bar, barLp);

            TextView month = text(local.monthNames[i], 12, false, secondaryText);
            month.setGravity(Gravity.CENTER);
            col.addView(month);
            row.addView(col);
        }
        return row;
    }

    // ── Your templates (counted by the server) ────────────────────────────────────

    private boolean signedIn() {
        try { return AuthRepository.getInstance(this).getCurrentUser() != null; } catch (RuntimeException e) { return false; }
    }

    private void loadRemote() {
        stats = null;
        error = null;
        if (!signedIn()) { loading = false; renderTemplates(); return; }
        final int mine = ++ticket;
        loading = true;
        renderTemplates();
        TemplateApi.fetchAuthorStats(this, days, new TemplateApi.ApiCallback<AuthorStats>() {
            @Override public void onSuccess(AuthorStats value) {
                if (mine != ticket || isFinishing()) return;
                stats = value;
                loading = false;
                selectedDay = null;
                renderTemplates();
            }

            @Override public void onError(String message) {
                if (mine != ticket || isFinishing()) return;
                loading = false;
                error = message;
                renderTemplates();
            }
        });
    }

    private void renderTemplates() {
        templatesBox.removeAllViews();

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = sectionTitle(getString(R.string.stats_section_templates));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        templatesBox.addView(titleRow);

        if (!signedIn()) {
            templatesBox.addView(mutedLine(getString(R.string.stats_sign_in_prompt)));
            return;
        }
        templatesBox.addView(rangeSwitch());

        if (loading) {
            templatesBox.addView(mutedLine(getString(R.string.stats_loading)));
            return;
        }
        if (error != null) {
            templatesBox.addView(mutedLine(getString(R.string.stats_load_failed, error)));
            MaterialButton retry = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            retry.setText(R.string.stats_retry);
            retry.setOnClickListener(v -> loadRemote());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            templatesBox.addView(retry, lp);
            return;
        }
        if (stats == null || stats.totalsOrEmpty().templates == 0) {
            templatesBox.addView(mutedLine(getString(R.string.stats_none_posted)));
            return;
        }

        // all time: the counters on the template cards
        AuthorStats.Totals t = stats.totalsOrEmpty();
        String useRate = t.views > 0 ? getString(R.string.stats_of_views, String.format(Locale.ROOT, "%.1f%%", 100.0 * t.uses / t.views)) : null;
        templatesBox.addView(smallTitle(getString(R.string.stats_all_time)));
        List<View> totals = new ArrayList<>();
        totals.add(tile(getString(R.string.stats_templates), compact(t.templates), null, 0, 26));
        totals.add(tile(getString(R.string.stats_views), compact(t.views), null, 0, 26));
        totals.add(tile(getString(R.string.stats_uses), compact(t.uses), useRate, 0, 26));
        totals.add(tile(getString(R.string.stats_likes), compact(t.likes), null, 0, 26));
        totals.add(tile(getString(R.string.stats_saves), compact(t.saves), null, 0, 26));
        totals.add(tile(getString(R.string.stats_comments), compact(t.comments), null, 0, 26));
        totals.add(tile(getString(R.string.stats_unique_viewers), compact(t.uniqueViewers), getString(R.string.stats_people_opened), 0, 26));
        addGrid(templatesBox, totals);

        // this period against the one before it (other people's activity only)
        AuthorStats.Counts cur = stats.current(), prev = stats.previous();
        templatesBox.addView(smallTitle(getString(R.string.stats_period_header, stats.days, stats.days)));
        List<View> period = new ArrayList<>();
        period.add(deltaTile(getString(R.string.stats_new_viewers), cur.newViewers, prev.newViewers));
        period.add(deltaTile(getString(R.string.stats_uses), cur.uses, prev.uses));
        period.add(deltaTile(getString(R.string.stats_likes), cur.likes, prev.likes));
        period.add(deltaTile(getString(R.string.stats_saves), cur.saves, prev.saves));
        period.add(deltaTile(getString(R.string.stats_comments), cur.comments, prev.comments));
        addGrid(templatesBox, period);

        // the day-by-day chart and the top list follow the metric picked in the switch
        templatesBox.addView(smallTitle(getString(R.string.stats_activity_per_day)));
        templatesBox.addView(metricSwitch());
        templatesBox.addView(dailyChart(stats.seriesOrEmpty()));

        List<AuthorStats.Item> sorted = new ArrayList<>(Arrays.asList(stats.templatesOrEmpty()));
        sorted.sort((a, b) -> Long.compare(itemValue(b), itemValue(a)));
        long top = sorted.isEmpty() ? 0 : itemValue(sorted.get(0));
        templatesBox.addView(smallTitle(getString(R.string.stats_top_templates)));
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            templatesBox.addView(topRow(i + 1, sorted.get(i), itemValue(sorted.get(i)), top));
        }
    }

    private long dayValue(AuthorStats.Day d) {
        switch (metric) {
            case VIEWS: return d.newViewers;
            case LIKES: return d.likes;
            case SAVES: return d.saves;
            case USES:
            default: return d.uses;
        }
    }

    private long itemValue(AuthorStats.Item d) {
        switch (metric) {
            case VIEWS: return d.views;
            case LIKES: return d.likes;
            case SAVES: return d.saves;
            case USES:
            default: return d.uses;
        }
    }

    private String metricUnit() {
        switch (metric) {
            case VIEWS: return getString(R.string.stats_unit_new_viewers);
            case LIKES: return getString(R.string.stats_unit_likes);
            case SAVES: return getString(R.string.stats_unit_saves);
            case USES:
            default: return getString(R.string.stats_unit_uses);
        }
    }

    /** 7 / 30 / 90 days: asks the server again. */
    private View rangeSwitch() {
        final int[] options = {7, 30, 90};
        MaterialButtonToggleGroup group = toggleGroup();
        final int[] ids = new int[options.length];
        for (int i = 0; i < options.length; i++) {
            MaterialButton b = toggleButton(getString(R.string.stats_days, options[i]));
            ids[i] = b.getId();
            group.addView(b);
            if (options[i] == days) group.check(b.getId());
        }
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId && options[i] != days) { days = options[i]; loadRemote(); return; }
            }
        });
        return group;
    }

    private View metricSwitch() {
        final Metric[] order = {Metric.USES, Metric.VIEWS, Metric.LIKES, Metric.SAVES};
        final int[] labels = {R.string.stats_uses, R.string.stats_views, R.string.stats_likes, R.string.stats_saves};
        MaterialButtonToggleGroup group = toggleGroup();
        final int[] ids = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            MaterialButton b = toggleButton(getString(labels[i]));
            ids[i] = b.getId();
            group.addView(b);
            if (order[i] == metric) group.check(b.getId());
        }
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId && order[i] != metric) { metric = order[i]; renderTemplates(); return; }
            }
        });
        return group;
    }

    /** One bar per local day of the current period. Tap a bar to read its day and number above the chart. */
    private View dailyChart(AuthorStats.Day[] series) {
        long peak = 1, total = 0;
        for (AuthorStats.Day d : series) { peak = Math.max(peak, dayValue(d)); total += dayValue(d); }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = dp(8);
        box.setLayoutParams(boxLp);

        chartCaption = text(total + " " + metricUnit(), 13, true, primaryText);
        box.addView(chartCaption);

        LinearLayout bars = new LinearLayout(this);
        bars.setOrientation(LinearLayout.HORIZONTAL);
        bars.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(124));
        barsLp.topMargin = dp(8);
        box.addView(bars, barsLp);

        final int accent = attrColor(com.google.android.material.R.attr.colorOnPrimary);
        final int idle = attrColor(com.google.android.material.R.attr.colorSurfaceContainerHigh);
        final int picked = attrColor(com.google.android.material.R.attr.colorSecondary);
        final int gap = series.length > 45 ? 1 : 3;
        final List<View> views = new ArrayList<>();
        for (final AuthorStats.Day d : series) {
            final long v = dayValue(d);
            int h = v == 0 ? dp(3) : dp(8) + (int) (dp(112) * (v / (float) peak));
            View bar = new View(this);
            bar.setBackground(rounded(v == 0 ? idle : accent, dp(2)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
            lp.setMargins(dp(gap) / 2, 0, dp(gap) / 2, 0);
            bar.setOnClickListener(x -> {
                boolean same = d.date != null && d.date.equals(selectedDay);
                selectedDay = same ? null : d.date;
                chartCaption.setText(same ? (total(series) + " " + metricUnit()) : (d.date + ": " + v + " " + metricUnit()));
                for (int i = 0; i < views.size(); i++) {
                    AuthorStats.Day di = series[i];
                    boolean on = di.date != null && di.date.equals(selectedDay);
                    views.get(i).setBackground(rounded(on ? picked : (dayValue(di) == 0 ? idle : accent), dp(2)));
                }
            });
            views.add(bar);
            bars.addView(bar, lp);
        }

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.addView(text(series.length == 0 ? "" : series[0].date, 11, false, secondaryText), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView last = text(series.length == 0 ? "" : series[series.length - 1].date, 11, false, secondaryText);
        last.setGravity(Gravity.END);
        labels.addView(last, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelsLp.topMargin = dp(6);
        box.addView(labels, labelsLp);
        return box;
    }

    private long total(AuthorStats.Day[] series) {
        long t = 0;
        for (AuthorStats.Day d : series) t += dayValue(d);
        return t;
    }

    private View topRow(int rank, AuthorStats.Item item, long value, long top) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);
        row.setLayoutParams(rowLp);

        TextView rankText = text(String.valueOf(rank), 13, true, secondaryText);
        row.addView(rankText, new LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        String name = item.title == null ? "" : item.title.trim();
        TextView title = text(name.isEmpty() ? getString(R.string.stats_untitled) : name, 14, false, primaryText);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        middle.addView(title);

        // the bar: two weighted views, so no measuring is needed
        int fill = top <= 0 ? 0 : (int) Math.round(1000.0 * Math.min(1.0, value / (double) top));
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(rounded(attrColor(com.google.android.material.R.attr.colorSurfaceContainerHigh), dp(4)));
        View fillView = new View(this);
        fillView.setBackground(rounded(attrColor(com.google.android.material.R.attr.colorOnPrimary), dp(4)));
        track.addView(fillView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fill));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1000 - fill));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        trackLp.topMargin = dp(6);
        middle.addView(track, trackLp);
        row.addView(middle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView count = text(compact(value), 14, true, primaryText);
        count.setGravity(Gravity.END);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT);
        countLp.setMarginStart(dp(12));
        row.addView(count, countLp);
        return row;
    }

    /** A tile with this period's number and a green / red change against the previous period. */
    private View deltaTile(String label, long current, long previous) {
        String text;
        int color = 0;
        if (current == previous) {
            text = getString(R.string.stats_no_change);
        } else if (previous == 0) {
            text = "▲ " + getString(R.string.stats_new);
            color = Color.parseColor("#34C759");
        } else {
            double pct = 100.0 * (current - previous) / previous;
            text = (pct > 0 ? "▲ " : "▼ ") + String.format(Locale.ROOT, "%.0f%%", Math.abs(pct));
            color = pct > 0 ? Color.parseColor("#34C759") : Color.parseColor("#FF3B30");
        }
        return tile(label, compact(current), text, color, 26);
    }

    // ── Small pieces ─────────────────────────────────────────────────────────────

    /** A tile from item_stat_tile.xml. {@code subColor} 0 = the normal secondary colour (and a plain weight). */
    private View tile(String label, String value, @Nullable String sub, int subColor, int valueSp) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_stat_tile, null, false);
        TextView valueView = v.findViewById(R.id.statValue);
        valueView.setText(value);
        valueView.setTextSize(valueSp);
        ((TextView) v.findViewById(R.id.statLabel)).setText(label);
        TextView subView = v.findViewById(R.id.statSub);
        if (sub != null && !sub.isEmpty()) {
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
            if (subColor != 0) {
                subView.setTextColor(subColor);
                subView.setTypeface(subView.getTypeface(), android.graphics.Typeface.BOLD);
            }
        }
        return v;
    }

    /** Tiles two to a row (a lone last tile keeps its half width). */
    private void addGrid(LinearLayout parent, List<View> tiles) {
        for (int i = 0; i < tiles.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(12);
            row.setLayoutParams(rowLp);

            View a = tiles.get(i);
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            aLp.setMarginEnd(dp(6));
            row.addView(a, aLp);

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            bLp.setMarginStart(dp(6));
            if (i + 1 < tiles.size()) {
                row.addView(tiles.get(i + 1), bLp);
            } else {
                row.addView(new View(this), bLp);
            }
            parent.addView(row);
        }
    }

    private MaterialButtonToggleGroup toggleGroup() {
        MaterialButtonToggleGroup g = new MaterialButtonToggleGroup(this);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        g.setLayoutParams(lp);
        return g;
    }

    private MaterialButton toggleButton(String label) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setId(View.generateViewId());
        b.setText(label);
        b.setAllCaps(false);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 20, true, primaryText);
        return t;
    }

    private TextView smallTitle(String s) {
        TextView t = text(s, 13, true, secondaryText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView mutedLine(String s) {
        TextView t = text(s, 14, false, secondaryText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, int sp, boolean bold, ColorStateList color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(color);
        return t;
    }

    private ColorStateList themeText(int attr) {
        TypedArray a = obtainStyledAttributes(new int[]{attr});
        try {
            ColorStateList c = a.getColorStateList(0);
            return c != null ? c : ColorStateList.valueOf(Color.GRAY);
        } finally {
            a.recycle();
        }
    }

    private int attrColor(int attr) {
        return MaterialColors.getColor(this, attr, Color.GRAY);
    }

    private GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 950 -> "950", 1234 -> "1.2K", 3_400_000 -> "3.4M". */
    private static String compact(long n) {
        if (n < 1000) return Long.toString(n);
        double v;
        String suffix;
        if (n < 1_000_000) { v = n / 1000.0; suffix = "K"; }
        else if (n < 1_000_000_000L) { v = n / 1_000_000.0; suffix = "M"; }
        else { v = n / 1_000_000_000.0; suffix = "B"; }
        String s = String.format(Locale.ROOT, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + suffix;
    }
}
