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
 * A clip mask as iOS and desktop store it: one shape that limits where the clip is visible, with feathering, an invert
 * switch, a {@link #mode} (how it combines with the masks before it) and its own keyframes. A clip has a LIST of them
 * (clip JSON key {@code "masks"}, at most {@link #MAX_MASKS}); the older single {@code "mask"} + {@code "maskKeys"} layout
 * is still read, as the first mask. See "Clip masks: Android and desktop catch-up spec".
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

    /** How a mask combines with the result of the masks before it (the first mask always just defines the area). */
    public static final String ADD = "add", SUBTRACT = "subtract", INTERSECT = "intersect";

    /** Most masks one clip can have (iOS and desktop use the same limit). */
    public static final int MAX_MASKS = 8;

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
    /** add, subtract or intersect (anything else is add). Ignored for the first mask of a clip. */
    public String mode = ADD;
    /** This mask's own keyframes (panel units, clip-local seconds, see {@link Key}); null / empty = every value is the static one. */
    public List<Key> keys;

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
        mode = other.mode;
        keys = copyKeys(other.keys);
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

    /** add, subtract or intersect (lower case); unknown or null = add. */
    public static String normalizeMode(String mode) {
        if (mode == null) return ADD;
        switch (mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case SUBTRACT: return SUBTRACT;
            case INTERSECT: return INTERSECT;
            default: return ADD;
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
            m.mode = normalizeMode(m.mode);
            m.keys = cleanKeys(m.keys);
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

    /** Keyframes with their holes filled in (no null key, values, easing); null when none are left. */
    private static List<Key> cleanKeys(List<Key> keys) {
        if (keys == null) return null;
        List<Key> clean = new ArrayList<>();
        for (Key k : keys) {
            if (k == null) continue;
            if (k.values == null) k.values = new LinkedHashMap<>();
            if (k.easing == null) k.easing = "LINEAR";
            clean.add(k);
        }
        return clean.isEmpty() ? null : clean;
    }

    /**
     * Reads a clip's masks from its JSON object {@code raw}: the {@code "masks"} array (iOS, desktop), else the older single
     * {@code "mask"} + {@code "maskKeys"} as one mask. What it reads is removed from {@code unknownKeys} (the clip's keys no
     * field maps to, may be null) so it isn't written twice; masks that can't be read are left in {@code unknownKeys}
     * untouched, so they survive a save. The array is read all or nothing, like iOS: one unreadable entry leaves the whole
     * array alone rather than losing a mask. Returns null when the clip has no readable mask.
     */
    public static List<ClipMask> readAllFrom(com.google.gson.JsonObject raw, com.google.gson.JsonObject unknownKeys) {
        if (raw == null) return null;
        JsonElement array = raw.get("masks");
        if (array != null && array.isJsonArray() && array.getAsJsonArray().size() > 0) {
            List<ClipMask> list = new ArrayList<>();
            boolean ok = true;
            for (JsonElement e : array.getAsJsonArray()) {
                ClipMask m = fromJson(e);
                if (m == null) { ok = false; break; }
                if (list.size() < MAX_MASKS) list.add(m);
            }
            if (ok && !list.isEmpty()) {
                if (unknownKeys != null) {
                    unknownKeys.remove("masks");
                    unknownKeys.remove("mask");      // a clip that has both: "masks" wins (iOS, desktop), the single one is stale
                    unknownKeys.remove("maskKeys");
                }
                return list;
            }
            return null; // an unreadable array stays as it is, and the old keys are not touched either
        }
        ClipMask single = fromJson(raw.get("mask"));
        if (single == null) return null;
        if (unknownKeys != null) unknownKeys.remove("mask");
        List<Key> legacy = keysFromJson(raw.get("maskKeys"));
        if (legacy != null && unknownKeys != null) unknownKeys.remove("maskKeys");
        if (legacy != null && !legacy.isEmpty()) single.keys = cleanKeys(legacy);
        List<ClipMask> one = new ArrayList<>();
        one.add(single);
        return one;
    }

    /**
     * Writes a clip's masks into its JSON {@code tree}: {@code "masks"} (the iOS format), and for exactly one adding mask also
     * the older {@code "mask"} + {@code "maskKeys"}, as desktop does, so a build that only knows that layout keeps the mask.
     * Nothing is written for no masks (an unreadable raw value in the tree is left as it is).
     */
    public static void writeAllTo(com.google.gson.JsonObject tree, List<ClipMask> masks) {
        if (masks == null || masks.isEmpty()) return;
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (ClipMask m : masks) {
            if (m == null) continue;
            com.google.gson.JsonObject o = toJson(m).getAsJsonObject();
            if (m.keys == null || m.keys.isEmpty()) o.remove("keys"); // iOS writes keys only when there are some
            array.add(o);
            if (array.size() >= MAX_MASKS) break;
        }
        if (array.size() == 0) return;
        tree.add("masks", array);
        tree.remove("mask");
        tree.remove("maskKeys");
        if (array.size() == 1 && ADD.equals(normalizeMode(masks.get(0).mode))) {
            com.google.gson.JsonObject legacy = array.get(0).getAsJsonObject().deepCopy();
            JsonElement keys = legacy.remove("keys");
            legacy.remove("mode");
            tree.add("mask", legacy);
            if (keys != null && keys.isJsonArray() && keys.getAsJsonArray().size() > 0) tree.add("maskKeys", keys);
        }
    }

    /** A deep copy of a mask list (null stays null). */
    public static List<ClipMask> copyMasks(List<ClipMask> masks) {
        if (masks == null) return null;
        List<ClipMask> copy = new ArrayList<>(masks.size());
        for (ClipMask m : masks) if (m != null) copy.add(new ClipMask(m));
        return copy;
    }

    public static List<Key> copyKeys(List<Key> keys) {
        if (keys == null) return null;
        List<Key> copy = new ArrayList<>(keys.size());
        for (Key k : keys) copy.add(new Key(k));
        return copy;
    }
}
