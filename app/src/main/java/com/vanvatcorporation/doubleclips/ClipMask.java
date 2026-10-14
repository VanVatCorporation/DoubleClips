package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A clip mask as iOS stores it (clip JSON keys {@code "mask"} and {@code "maskKeys"}): one shape that limits where the
 * clip is visible, with feathering, an invert switch and optional keyframes. See "Clip masks: Android and desktop
 * catch-up spec".
 * <p>
 * The static values here are FRACTIONS (centre as a fraction of the box, size as a fraction of the box's shorter side).
 * The keyframes' values are in the PANEL's units instead (x 50 = 0.5): see {@link Key}. {@link ClipMaskResolver} turns
 * both into the values for one moment.
 * <p>
 * Plain Java (no Android types), so the desktop port can share it. Read and written through {@link #fromJson} /
 * {@link #toJson}, never by the project loader: a mask that can't be read must never cost the clip.
 */
public class ClipMask implements Serializable {

    public static final String LINEAR = "linear", MIRROR = "mirror", CIRCLE = "circle",
            RECTANGLE = "rectangle", HEART = "heart", STAR = "star";

    /** linear, mirror, circle, rectangle, heart or star (anything else is a circle). */
    public String shape = CIRCLE;
    /** Shape centre as a fraction of the box width / height. */
    public float centerX = 0.5f, centerY = 0.5f;
    /** Size as a fraction of the box's SHORTER side; null = the shape's default size (see {@link #defaultWidth}). */
    public Float width, height;
    /** Degrees, clockwise (y points down). */
    public float rotation;
    /** Soft edge, fraction of the shorter side. */
    public float feather;
    /** Rectangle corners: 0 square, 1 pill. */
    public float roundness;
    /** Show everything outside the shape instead. */
    public boolean invert;

    public ClipMask() { }

    public ClipMask(ClipMask other) {
        shape = other.shape;
        centerX = other.centerX;
        centerY = other.centerY;
        width = other.width;
        height = other.height;
        rotation = other.rotation;
        feather = other.feather;
        roundness = other.roundness;
        invert = other.invert;
    }

    /** A new mask of this shape with its default size, centred, no rotation or feather. */
    public static ClipMask of(String shape) {
        ClipMask m = new ClipMask();
        m.shape = normalizeShape(shape);
        m.width = defaultWidth(m.shape);
        m.height = defaultHeight(m.shape);
        return m;
    }

    /** One of the six known shape names (lower case); unknown or null = circle. */
    public static String normalizeShape(String shape) {
        if (shape == null) return CIRCLE;
        switch (shape.trim().toLowerCase(java.util.Locale.ROOT)) {
            case LINEAR: return LINEAR;
            case MIRROR: return MIRROR;
            case RECTANGLE: return RECTANGLE;
            case HEART: return HEART;
            case STAR: return STAR;
            default: return CIRCLE;
        }
    }

    /** Default size when a shape is picked: linear 0 x 0 (no extent), mirror 0 x 0.35 (height = band thickness), circle .6 x .6, rectangle .8 x .5, heart and star .7 x .7. */
    public static float defaultWidth(String shape) {
        switch (normalizeShape(shape)) {
            case LINEAR: case MIRROR: return 0f;
            case RECTANGLE: return 0.8f;
            case HEART: case STAR: return 0.7f;
            default: return 0.6f;
        }
    }

    public static float defaultHeight(String shape) {
        switch (normalizeShape(shape)) {
            case LINEAR: return 0f;
            case MIRROR: return 0.35f;
            case RECTANGLE: return 0.5f;
            case HEART: case STAR: return 0.7f;
            default: return 0.6f;
        }
    }

    /**
     * Picking another shape keeps centre, rotation, feather and invert and takes the new shape's default size
     * (roundness goes back to 0: it only exists for rectangles).
     */
    public void switchShape(String newShape) {
        shape = normalizeShape(newShape);
        width = defaultWidth(shape);
        height = defaultHeight(shape);
        roundness = 0f;
    }

    /** One mask keyframe: only the parameters it animates, in the PANEL's units (see ClipMaskResolver for the mapping). */
    public static class Key implements Serializable {
        /** Seconds from the clip start (clip-local, like the clip's own keyframes). */
        public float time;
        /** A name of the clip keyframes' easing list (LINEAR, EASE_IN_OUT_SINE, NONE...). */
        public String easing = "LINEAR";
        /** x, y, width, height, rotation, feather, roundness. */
        public Map<String, Float> values = new LinkedHashMap<>();

        public Key() { }

        public Key(Key other) {
            time = other.time;
            easing = other.easing;
            values = other.values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(other.values);
        }
    }

    // ---- JSON ----------------------------------------------------------------------------------

    private static final Gson GSON = new Gson();
    private static final Type KEY_LIST = new TypeToken<List<Key>>() { }.getType();

    /** The mask in a clip's JSON, or null when it isn't a readable mask object (the caller then keeps the raw JSON untouched). */
    public static ClipMask fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        try {
            ClipMask m = GSON.fromJson(element, ClipMask.class);
            if (m == null) return null;
            m.shape = normalizeShape(m.shape);
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static JsonElement toJson(ClipMask mask) {
        return GSON.toJsonTree(mask);
    }

    /** The keyframes in a clip's JSON, or null when the array can't be read (the caller then keeps the raw JSON). */
    public static List<Key> keysFromJson(JsonElement element) {
        if (element == null || !element.isJsonArray()) return null;
        try {
            List<Key> keys = GSON.fromJson(element, KEY_LIST);
            if (keys == null) return null;
            List<Key> clean = new ArrayList<>();
            for (Key k : keys) {
                if (k == null) continue;
                if (k.values == null) k.values = new LinkedHashMap<>();
                if (k.easing == null) k.easing = "LINEAR";
                clean.add(k);
            }
            return clean;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static JsonElement keysToJson(List<Key> keys) {
        return GSON.toJsonTree(keys, KEY_LIST);
    }

    /** What {@link #readFrom} found in a clip's JSON. */
    public static final class Loaded {
        public final ClipMask mask;
        /** Null when the clip has no (readable) keyframes. */
        public final List<Key> keys;

        Loaded(ClipMask mask, List<Key> keys) {
            this.mask = mask;
            this.keys = keys;
        }
    }

    /**
     * Reads a clip's mask (keys "mask", "maskKeys") from its JSON object {@code raw}. What it reads is removed from
     * {@code unknownKeys} (the clip's keys no field maps to, may be null) so it isn't written twice; a mask or keyframe list
     * that can't be read is left in {@code unknownKeys} untouched, so it survives a save. Returns null when the clip has no
     * readable mask (keyframes are only read together with a mask).
     */
    public static Loaded readFrom(com.google.gson.JsonObject raw, com.google.gson.JsonObject unknownKeys) {
        ClipMask mask = fromJson(raw.get("mask"));
        if (mask == null) return null;
        if (unknownKeys != null) unknownKeys.remove("mask");
        List<Key> keys = keysFromJson(raw.get("maskKeys"));
        if (keys != null && unknownKeys != null) unknownKeys.remove("maskKeys");
        return new Loaded(mask, keys == null || keys.isEmpty() ? null : keys);
    }

    /** Writes the mask into a clip's JSON {@code tree}: "maskKeys" is omitted when there are none. Nothing is written without a mask. */
    public static void writeTo(com.google.gson.JsonObject tree, ClipMask mask, List<Key> keys) {
        if (mask == null) return;
        tree.add("mask", toJson(mask));
        if (keys != null && !keys.isEmpty()) tree.add("maskKeys", keysToJson(keys));
    }

    public static List<Key> copyKeys(List<Key> keys) {
        if (keys == null) return null;
        List<Key> copy = new ArrayList<>(keys.size());
        for (Key k : keys) copy.add(new Key(k));
        return copy;
    }
}
