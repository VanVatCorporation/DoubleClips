package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen.TemplateData;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.User;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The Templates backend (app.vanvatcorp.com/doubleclips/api, backend v2), the only place the app talks to it.
 * Contract: docs/doubleclips-template-api-v2.md.
 *
 * ONE state cache: every like / bookmark / count the app learns (from a page, a toggle or a view) lands in {@link #STATES},
 * and every screen reads it back through {@link #overlay}. That is what keeps the grid, the preview pager and the Profile
 * lists showing the same numbers, and the listeners below repaint a screen that is underneath another one.
 *
 * All callbacks run on the main thread.
 */
public final class TemplateApi {
    private TemplateApi() { }

    public static final String BASE = "https://app.vanvatcorp.com/doubleclips/api";
    public static final int PAGE_SIZE = 20;

    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface ApiCallback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    public interface StateListener {
        /** A template's like / bookmark / counters changed somewhere in the app. */
        void onTemplateStateChanged(String templateId);
    }

    /** One page of the server's list envelope. */
    public static class Page {
        public String returnResult;
        public TemplateData[] items;
        public String nextCursor;
        public boolean hasMore;
        public boolean personalized;
        public String algorithm;
    }

    // ---- who is asking ---------------------------------------------------------------------------------------------

    /** The signed-in username, or null. (Self-asserted for now: the server has no token for the native apps yet.) */
    public static String viewer(Context context) {
        try {
            User user = AuthRepository.getInstance(context).getCurrentUser();
            return user == null ? null : user.getUsername();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A random id kept on this install: lets the server dedupe views and avoid repeats for a signed-out user. */
    public static String deviceId(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("template_api", Context.MODE_PRIVATE);
        String id = prefs.getString("deviceId", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    // ---- the shared state ------------------------------------------------------------------------------------------

    /** What the server last said about one template, for this viewer. */
    public static final class State {
        public boolean isLiked, isBookmarked;
        public int viewCount, useCount, heartCount, bookmarkCount, commentCount;

        public void applyTo(TemplateData data) {
            data.isLiked = isLiked;
            data.isBookmarked = isBookmarked;
            data.setStats(viewCount, useCount, heartCount, bookmarkCount, commentCount);
        }
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<StateListener> LISTENERS = new CopyOnWriteArrayList<>();
    /** The account the cached flags belong to; a different one (sign in / out) must not inherit them. */
    private static volatile String stateOwner = null;

    private static void ownerCheck(Context context) {
        String now = viewer(context);
        String owner = now == null ? "" : now.toLowerCase();
        if (stateOwner == null || !stateOwner.equals(owner)) {
            STATES.clear();
            stateOwner = owner;
        }
    }

    public static void addStateListener(StateListener l) { LISTENERS.addIfAbsent(l); }
    public static void removeStateListener(StateListener l) { LISTENERS.remove(l); }

    private static void notifyChanged(String templateId) {
        MAIN.post(() -> { for (StateListener l : LISTENERS) l.onTemplateStateChanged(templateId); });
    }

    private static String key(String id) { return id == null ? "" : id.toLowerCase(); }

    /** Brings a template object up to date with the newest thing the app knows about it. */
    public static void overlay(Context context, TemplateData data) {
        ownerCheck(context);
        State s = STATES.get(key(data.getTemplateId()));
        if (s != null) s.applyTo(data);
    }

    /** Anything the server sends about a template is the newest truth: remember it. */
    private static void remember(TemplateData d) {
        State s = new State();
        s.isLiked = d.isLiked;
        s.isBookmarked = d.isBookmarked;
        s.viewCount = d.getViewCount();
        s.useCount = d.getUseCount();
        s.heartCount = d.getHeartCount();
        s.bookmarkCount = d.getBookmarkCount();
        s.commentCount = d.getCommentCount();
        STATES.put(key(d.getTemplateId()), s);
    }

    // ---- lists ----------------------------------------------------------------------------------------------------

    public static void fetchPage(Context context, TemplateFeedSource source, String cursor, int limit, ApiCallback<Page> callback) {
        final Context app = context.getApplicationContext();
        ownerCheck(app);
        String path;
        switch (source.kind) {
            case LIKED: path = "/templates/liked"; break;
            case BOOKMARKED: path = "/templates/bookmarked"; break;
            case SEARCH:
            case MINE: path = "/templates"; break;
            case FEED:
            default: path = "/templates/feed"; break;
        }
        HttpUrl.Builder url = HttpUrl.get(BASE + path).newBuilder().addQueryParameter("limit", String.valueOf(limit));
        if (cursor != null && !cursor.isEmpty()) url.addQueryParameter("cursor", cursor);
        if (source.kind == TemplateFeedSource.Kind.SEARCH) url.addQueryParameter("q", source.query);
        String viewer = viewer(app);
        if (viewer != null) url.addQueryParameter("viewer", viewer);   // query, not a header: OkHttp rejects non-ASCII header values
        if (source.kind == TemplateFeedSource.Kind.MINE) {
            // "My templates" = the templates whose author is this account (the same endpoint as browse, filtered by author).
            if (viewer == null) { MAIN.post(() -> callback.onError("Login required")); return; }
            url.addQueryParameter("author", viewer);
            url.addQueryParameter("sort", "new");
        }

        send(app, new Request.Builder().url(url.build()).get(), body -> {
            Page page = GSON.fromJson(body, Page.class);
            if (page == null || page.items == null) page = emptyPage();
            for (TemplateData d : page.items) remember(d);
            return page;
        }, callback);
    }

    private static Page emptyPage() {
        Page p = new Page();
        p.items = new TemplateData[0];
        return p;
    }

    // ---- like / bookmark ------------------------------------------------------------------------------------------

    private static final Map<String, Integer> LATEST = new ConcurrentHashMap<>();

    private static synchronized int nextTicket(String key) {
        Integer c = LATEST.get(key);
        int n = c == null ? 1 : c + 1;
        LATEST.put(key, n);
        return n;
    }

    /** Sets (not flips) the like: a retry or a double tap cannot undo itself. Needs a signed-in user. */
    public static void setLike(Context context, String templateId, boolean liked, ApiCallback<State> callback) {
        setInteraction(context, "/toggle-like", "liked", "isLiked", templateId, liked, callback);
    }

    public static void setBookmark(Context context, String templateId, boolean bookmarked, ApiCallback<State> callback) {
        setInteraction(context, "/toggle-bookmark", "bookmarked", "isBookmarked", templateId, bookmarked, callback);
    }

    private static void setInteraction(Context context, String path, String flagName, String answerFlag,
                                       String templateId, boolean value, ApiCallback<State> callback) {
        final Context app = context.getApplicationContext();
        ownerCheck(app);
        String viewer = viewer(app);
        if (viewer == null) { MAIN.post(() -> callback.onError("Login required")); return; }

        // Only the newest tap per template + kind reports back, so a slow older answer cannot overwrite a newer one.
        final String latestKey = path + "|" + key(templateId);
        final int ticket = nextTicket(latestKey);

        JsonObject json = new JsonObject();
        json.addProperty("username", viewer);
        json.addProperty("templateId", templateId);
        json.addProperty(flagName, value);

        send(app, new Request.Builder().url(BASE + path).post(RequestBody.create(json.toString(), JSON)), body -> {
            JsonObject o = new JsonParser().parse(body).getAsJsonObject();
            State s = copyOf(STATES.get(key(templateId)));
            if (o.has(answerFlag)) {
                if ("isLiked".equals(answerFlag)) s.isLiked = o.get("isLiked").getAsBoolean();
                else s.isBookmarked = o.get("isBookmarked").getAsBoolean();
            }
            readCounts(o, s);
            STATES.put(key(templateId), s);
            notifyChanged(templateId);
            return s;
        }, new ApiCallback<State>() {
            @Override public void onSuccess(State v) {
                Integer current = LATEST.get(latestKey);
                if (current != null && current == ticket) callback.onSuccess(v);
            }
            @Override public void onError(String message) {
                Integer current = LATEST.get(latestKey);
                if (current != null && current == ticket) callback.onError(message);
            }
        });
    }

    // ---- view / use -----------------------------------------------------------------------------------------------

    /** The template came on screen. The server counts it once per viewer per 30 minutes. */
    public static void postView(Context context, String templateId) {
        postCounter(context, "/increment-view", templateId, "newViewCount", true);
    }

    /** The template was actually used (exported). */
    public static void postUse(Context context, String templateId) {
        postCounter(context, "/increment-use", templateId, "newUseCount", false);
    }

    private static void postCounter(Context context, String path, String templateId, String answerField, boolean isView) {
        final Context app = context.getApplicationContext();
        ownerCheck(app);
        JsonObject json = new JsonObject();
        json.addProperty("templateId", templateId);
        String viewer = viewer(app);
        if (viewer != null) json.addProperty("username", viewer);

        send(app, new Request.Builder().url(BASE + path).post(RequestBody.create(json.toString(), JSON)), body -> {
            JsonObject o = new JsonParser().parse(body).getAsJsonObject();
            if (o.has(answerField)) {
                State s = copyOf(STATES.get(key(templateId)));
                int n = o.get(answerField).getAsInt();
                if (isView) s.viewCount = n; else s.useCount = n;
                STATES.put(key(templateId), s);
                notifyChanged(templateId);
            }
            return Boolean.TRUE;
        }, new ApiCallback<Boolean>() {   // fire and forget: a lost counter must never bother the user
            @Override public void onSuccess(Boolean v) { }
            @Override public void onError(String message) { }
        });
    }

    // ---- search: hot searches, suggestions, the search log -----------------------------------------------------------

    /** The hot searches ("Trending"), best first. Rows are strings or {query, count?, hot?}; see {@link TemplateSearchParse}. */
    public static void fetchTrending(Context context, int limit, ApiCallback<java.util.List<TrendingSearch>> callback) {
        final Context app = context.getApplicationContext();
        ownerCheck(app);
        HttpUrl.Builder url = HttpUrl.get(BASE + "/templates/search/trending").newBuilder()
                .addQueryParameter("limit", String.valueOf(limit));
        String viewer = viewer(app);
        if (viewer != null) url.addQueryParameter("viewer", viewer);
        send(app, new Request.Builder().url(url.build()).get(),
                body -> TemplateSearchParse.trending(GSON.fromJson(body, Object.class)), callback);
    }

    /** Suggested searches for what was typed; an empty prefix asks for "you may like" (personal, by the viewer). */
    public static void fetchSuggestions(Context context, String prefix, int limit, ApiCallback<java.util.List<String>> callback) {
        final Context app = context.getApplicationContext();
        ownerCheck(app);
        HttpUrl.Builder url = HttpUrl.get(BASE + "/templates/search/suggest").newBuilder()
                .addQueryParameter("q", prefix == null ? "" : prefix)
                .addQueryParameter("limit", String.valueOf(limit));
        String viewer = viewer(app);
        if (viewer != null) url.addQueryParameter("viewer", viewer);
        send(app, new Request.Builder().url(url.build()).get(),
                body -> TemplateSearchParse.suggestions(GSON.fromJson(body, Object.class)), callback);
    }

    /** Tells the server a search was submitted, so it can rank "Trending". Best effort: nobody waits for it. */
    public static void logSearch(Context context, String query) {
        if (query == null || query.trim().isEmpty()) return;
        final Context app = context.getApplicationContext();
        ownerCheck(app);
        JsonObject json = new JsonObject();
        json.addProperty("query", query.trim());
        String viewer = viewer(app);
        if (viewer != null) json.addProperty("username", viewer);
        send(app, new Request.Builder().url(BASE + "/templates/search/log").post(RequestBody.create(json.toString(), JSON)),
                body -> Boolean.TRUE, new ApiCallback<Boolean>() {
                    @Override public void onSuccess(Boolean v) { }
                    @Override public void onError(String message) { }
                });
    }

    // ---- plumbing -------------------------------------------------------------------------------------------------

    private interface Parser<T> { T parse(String body) throws Exception; }

    private static State copyOf(State from) {
        State s = new State();
        if (from != null) {
            s.isLiked = from.isLiked; s.isBookmarked = from.isBookmarked;
            s.viewCount = from.viewCount; s.useCount = from.useCount; s.heartCount = from.heartCount;
            s.bookmarkCount = from.bookmarkCount; s.commentCount = from.commentCount;
        }
        return s;
    }

    private static void readCounts(JsonObject o, State s) {
        if (o.has("viewCount")) s.viewCount = o.get("viewCount").getAsInt();
        if (o.has("useCount")) s.useCount = o.get("useCount").getAsInt();
        if (o.has("heartCount")) s.heartCount = o.get("heartCount").getAsInt();
        if (o.has("bookmarkCount")) s.bookmarkCount = o.get("bookmarkCount").getAsInt();
        if (o.has("commentCount")) s.commentCount = o.get("commentCount").getAsInt();
    }

    private static <T> void send(Context app, Request.Builder request, Parser<T> parser, ApiCallback<T> callback) {
        request.header("X-Device-Id", deviceId(app));
        CLIENT.newCall(request.build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                MAIN.post(() -> callback.onError("No connection"));
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    String body = r.body() == null ? "" : r.body().string();
                    if (!r.isSuccessful()) {
                        String message = "Error " + r.code();
                        try {
                            JsonObject o = new JsonParser().parse(body).getAsJsonObject();
                            if (o.has("returnResult")) message = o.get("returnResult").getAsString();
                        } catch (RuntimeException ignored) { /* not JSON */ }
                        final String m = message;
                        MAIN.post(() -> callback.onError(m));
                        return;
                    }
                    final T value = parser.parse(body);
                    MAIN.post(() -> callback.onSuccess(value));
                } catch (Exception e) {
                    MAIN.post(() -> callback.onError("Bad response"));
                }
            }
        });
    }
}
