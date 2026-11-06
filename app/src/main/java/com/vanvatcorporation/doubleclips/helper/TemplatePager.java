package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;

import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen.TemplateData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pages through one {@link TemplateFeedSource} (feed / search / liked / saved) with the server's cursor.
 * Shared by the Templates tab, the Profile lists and the preview pager, so each of them only has to
 * react to the four callbacks. Main thread only.
 *
 *  - A new {@link #start} bumps a generation: an answer for an older source (the user kept typing in the search box,
 *    pulled to refresh...) is dropped instead of landing in the new list.
 *  - Templates already delivered are never delivered again (a feed session never repeats, but a search can
 *    overlap a freshly posted template).
 */
public class TemplatePager {
    public interface Listener {
        /** A new list begins: clear whatever is shown. */
        void onReset();
        /** More templates (never ones delivered before). hasMore = the server has another page. */
        void onPage(List<TemplateData> items, boolean hasMore);
        void onLoading(boolean loading);
        void onError(String message);
    }

    private static final int MAX_EMPTY_RETRIES = 3;

    private final Context context;
    private final Listener listener;
    private TemplateFeedSource source = TemplateFeedSource.feed();
    private String cursor;
    private boolean hasMore = true;
    private boolean loading;
    private int generation;
    private final Set<String> delivered = new HashSet<>();

    public TemplatePager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public TemplateFeedSource getSource() { return source; }
    public String getCursor() { return cursor; }
    public boolean hasMore() { return hasMore; }
    public boolean isLoading() { return loading; }

    /** Begins a fresh list (first page) of the given source. */
    public void start(TemplateFeedSource newSource) {
        generation++;
        source = newSource;
        cursor = null;
        hasMore = true;
        loading = false;
        delivered.clear();
        listener.onReset();
        loadNext();
    }

    /** Continues a list another screen already started (the preview pager): `have` are the ids it already shows. */
    public void resume(TemplateFeedSource continuing, String nextCursor, Collection<String> have) {
        generation++;
        source = continuing;
        cursor = nextCursor;
        hasMore = nextCursor != null && !nextCursor.isEmpty();
        loading = false;
        delivered.clear();
        for (String id : have) delivered.add(id.toLowerCase());
    }

    public void loadNext() {
        loadNext(0);
    }

    private void loadNext(int emptyRetries) {
        if (loading || !hasMore) return;
        loading = true;
        listener.onLoading(true);
        final int gen = generation;

        TemplateApi.fetchPage(context, source, cursor, TemplateApi.PAGE_SIZE, new TemplateApi.ApiCallback<TemplateApi.Page>() {
            @Override public void onSuccess(TemplateApi.Page page) {
                if (gen != generation) return;           // an older source: drop
                loading = false;
                cursor = page.nextCursor;
                hasMore = page.hasMore && cursor != null && !cursor.isEmpty();

                // Templates the user hid stay out of the discovery lists (feed, search); Liked / Saved / My lists are their own picks
                boolean discovery = source.kind == TemplateFeedSource.Kind.FEED || source.kind == TemplateFeedSource.Kind.SEARCH;
                HiddenTemplates hidden = HiddenTemplates.getInstance(context);
                List<TemplateData> fresh = new ArrayList<>();
                for (TemplateData d : page.items) {
                    if (delivered.add(d.getTemplateId().toLowerCase()) && !(discovery && hidden.contains(d.getTemplateId()))) fresh.add(d);
                }
                if (fresh.isEmpty() && hasMore && emptyRetries < MAX_EMPTY_RETRIES) {
                    loadNext(emptyRetries + 1);          // a whole page of repeats: ask for the next one
                    return;
                }
                listener.onLoading(false);
                listener.onPage(fresh, hasMore);
            }

            @Override public void onError(String message) {
                if (gen != generation) return;
                loading = false;
                listener.onLoading(false);
                listener.onError(message);
            }
        });
    }
}
