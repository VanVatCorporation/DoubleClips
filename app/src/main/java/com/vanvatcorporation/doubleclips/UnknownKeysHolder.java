package com.vanvatcorporation.doubleclips;

import com.google.gson.JsonObject;

/**
 * A project-file object that must survive a load + save even when it contains keys this build
 * doesn't know (written by the iOS or desktop port, or by a newer Android build).
 * <p>
 * {@link ProjectGson} stores every unknown key of the JSON object in {@link #getUnknownKeys()} when it
 * reads the object and writes them back when it saves it. Implementers only need a
 * {@code private transient JsonObject} field behind the two accessors. Plain Java (Gson only), so the
 * desktop port can share it.
 */
public interface UnknownKeysHolder {

    /** Keys read from the file that no field of this class maps to, or null when there were none. */
    JsonObject getUnknownKeys();

    void setUnknownKeys(JsonObject keys);

    /**
     * Called right after the object was read; {@code raw} is its complete JSON object. The place for
     * migrations (an older key name, or another platform's layout) into the real fields.
     */
    default void onLoaded(JsonObject raw) { }

    /**
     * Called while the object is written, after the unknown keys were merged in. {@code tree} is the complete
     * JSON object about to be written: add computed keys, or remove stale ones, here.
     */
    default void onSaving(JsonObject tree) { }

    /** For copy constructors: the copy keeps the original's unknown keys. */
    default void copyUnknownKeysFrom(UnknownKeysHolder other) {
        JsonObject keys = other == null ? null : other.getUnknownKeys();
        setUnknownKeys(keys == null ? null : keys.deepCopy());
    }
}
