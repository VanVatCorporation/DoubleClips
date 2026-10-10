package com.vanvatcorporation.doubleclips;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Plain Java (no Android types): the JSON side of "a template is a timeline", as iOS defines it
 * (TemplateTimeline.swift / TemplatePosting.swift).
 *
 * The server may hand the timeline over in several shapes: the project's own timeline JSON ({"tracks":[...]}), a wrapper
 * {"format":1,"settings":{canvas},"timeline":{...}}, a JSON STRING holding either (a JSON column often comes back that
 * way), or an ARRAY holding that string / object. {@link #parse} unwraps all of them.
 */
public final class TemplateTimelineJson {
    private TemplateTimelineJson() {}

    /** The canvas a template was made for. Any field may be absent (0 / null). */
    public static final class Canvas {
        public final int videoWidth, videoHeight, frameRate;
        public final Boolean isStretchToFull;
        Canvas(int w, int h, int fps, Boolean stretch) { videoWidth = w; videoHeight = h; frameRate = fps; isStretchToFull = stretch; }
    }

    public static final class Parsed {
        /** The timeline object as JSON text ({"tracks":[...], "duration":...}), ready for ProjectGson. */
        public final String timelineJson;
        /** Null when the file carried no canvas (a bare timeline, or a wrapper without settings). */
        public final Canvas canvas;
        Parsed(String timelineJson, Canvas canvas) { this.timelineJson = timelineJson; this.canvas = canvas; }
    }

    public static final class NotATimelineException extends Exception {
        public NotATimelineException(String message) { super(message); }
    }

    private static final int MAX_UNWRAP_DEPTH = 3;

    /** Unwraps a JSON string / one-element array down to the JSON object that is inside. Returns the input when nothing to unwrap. */
    static JsonElement unwrap(JsonElement e, int depth) {
        if (e == null || depth >= MAX_UNWRAP_DEPTH) return e;
        if (e.isJsonPrimitive() && ((JsonPrimitive) e).isString()) {
            try { return unwrap(JsonParser.parseString(e.getAsString()), depth + 1); }
            catch (RuntimeException notJson) { return e; }
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() == 0) return e;
            return unwrap(a.get(0), depth + 1);
        }
        return e;
    }

    public static Parsed parse(String raw) throws NotATimelineException {
        if (raw == null || raw.trim().isEmpty()) throw new NotATimelineException("(empty)");
        JsonElement root;
        try { root = JsonParser.parseString(raw); }
        catch (RuntimeException e) { throw new NotATimelineException(head(raw)); }
        root = unwrap(root, 0);
        if (root == null || !root.isJsonObject()) throw new NotATimelineException(head(raw));
        JsonObject obj = root.getAsJsonObject();

        if (obj.has("timeline") && obj.get("timeline").isJsonObject()) {
            JsonObject timeline = obj.getAsJsonObject("timeline");
            if (!timeline.has("tracks")) throw new NotATimelineException(head(raw));
            return new Parsed(timeline.toString(), canvasOf(obj.get("settings")));
        }
        if (obj.has("tracks") && obj.get("tracks").isJsonArray()) return new Parsed(obj.toString(), null);
        throw new NotATimelineException(head(raw));
    }

    private static Canvas canvasOf(JsonElement settings) {
        if (settings == null || !settings.isJsonObject()) return null;
        JsonObject s = settings.getAsJsonObject();
        return new Canvas(intOf(s, "videoWidth"), intOf(s, "videoHeight"), intOf(s, "frameRate"), boolOf(s, "isStretchToFull"));
    }

    private static int intOf(JsonObject o, String key) {
        try { JsonElement v = o.get(key); return v == null || v.isJsonNull() ? 0 : Math.max(0, (int) Math.round(v.getAsDouble())); }
        catch (RuntimeException e) { return 0; }
    }

    private static Boolean boolOf(JsonObject o, String key) {
        try { JsonElement v = o.get(key); return v == null || v.isJsonNull() ? null : (Boolean) v.getAsBoolean(); }
        catch (RuntimeException e) { return null; }
    }

    /** The first characters of what came back, for an error message ("an error page, null, a route that doesn't exist"). */
    static String head(String raw) {
        String t = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return t.isEmpty() ? "(empty)" : (t.length() > 120 ? t.substring(0, 120) : t);
    }

    /** {"format":1,"settings":{canvas},"timeline":{...}}: what is posted. {@code timelineJson} must be a JSON object. */
    public static String wrap(String timelineJson, int videoWidth, int videoHeight, int frameRate, boolean isStretchToFull) {
        JsonObject settings = new JsonObject();
        settings.addProperty("videoWidth", videoWidth);
        settings.addProperty("videoHeight", videoHeight);
        settings.addProperty("frameRate", frameRate);
        settings.addProperty("isStretchToFull", isStretchToFull);
        JsonObject doc = new JsonObject();
        doc.addProperty("format", 1);
        doc.add("settings", settings);
        doc.add("timeline", JsonParser.parseString(timelineJson));
        return doc.toString();
    }

    // ------------------------------------------------------------------------------------------------ file names

    /**
     * A name the server keeps as it is: it strips a leading "123-" (it takes that for a timestamp) and its multipart parser
     * mangles non-ASCII names, so both are rewritten. Same rules as iOS {@code TemplatePackager.safeName}.
     */
    public static String safeName(String name) {
        String base = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        base = base.replace("đ", "d").replace("Đ", "D");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == ' ' || c == '(' || c == ')';
            sb.append(ok ? c : '_');
        }
        base = sb.toString();
        if (base.matches("^\\d+-.*")) base = "f_" + base;
        return base.isEmpty() ? "file" : base;
    }

    /** {@link #safeName} made unique among {@code used} (case-insensitive): "a.mp4", "a (1).mp4", "a (2).mp4"... The result is added to {@code used}. */
    public static String uniqueSafeName(String original, Set<String> used) {
        String safe = safeName(original);
        String candidate = safe;
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        int counter = 1;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = stem + " (" + counter + ")" + ext;
            counter++;
        }
        return candidate;
    }

    public static Set<String> newNameSet() { return new HashSet<>(); }
}
