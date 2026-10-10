package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Effect styles as JSON, the same format iOS reads ({@code animations/effects/<id>.json}):
 *
 * <pre>
 * {
 *   "schema": 1,
 *   "id": "heartbeat",                // [a-z0-9_-]{1,64}: the style key an effect clip stores
 *   "name": "Heartbeat",
 *   "description": "...",             // optional, 256 characters at most
 *   "author": "...",                  // optional (default: EffectStyle.BUILTIN_AUTHOR)
 *   "intensity": { "label": "Strength", "min": 0.2, "max": 3 },  // optional; absent = no strength slider
 *   "period": 1.0,                    // optional, seconds, (0, 60]: the curves loop, p = (t mod period) / period
 *   "span": 30,                       // optional, seconds, (0, 3600]: p = t / span, held at 1; not with "period"
 *   "referenceFrames": 60,            // only needed by "frames" knots
 *   "channels": { ... }               // the clip-animation channels and curve kinds
 * }
 * </pre>
 *
 * Without "period" and "span" the curves run once across the effect clip. The strength slider pushes every channel away
 * from its neutral value ({@link ClipAnimationFrame#scaledDeviation}). Validation is the clip-animation one: strict
 * JSON, bounded size, unknown keys / channels rejected, values inside the channel's range; a bad file throws
 * {@link ClipAnimationLoader.FormatException} and registers nothing. Nothing in a file can execute anything.
 *
 * Plain Java (no Android classes); {@link ClipAnimationAssets} finds the bundled files.
 */
public final class EffectStyleLoader {

    /** Folder inside the bundled {@code animations} folder. */
    public static final String ASSET_FOLDER = "effects";

    private static final int MAX_NAME_CHARS = 64;
    private static final int MAX_NOTE_CHARS = 256;
    private static final float DEFAULT_MIN = 0.2f, DEFAULT_MAX = 3f;

    private static final Map<String, EffectStyle> REGISTRY = new ConcurrentHashMap<>();

    private EffectStyleLoader() {}

    // ---- lookup (thread-safe: the render threads read it) -------------------------------

    /** The data-driven style stored under this key (see {@link #normalize}), or null. */
    public static EffectStyle get(String style) {
        String id = normalize(style);
        return id.isEmpty() ? null : REGISTRY.get(id);
    }

    public static boolean isDataDriven(String style) { return get(style) != null; }

    /** All registered styles, sorted by name. */
    public static List<EffectStyle> list() {
        List<EffectStyle> all = new ArrayList<>(REGISTRY.values());
        Collections.sort(all, new Comparator<EffectStyle>() {
            @Override public int compare(EffectStyle a, EffectStyle b) {
                int c = a.getName().compareToIgnoreCase(b.getName());
                return c != 0 ? c : a.getId().compareTo(b.getId());
            }
        });
        return all;
    }

    /** The key form of a style string: trimmed, lower case. Same rule as iOS. */
    public static String normalize(String style) {
        return style == null ? "" : style.trim().toLowerCase(Locale.ROOT);
    }

    // ---- registering -------------------------------------------------------------------

    /** Parses, validates and registers one file (replacing a style with the same id). */
    public static EffectStyle register(String json, String source) throws ClipAnimationLoader.FormatException {
        EffectStyle style = parse(json, source);
        REGISTRY.put(style.getId(), style);
        return style;
    }

    /** Parses and validates without registering. */
    public static EffectStyle parse(String json, String source) throws ClipAnimationLoader.FormatException {
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
            "schema", "id", "name", "description", "author", "intensity", "period", "span", "referenceFrames", "channels"
    };

    private static EffectStyle parseInternal(String json) throws ClipAnimationLoader.FormatException {
        if (json == null) throw new ClipAnimationLoader.FormatException("no data");
        if (json.length() > ClipAnimationLoader.MAX_JSON_CHARS) {
            throw new ClipAnimationLoader.FormatException("file is larger than " + ClipAnimationLoader.MAX_JSON_CHARS + " characters");
        }
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
        String author = ClipAnimationLoader.optString(top, "author", "$", MAX_NAME_CHARS);
        if (author == null || author.isEmpty()) author = EffectStyle.BUILTIN_AUTHOR;

        String label = null;
        float lo = DEFAULT_MIN, hi = DEFAULT_MAX;
        if (top.containsKey("intensity")) {
            Map<String, Object> object = ClipAnimationLoader.asObject(top.get("intensity"), "$.intensity");
            ClipAnimationLoader.allowKeys(object, "$.intensity", "label", "min", "max");
            label = ClipAnimationLoader.optString(object, "label", "$.intensity", 32);
            if (label == null || label.isEmpty()) label = "Strength";
            double dlo = DEFAULT_MIN, dhi = DEFAULT_MAX;
            if (object.containsKey("min")) dlo = ClipAnimationLoader.reqNumber(object, "min", "$.intensity");
            if (object.containsKey("max")) dhi = ClipAnimationLoader.reqNumber(object, "max", "$.intensity");
            if (!(dlo > 0 && dhi > dlo && dhi <= 10)) {
                throw new ClipAnimationLoader.FormatException("$.intensity: needs 0 < min < max <= 10");
            }
            lo = (float) dlo;
            hi = (float) dhi;
        }

        double period = 0;
        if (top.containsKey("period")) {
            period = ClipAnimationLoader.reqNumber(top, "period", "$");
            if (!(period > 0 && period <= 60)) {
                throw new ClipAnimationLoader.FormatException("$.period: must be > 0 and <= 60 seconds");
            }
        }
        double span = 0;
        if (top.containsKey("span")) {
            span = ClipAnimationLoader.reqNumber(top, "span", "$");
            if (!(span > 0 && span <= 3600)) {
                throw new ClipAnimationLoader.FormatException("$.span: must be > 0 and <= 3600 seconds");
            }
            if (period != 0) {
                throw new ClipAnimationLoader.FormatException("$.span: can't be combined with \"period\"");
            }
        }
        int referenceFrames = 0;
        if (top.containsKey("referenceFrames")) {
            double rf = ClipAnimationLoader.reqNumber(top, "referenceFrames", "$");
            if (rf != Math.rint(rf) || rf < 1 || rf > 10000) {
                throw new ClipAnimationLoader.FormatException("$.referenceFrames: must be a whole number 1-10000");
            }
            referenceFrames = (int) rf;
        }

        Map<String, Object> channelObjects = ClipAnimationLoader.asObject(top.get("channels"), "$.channels");
        if (channelObjects.isEmpty()) {
            throw new ClipAnimationLoader.FormatException("$.channels: at least one channel is required");
        }
        EnumMap<ClipAnimation.Channel, ClipAnimation.Curve> curves = new EnumMap<>(ClipAnimation.Channel.class);
        for (Map.Entry<String, Object> e : channelObjects.entrySet()) {
            String path = "$.channels." + e.getKey();
            ClipAnimation.Channel channel = ClipAnimation.Channel.fromJson(e.getKey());
            if (channel == null) throw new ClipAnimationLoader.FormatException(path + ": unknown channel");
            curves.put(channel, ClipAnimationLoader.parseCurve(
                    ClipAnimationLoader.asObject(e.getValue(), path), channel, referenceFrames, path));
        }
        ClipAnimation animation = new ClipAnimation(id, name, ClipAnimation.Direction.IN, 1f, curves, false, referenceFrames);
        return new EffectStyle(id, name, description, author, label, lo, hi, period, span, animation);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString((long) d) : Double.toString(d);
    }
}
