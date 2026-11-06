package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen.TemplateData;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Templates the user hid ("Hide this template" in the preview): kept on this phone only, by template id, and left out of the
 * Templates tab and the template search (the Liked / Saved / My lists are the user's own picks and show them anyway). Settings >
 * Hidden content lists them and shows them again. The title and author are kept so the list says what was hidden. Main thread only.
 */
public final class HiddenTemplates {

    /** One hidden template, as shown in Settings. */
    public static final class Entry {
        public String id;
        public String title;
        public String author;
    }

    /** Runs when the list changed; {@code unhid} is true when something was shown again (the feed then reloads to bring it back). */
    public interface Listener {
        void onChanged(boolean unhid);
    }

    private static final String PREFS = "hidden_content";
    private static final String KEY = "hidden_templates";
    private static final Type LIST = new TypeToken<List<Entry>>() { }.getType();

    private static HiddenTemplates instance;

    private final SharedPreferences prefs;
    private final Map<String, Entry> entries = new LinkedHashMap<>();   // by lower-case id, newest last
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static synchronized HiddenTemplates getInstance(Context context) {
        if (instance == null) {
            instance = new HiddenTemplates(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        }
        return instance;
    }

    private HiddenTemplates(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            List<Entry> stored = new Gson().fromJson(prefs.getString(KEY, "[]"), LIST);
            if (stored != null) for (Entry e : stored) if (e != null && !key(e.id).isEmpty()) entries.put(key(e.id), e);
        } catch (RuntimeException ignored) {
            // an unreadable list is an empty list: nothing is hidden, nothing breaks
        }
    }

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    public boolean contains(String templateId) {
        return !key(templateId).isEmpty() && entries.containsKey(key(templateId));
    }

    /** The hidden templates, in the order they were hidden. */
    public List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public void hide(TemplateData data) {
        if (data == null || key(data.getTemplateId()).isEmpty()) return;
        Entry e = new Entry();
        e.id = data.getTemplateId();
        e.title = data.getTemplateTitle();
        e.author = data.getTemplateAuthor();
        entries.put(key(e.id), e);
        changed(false);
    }

    public void unhide(String templateId) {
        if (entries.remove(key(templateId)) != null) changed(true);
    }

    public void unhideAll() {
        if (!entries.isEmpty()) {
            entries.clear();
            changed(true);
        }
    }

    public void addListener(Listener l) { if (l != null) listeners.addIfAbsent(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    private void changed(boolean unhid) {
        prefs.edit().putString(KEY, new Gson().toJson(new ArrayList<>(entries.values()), LIST)).apply();
        for (Listener l : listeners) l.onChanged(unhid);
    }
}
