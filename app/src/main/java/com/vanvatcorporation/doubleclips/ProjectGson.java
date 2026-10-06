package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Gson every project file (timeline, clip / track / keyframe import and export, clipboard, text styles)
 * goes through. It behaves like a plain Gson, except that any class implementing {@link UnknownKeysHolder}
 * keeps the keys it doesn't know across load and save, so a project edited on another platform (or by a
 * newer build) is not silently stripped when this build saves it.
 * <p>
 * Plain Java + Gson, no Android types.
 */
public final class ProjectGson {

    private static final TypeAdapterFactory FACTORY = new UnknownKeysFactory();

    /** Reads everything the classes have fields for (annotated with @Expose or not, as before). */
    private static final Gson LOAD = new GsonBuilder().registerTypeAdapterFactory(FACTORY).create();
    /** Writes only @Expose fields, like {@code GsonHelper.createExposeOnlyGson()} always did. */
    private static final Gson SAVE = new GsonBuilder().excludeFieldsWithoutExposeAnnotation()
            .registerTypeAdapterFactory(FACTORY).create();
    private static final Gson SAVE_PRETTY = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting()
            .registerTypeAdapterFactory(FACTORY).create();

    private ProjectGson() { }

    public static Gson forLoad() { return LOAD; }

    public static Gson forSave() { return SAVE; }

    public static Gson forSavePretty() { return SAVE_PRETTY; }

    // ----------------------------------------------------------------------------------------

    private static final Map<Class<?>, Set<String>> KNOWN = new ConcurrentHashMap<>();

    /** Every JSON name some field of {@code type} (or a superclass) reads, @Expose or not, transient excluded. */
    private static Set<String> knownNames(Class<?> type) {
        Set<String> cached = KNOWN.get(type);
        if (cached != null) return cached;
        Set<String> names = new HashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic()) continue;
                SerializedName sn = f.getAnnotation(SerializedName.class);
                if (sn != null) {
                    names.add(sn.value());
                    for (String alt : sn.alternate()) names.add(alt);
                } else {
                    names.add(f.getName());
                }
            }
        }
        KNOWN.put(type, names);
        return names;
    }

    private static final class UnknownKeysFactory implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            final Class<? super T> raw = type.getRawType();
            if (!UnknownKeysHolder.class.isAssignableFrom(raw)) return null;

            final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            final TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
            final Set<String> known = knownNames(raw);

            return new TypeAdapter<T>() {
                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    if (value == null) {
                        out.nullValue();
                        return;
                    }
                    JsonElement tree = delegate.toJsonTree(value);
                    if (tree.isJsonObject()) {
                        JsonObject obj = tree.getAsJsonObject();
                        UnknownKeysHolder holder = (UnknownKeysHolder) value;
                        JsonObject extras = holder.getUnknownKeys();
                        if (extras != null) {
                            for (Map.Entry<String, JsonElement> e : extras.entrySet()) {
                                if (!obj.has(e.getKey())) obj.add(e.getKey(), e.getValue().deepCopy());
                            }
                        }
                        holder.onSaving(obj);
                    }
                    elements.write(out, tree);
                }

                @Override
                public T read(JsonReader in) throws IOException {
                    JsonElement tree = elements.read(in);
                    if (tree == null || tree.isJsonNull()) return null;
                    T value = delegate.fromJsonTree(tree);
                    if (value != null && tree.isJsonObject()) {
                        JsonObject obj = tree.getAsJsonObject();
                        UnknownKeysHolder holder = (UnknownKeysHolder) value;
                        JsonObject extras = new JsonObject();
                        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                            if (!known.contains(e.getKey())) extras.add(e.getKey(), e.getValue().deepCopy());
                        }
                        holder.setUnknownKeys(extras.size() == 0 ? null : extras);
                        holder.onLoaded(obj);
                    }
                    return value;
                }
            };
        }
    }
}
