package com.vanvatcorporation.doubleclips.assets;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.vanvatcorporation.doubleclips.helper.TrendingSearch;
import com.vanvatcorporation.drivekit.AssetsClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Assets hot searches ("Trending") and the "You may like" list, shared by the search bar's ticker and the search window
 * (iOS: AssetSearchHints; the Templates tab has the same in TemplateSearchHints). Loaded once and again after
 * {@link #REFRESH_AFTER_MS} (or when forced). A failure keeps what is already shown, so a server without the search migration just
 * means empty lists and the phone-side suggestions. Use on the main thread; listeners run there too.
 */
public final class AssetSearchHints {

    public static final long REFRESH_AFTER_MS = 5 * 60 * 1000;

    private static AssetSearchHints instance;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<TrendingSearch> trending = new ArrayList<>();
    private List<String> forYou = new ArrayList<>();
    private long loadedAt;
    private boolean loading;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized AssetSearchHints getInstance() {
        if (instance == null) instance = new AssetSearchHints();
        return instance;
    }

    private AssetSearchHints() { }

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
        final AssetsClient client = AssetsSession.get(context);
        IO.execute(() -> {
            List<TrendingSearch> hot = null;
            List<String> mine = null;
            try {
                List<TrendingSearch> mapped = new ArrayList<>();
                for (AssetsClient.HotSearch h : client.trending(10)) mapped.add(new TrendingSearch(h.text, h.count, h.hot));
                hot = mapped;
            } catch (IOException | RuntimeException ignored) {
                // keeps the old list
            }
            try {
                mine = client.suggest("", 6);
            } catch (IOException | RuntimeException ignored) {
                // keeps the old list
            }
            final List<TrendingSearch> newHot = hot;
            final List<String> newMine = mine;
            ui.post(() -> {
                loading = false;
                if (newHot != null) trending = newHot;
                if (newMine != null) forYou = newMine;
                if (newHot != null || newMine != null) {
                    loadedAt = System.currentTimeMillis();
                    for (Runnable r : listeners) r.run();
                }
            });
        });
    }
}
