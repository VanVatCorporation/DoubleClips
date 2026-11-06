package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "Hide assets from this person" (desktop and iOS: BlockedAuthors): kept on this phone only, by display name, and applied when the
 * Assets lists are drawn. Settings > Hidden content lists the names (as the poster wrote them) and shows them again. Main thread only.
 */
public final class BlockedAuthors {

    private static final String PREFS = "hidden_content";
    private static final String KEY = "blocked_authors";
    private static final Type LIST = new TypeToken<List<String>>() { }.getType();

    private static BlockedAuthors instance;

    private final SharedPreferences prefs;
    /** lower case (how names are compared) -> the name as it was hidden (how it is shown). */
    private final TreeMap<String, String> names = new TreeMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized BlockedAuthors getInstance(Context context) {
        if (instance == null) {
            instance = new BlockedAuthors(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        }
        return instance;
    }

    private BlockedAuthors(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            List<String> stored = new Gson().fromJson(prefs.getString(KEY, "[]"), LIST);
            if (stored != null) for (String s : stored) if (!key(s).isEmpty()) names.put(key(s), s.trim());
        } catch (RuntimeException ignored) {
            // an unreadable list is an empty list: nobody is hidden, nothing breaks
        }
    }

    private static String key(String owner) {
        return owner == null ? "" : owner.trim().toLowerCase(Locale.ROOT);
    }

    public boolean contains(String owner) {
        return !key(owner).isEmpty() && names.containsKey(key(owner));
    }

    /** The hidden names as they were written, in alphabetical order. */
    public List<String> names() {
        return new ArrayList<>(names.values());
    }

    public void block(String owner) {
        if (key(owner).isEmpty()) return;
        names.put(key(owner), owner.trim());
        changed();
    }

    public void unblock(String owner) {
        if (names.remove(key(owner)) != null) changed();
    }

    /** Shows everyone again. */
    public void unblockAll() {
        if (!names.isEmpty()) {
            names.clear();
            changed();
        }
    }

    /** Someone was hidden or shown again: the lists on screen redraw. Runs on the thread that changed it (the main thread). */
    public void addListener(Runnable r) { if (r != null) listeners.addIfAbsent(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    private void changed() {
        prefs.edit().putString(KEY, new Gson().toJson(new ArrayList<>(names.values()), LIST)).apply();
        for (Runnable r : listeners) r.run();
    }
}
