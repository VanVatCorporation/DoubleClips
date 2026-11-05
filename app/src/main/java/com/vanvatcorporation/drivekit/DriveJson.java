package com.vanvatcorporation.drivekit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Forgiving readers for the Drive API's JSON: a missing or wrongly typed field is a default, never a crash. */
final class DriveJson {
    private DriveJson() { }

    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    static long lng(JsonObject o, String key, long fallback) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return false;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        JsonArray array = e.getAsJsonArray();
        for (JsonElement item : array) {
            try {
                String s = item.getAsString();
                if (s != null && !s.trim().isEmpty()) out.add(s.trim());
            } catch (RuntimeException ignored) { /* not a string: skip */ }
        }
        return out;
    }

    /** A JSON string literal (quotes and escapes included). */
    static String quote(String s) {
        return new com.google.gson.JsonPrimitive(s == null ? "" : s).toString();
    }
}
