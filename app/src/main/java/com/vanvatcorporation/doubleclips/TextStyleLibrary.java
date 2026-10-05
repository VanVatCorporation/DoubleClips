package com.vanvatcorporation.doubleclips;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The styles a user saved ("My styles"), kept in the app's own storage so they are available in every
 * project. Built-ins are not stored here (see {@link TextStyle#builtIns()}). Applying a style copies its
 * values into the clip, so deleting a style never changes a project.
 */
public final class TextStyleLibrary {

    private static final String FILE_NAME = "text-styles.json";
    private static final Type LIST_TYPE = new TypeToken<List<TextStyle>>() { }.getType();

    private TextStyleLibrary() { }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static synchronized List<TextStyle> load(Context context) {
        File f = file(context);
        if (!f.isFile()) return new ArrayList<>();
        try (Reader reader = new FileReader(f)) {
            List<TextStyle> list = new Gson().fromJson(reader, LIST_TYPE);
            return list == null ? new ArrayList<>() : list;
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>(); // a damaged file just means no saved styles
        }
    }

    private static void write(Context context, List<TextStyle> styles) throws IOException {
        File f = file(context);
        File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
        try (Writer writer = new FileWriter(tmp)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(styles, LIST_TYPE, writer);
        }
        if (f.exists() && !f.delete()) throw new IOException("Couldn't replace the saved styles.");
        if (!tmp.renameTo(f)) throw new IOException("Couldn't save the styles.");
    }

    /** Saves a copy of {@code look} under a new id and the given name. */
    public static synchronized TextStyle save(Context context, TextStyle look, String name) throws IOException {
        List<TextStyle> all = load(context);
        TextStyle saved = new TextStyle(look);
        saved.id = "user-" + System.currentTimeMillis();
        saved.name = name == null || name.trim().isEmpty() ? "My style" : name.trim();
        saved.author = null;
        all.add(saved);
        write(context, all);
        return saved;
    }

    public static synchronized void delete(Context context, String id) throws IOException {
        List<TextStyle> all = load(context);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (id != null && id.equals(all.get(i).id)) all.remove(i);
        }
        write(context, all);
    }
}
