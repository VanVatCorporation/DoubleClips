package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The searches this phone submitted, newest first, at most {@link #LIMIT}. Kept in SharedPreferences. Main thread only. */
public final class TemplateSearchHistory {

    public static final int LIMIT = 20;
    private static final String PREFS = "template_search";
    private static final String KEY = "history";
    private static final int MAX_QUERY_LENGTH = 100;

    private static TemplateSearchHistory instance;

    private final SharedPreferences prefs;
    private final List<String> items = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized TemplateSearchHistory getInstance(Context context) {
        if (instance == null) {
            instance = new TemplateSearchHistory(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        }
        return instance;
    }

    /** Public so a test can use a store of its own. */
    public TemplateSearchHistory(SharedPreferences prefs) {
        this.prefs = prefs;
        String stored = prefs.getString(KEY, "");
        for (String line : stored.split("\n")) {
            String q = line.trim();
            if (!q.isEmpty() && items.size() < LIMIT) items.add(q);
        }
    }

    public List<String> items() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** Puts a query first (a repeat moves up instead of doubling). */
    public void add(String query) {
        String q = query == null ? "" : query.trim().replace('\n', ' ');
        if (q.isEmpty()) return;
        if (q.length() > MAX_QUERY_LENGTH) q = q.substring(0, MAX_QUERY_LENGTH);
        final String added = q;
        items.removeIf(x -> x.equalsIgnoreCase(added));
        items.add(0, added);
        while (items.size() > LIMIT) items.remove(items.size() - 1);
        changed();
    }

    public void remove(String query) {
        if (items.remove(query)) changed();
    }

    public void clear() {
        if (!items.isEmpty()) {
            items.clear();
            changed();
        }
    }

    public void addListener(Runnable r) { if (r != null) listeners.addIfAbsent(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    private void changed() {
        prefs.edit().putString(KEY, String.join("\n", items)).apply();
        for (Runnable r : listeners) r.run();
    }
}
