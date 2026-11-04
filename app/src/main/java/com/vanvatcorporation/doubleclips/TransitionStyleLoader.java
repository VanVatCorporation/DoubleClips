package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transition styles as JSON, the same format iOS reads ({@code animations/transitions/<id>.json}):
 *
 * <pre>
 * {
 *   "schema": 1,
 *   "id": "glitchblur",              // [a-z0-9_-]{1,64}: the style key a transition stores
 *   "name": "Glitch Blur",
 *   "description": "...",            // optional, 256 characters at most
 *   "author": "...",                 // optional
 *   "defaultDuration": 1.1,          // optional, seconds, (0, 30]; default 0.5
 *   "mode": "cut",                   // optional: cut (default) | layers (both drawn, B over A) | mask (B revealed through "mask")
 *   "cut": 0.76,                     // where A is replaced by B, 0.05 .. 0.95 of the window (default 0.5); cut mode only
 *   "mask": { "shape": "wipe", "reveal": { ... }, "angle": 180 },  // modes mask and crop, shapes in EffectFilterSpecs
 *   "referenceFrames": 100,          // only needed by "frames" knots
 *   "from": { "channels": { ... } }, // the look of A, curves over the WHOLE window (p = 0..1); optional in mask mode
 *   "to":   { "channels": { ... } }  // the look of B, same p; optional in mask mode
 * }
 * </pre>
 *
 * A side's channels are the clip-animation ones ({@link ClipAnimation.Channel}) with the same three curve kinds,
 * so nothing in a file can execute anything. Validation is the clip-animation one: strict JSON, bounded size,
 * unknown keys / channels rejected, values inside the channel's range. A bad file throws
 * {@link ClipAnimationLoader.FormatException} and registers nothing.
 *
 * Plain Java (no Android classes); {@link ClipAnimationAssets} finds the bundled files.
 */
public final class TransitionStyleLoader {

    /** Folder inside the bundled {@code animations} folder. */
    public static final String ASSET_FOLDER = "transitions";

    private static final int MAX_NAME_CHARS = 64;
    private static final int MAX_NOTE_CHARS = 256;

    private static final Map<String, TransitionStyle> REGISTRY = new ConcurrentHashMap<>();

    private TransitionStyleLoader() {}

    // ---- lookup (thread-safe: the render threads read it) -------------------------------

    /** The data-driven style stored under this key (see {@link #normalize}), or null when the key is a built-in shader style. */
    public static TransitionStyle get(String style) {
        String id = normalize(style);
        return id.isEmpty() ? null : REGISTRY.get(id);
    }

    public static boolean isDataDriven(String style) { return get(style) != null; }

    /** All registered styles, sorted by id. */
    public static List<TransitionStyle> list() {
        List<TransitionStyle> all = new ArrayList<>(REGISTRY.values());
        Collections.sort(all, new Comparator<TransitionStyle>() {
            @Override public int compare(TransitionStyle a, TransitionStyle b) { return a.getId().compareTo(b.getId()); }
        });
        return all;
    }

    /** The key form of a style string: trimmed, lower case ("none" / empty stay as they are). Same rule as iOS. */
    public static String normalize(String style) {
        return style == null ? "" : style.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ---- registering -------------------------------------------------------------------

    /** Parses, validates and registers one file (replacing a style with the same id). */
    public static TransitionStyle register(String json, String source) throws ClipAnimationLoader.FormatException {
        TransitionStyle style = parse(json, source);
        REGISTRY.put(style.getId(), style);
        return style;
    }

    /** Parses and validates without registering. */
    public static TransitionStyle parse(String json, String source) throws ClipAnimationLoader.FormatException {
        try {
            return parseInternal(json);
        } catch (ClipAnimationLoader.FormatException e) {
            if (source == null || source.isEmpty()) throw e;
            throw new ClipAnimationLoader.FormatException(source + ": " + e.getMessage());
        }
    }

    static void clearForTests() { REGISTRY.clear(); }

    // ---- parse + validate --------------------------------------------------------------

    private static final String[] TOP_KEYS = {
            "schema", "id", "name", "description", "author", "defaultDuration", "cut", "mode", "referenceFrames", "from", "to", "mask"
    };

    private static TransitionStyle parseInternal(String json) throws ClipAnimationLoader.FormatException {
        typeCheckLength(json);
        Map<String, Object> top = ClipAnimationLoader.parseObject(json);
        ClipAnimationLoader.allowKeys(top, "$", TOP_KEYS);

        double schema = ClipAnimationLoader.reqNumber(top, "schema", "$");
        if (schema != ClipAnimationLoader.SUPPORTED_SCHEMA) {
            throw new ClipAnimationLoader.FormatException("$.schema: unsupported schema " + fmt(schema)
                    + " (this app reads " + ClipAnimationLoader.SUPPORTED_SCHEMA + ")");
        }
        String id = ClipAnimationLoader.reqString(top, "id", "$", 64);
        if (!id.matches("[a-z0-9_-]{1,64}") || "none".equals(id)) {
            throw new ClipAnimationLoader.FormatException("$.id: must be 1-64 characters of a-z 0-9 _ - and not 'none'");
        }
        String name = ClipAnimationLoader.optString(top, "name", "$", MAX_NAME_CHARS);
        if (name == null || name.isEmpty()) name = id;
        String description = ClipAnimationLoader.optString(top, "description", "$", MAX_NOTE_CHARS);
        String author = ClipAnimationLoader.optString(top, "author", "$", MAX_NOTE_CHARS);

        double duration = ClipAnimationLoader.DEFAULT_DURATION_FALLBACK;
        if (top.containsKey("defaultDuration")) {
            duration = ClipAnimationLoader.reqNumber(top, "defaultDuration", "$");
            if (!(duration > 0.0 && duration <= ClipAnimationLoader.MAX_DEFAULT_DURATION)) {
                throw new ClipAnimationLoader.FormatException("$.defaultDuration: must be > 0 and <= "
                        + (int) ClipAnimationLoader.MAX_DEFAULT_DURATION + " seconds");
            }
        }
        double cut = 0.5;
        if (top.containsKey("cut")) {
            cut = ClipAnimationLoader.reqNumber(top, "cut", "$");
            if (!(cut >= 0.05 && cut <= 0.95)) {
                throw new ClipAnimationLoader.FormatException("$.cut: must be between 0.05 and 0.95");
            }
        }
        TransitionStyle.Mode mode = TransitionStyle.Mode.CUT;
        if (top.containsKey("mode")) {
            String word = ClipAnimationLoader.reqString(top, "mode", "$", 16);
            mode = TransitionStyle.Mode.fromJson(word);
            if (mode == null) throw new ClipAnimationLoader.FormatException("$.mode: must be \"cut\", \"layers\", \"mask\" or \"crop\"");
        }
        int referenceFrames = 0;
        if (top.containsKey("referenceFrames")) {
            double rf = ClipAnimationLoader.reqNumber(top, "referenceFrames", "$");
            if (rf != Math.rint(rf) || rf < 1 || rf > 10000) {
                throw new ClipAnimationLoader.FormatException("$.referenceFrames: must be a whole number 1-10000");
            }
            referenceFrames = (int) rf;
        }

        EffectFilterOps.Filter mask = null;
        if (top.containsKey("mask")) {
            if (mode != TransitionStyle.Mode.MASK && mode != TransitionStyle.Mode.CROP) {
                throw new ClipAnimationLoader.FormatException("$.mask: only allowed with \"mode\": \"mask\" or \"crop\"");
            }
            mask = EffectFilterOps.parseMask(top.get("mask"), "$.mask");
        } else if (mode == TransitionStyle.Mode.MASK || mode == TransitionStyle.Mode.CROP) {
            throw new ClipAnimationLoader.FormatException("$.mask: required when \"mode\" is \"mask\" or \"crop\"");
        }

        boolean sidesOptional = mode == TransitionStyle.Mode.MASK || mode == TransitionStyle.Mode.CROP;
        ClipAnimation from = side(top, "from", ClipAnimation.Direction.OUT, id, name, (float) duration, referenceFrames, sidesOptional);
        ClipAnimation to = side(top, "to", ClipAnimation.Direction.IN, id, name, (float) duration, referenceFrames, sidesOptional);
        return new TransitionStyle(id, name, description, author, (float) duration, cut, from, to, mode, mask);
    }

    private static ClipAnimation side(Map<String, Object> top, String key, ClipAnimation.Direction direction,
                                      String id, String name, float duration, int referenceFrames, boolean optional)
            throws ClipAnimationLoader.FormatException {
        String path = "$." + key;
        if (!top.containsKey(key)) {
            // Mask styles may leave a side out: that clip is drawn as it is (no channels = neutral).
            if (optional) {
                return new ClipAnimation(id + "." + key, name, direction, duration,
                        new EnumMap<ClipAnimation.Channel, ClipAnimation.Curve>(ClipAnimation.Channel.class), false, referenceFrames);
            }
            throw new ClipAnimationLoader.FormatException(path + ": required object");
        }
        Map<String, Object> object = ClipAnimationLoader.asObject(top.get(key), path);
        ClipAnimationLoader.allowKeys(object, path, "channels");
        Map<String, Object> channelObjects = ClipAnimationLoader.asObject(object.get("channels"), path + ".channels");
        if (channelObjects.isEmpty()) {
            throw new ClipAnimationLoader.FormatException(path + ".channels: at least one channel is required");
        }
        EnumMap<ClipAnimation.Channel, ClipAnimation.Curve> curves = new EnumMap<>(ClipAnimation.Channel.class);
        for (Map.Entry<String, Object> e : channelObjects.entrySet()) {
            String channelPath = path + ".channels." + e.getKey();
            ClipAnimation.Channel channel = ClipAnimation.Channel.fromJson(e.getKey());
            if (channel == null) throw new ClipAnimationLoader.FormatException(channelPath + ": unknown channel");
            curves.put(channel, ClipAnimationLoader.parseCurve(
                    ClipAnimationLoader.asObject(e.getValue(), channelPath), channel, referenceFrames, channelPath));
        }
        return new ClipAnimation(id + "." + key, name, direction, duration, curves, false, referenceFrames);
    }

    private static void typeCheckLength(String json) throws ClipAnimationLoader.FormatException {
        if (json == null) throw new ClipAnimationLoader.FormatException("no data");
        if (json.length() > ClipAnimationLoader.MAX_JSON_CHARS) {
            throw new ClipAnimationLoader.FormatException("file is larger than " + ClipAnimationLoader.MAX_JSON_CHARS + " characters");
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString((long) d) : Double.toString(d);
    }
}
