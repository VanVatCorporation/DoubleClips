package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The hot searches ("Trending") and the personal "You may like" list, shared by the search bar's ticker and the search
 * window: loaded once and again after {@link #REFRESH_AFTER_MS} (or when forced). A failure keeps what is already shown.
 * Main thread only (the API calls back there).
 */
public final class TemplateSearchHints {

    public static final long REFRESH_AFTER_MS = 5 * 60 * 1000;

    private static TemplateSearchHints instance;

    private List<TrendingSearch> trending = new ArrayList<>();
    private List<String> forYou = new ArrayList<>();
    private long loadedAt = 0;
    private boolean loading = false;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized TemplateSearchHints getInstance() {
        if (instance == null) instance = new TemplateSearchHints();
        return instance;
    }

    private TemplateSearchHints() { }

    public List<TrendingSearch> trending() { return Collections.unmodifiableList(trending); }

    public List<String> forYou() { return Collections.unmodifiableList(forYou); }

    /** The hot-search texts, best first (what the ticker rotates through). */
    public List<String> trendingTexts() {
        List<String> out = new ArrayList<>();
        for (TrendingSearch t : trending) out.add(t.text);
        return out;
    }

    public void addListener(Runnable r) { if (r != null) listeners.addIfAbsent(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    public void refresh(Context context, boolean force) {
        if (loading) return;
        if (!force && loadedAt != 0 && System.currentTimeMillis() - loadedAt < REFRESH_AFTER_MS) return;
        loading = true;
        final int[] pending = {2};
        final boolean[] gotSomething = {false};
        final Runnable oneDone = () -> {
            if (--pending[0] > 0) return;
            loading = false;
            if (gotSomething[0]) {
                loadedAt = System.currentTimeMillis();
                for (Runnable r : listeners) r.run();
            }
        };
        TemplateApi.fetchTrending(context, 10, new TemplateApi.ApiCallback<List<TrendingSearch>>() {
            @Override public void onSuccess(List<TrendingSearch> list) { trending = list; gotSomething[0] = true; oneDone.run(); }
            @Override public void onError(String message) { oneDone.run(); }
        });
        TemplateApi.fetchSuggestions(context, "", 6, new TemplateApi.ApiCallback<List<String>>() {
            @Override public void onSuccess(List<String> list) { forYou = list; gotSomething[0] = true; oneDone.run(); }
            @Override public void onError(String message) { oneDone.run(); }
        });
    }
}
