package com.vanvatcorporation.doubleclips;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.Expose;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a TEXT clip looks. Plain data (no Android types) so it can be shared with the desktop port.
 * <p>
 * A style is a PRESET: picking one copies its values into the clip, so a project never depends on
 * a style still being installed. {@link #id}/{@link #name}/{@link #author} only record where the
 * values came from (and drive the style browser: name, @author, engine badge). A clip whose values
 * were edited by hand afterwards has a null id ("Custom").
 */
public class TextStyle implements Serializable, UnknownKeysHolder {

    public static final String ENGINE_FFMPEG = "FFMPEG";
    public static final String ENGINE_OPENGL = "OPENGL";

    public static final String ALIGN_LEFT = "left", ALIGN_CENTER = "center", ALIGN_RIGHT = "right";

    /** Unknown keys of this style's JSON object (another platform's fields), kept across a save. See {@link UnknownKeysHolder}. */
    private transient JsonObject unknownKeys;

    @Override public JsonObject getUnknownKeys() { return unknownKeys; }
    @Override public void setUnknownKeys(JsonObject keys) { unknownKeys = keys; }

    /** {@link #alignment} reduced to one of the three known values. */
    public String alignmentOrDefault() {
        if (ALIGN_CENTER.equalsIgnoreCase(alignment)) return ALIGN_CENTER;
        if (ALIGN_RIGHT.equalsIgnoreCase(alignment)) return ALIGN_RIGHT;
        return ALIGN_LEFT;
    }

    @Override
    public void onLoaded(JsonObject raw) {
        // The iOS-compatible hex colours win over the old int fields the loader already filled in.
        colorArgb = hexOf(raw, "colorHex", colorArgb);
        outlineColorArgb = hexOf(raw, "outlineColorHex", outlineColorArgb);
        alignment = alignmentOrDefault();
        // Consumed into the colour fields above (and rewritten from them on save), so they aren't "unknown".
        if (unknownKeys != null) {
            unknownKeys.remove("colorHex");
            unknownKeys.remove("outlineColorHex");
            if (unknownKeys.size() == 0) unknownKeys = null;
        }
    }

    @Override
    public void onSaving(JsonObject tree) {
        tree.addProperty("colorHex", toHex(colorArgb));
        tree.addProperty("outlineColorHex", toHex(outlineColorArgb));
        // iOS names a font by PostScript name; if this build picked a font file since, that name is stale.
        if (fontPath != null && !fontPath.isEmpty()) tree.remove("fontName");
    }

    private static int hexOf(JsonObject raw, String key, int fallback) {
        JsonElement e = raw.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return fallback;
        return parseHex(e.getAsString(), fallback);
    }

    /** Built-in id, or null for custom values. */
    @Expose public String id;
    @Expose public String name;
    /** Community attribution such as "@username"; null for built-ins and local styles. */
    @Expose public String author;
    /**
     * A .ttf / .otf / .ttc: relative to the project folder ("Fonts/Name.ttf", an imported font) or an absolute
     * path (a system font). Null = the default font (DroidSans, what the FFmpeg export uses). A style
     * whose font is null leaves the clip's current font alone when it is applied.
     */
    @Expose public String fontPath;
    /**
     * Fill and outline colour as ARGB ints in memory. On disk they are the iOS-compatible "colorHex" /
     * "outlineColorHex" ("#RRGGBB" or "#RRGGBBAA", see {@link #onSaving}); these two fields are deliberately NOT
     * @Expose, but the plain loader still reads an old "colorArgb" / "outlineColorArgb" int (projects saved by
     * builds before the iOS-compatible layout), and {@link #onLoaded} lets a hex value win when both exist.
     */
    public int colorArgb = 0xFFFFFFFF;
    public int outlineColorArgb = 0xFF000000;
    /** Outline thickness in canvas pixels, around each glyph. 0 = none. */
    @Expose public float outlineWidth = 0f;
    /** Bold / italic, the same keys the iOS port writes ("bold", "italic"). A font without the weight gets a synthetic one. */
    @Expose public boolean bold;
    @Expose public boolean italic;
    /**
     * "left", "center" or "right" (the iOS key "alignment"): how the lines of a multi-line text sit inside the block.
     * Absent in older projects = "left", which is how text was always drawn here; the iOS port writes the key
     * every time, so its own default of "center" never reaches this build as a missing value.
     */
    @Expose public String alignment = ALIGN_LEFT;
    /** Engines this style renders on; null or empty = both. Per-character animation styles list only OPENGL. */
    @Expose public List<String> supportedEngines;

    // ---- per-unit (character / word / line) animation: OpenGL only --------------------------
    /** The clip's in / out animation is applied to each unit instead of the whole text: "CHARACTER", "WORD" or "LINE"; null / "NONE" = whole text. */
    @Expose public String unitMode;
    /** Share of the animation window spent staggering the units' starts, 0..0.95 (0 = all together). */
    @Expose public float stagger = 0.6f;
    /** Which unit goes first: "FORWARD", "REVERSE", "CENTER_OUT" or "RANDOM". */
    @Expose public String order = "FORWARD";
    /** Animation ids to put on the clip's in / out slots when this style is picked; null = leave the clip's own. */
    @Expose public String inAnimationId;
    @Expose public String outAnimationId;

    public TextStyle() { }

    public TextStyle(TextStyle other) {
        this.id = other.id;
        this.name = other.name;
        this.author = other.author;
        this.fontPath = other.fontPath;
        this.colorArgb = other.colorArgb;
        this.outlineColorArgb = other.outlineColorArgb;
        this.outlineWidth = other.outlineWidth;
        this.bold = other.bold;
        this.italic = other.italic;
        this.alignment = other.alignment;
        copyUnknownKeysFrom(other);
        this.supportedEngines = other.supportedEngines == null ? null : new ArrayList<>(other.supportedEngines);
        this.unitMode = other.unitMode;
        this.stagger = other.stagger;
        this.order = other.order;
        this.inAnimationId = other.inAnimationId;
        this.outAnimationId = other.outAnimationId;
    }

    public boolean animatesPerUnit() {
        return unitMode != null && !"NONE".equals(unitMode);
    }

    /** Shared read-only default for clips with no style yet: plain white text, no outline. Never mutate it. */
    public static final TextStyle DEFAULT = builtIn("classic", "Classic", 0xFFFFFFFF, 0xFF000000, 0f);

    public boolean supportsEngine(String engine) {
        if (animatesPerUnit() && !ENGINE_OPENGL.equals(engine)) return false; // FFmpeg's drawtext can't animate per unit
        return supportedEngines == null || supportedEngines.isEmpty() || supportedEngines.contains(engine);
    }

    /** Same look (ignores id / name / author), used to tell whether hand edits left a preset unchanged. */
    public boolean sameLookAs(TextStyle o) {
        if (o == null) return false;
        return colorArgb == o.colorArgb && outlineColorArgb == o.outlineColorArgb
                && Math.abs(outlineWidth - o.outlineWidth) < 1e-3f
                && bold == o.bold && italic == o.italic
                && alignmentOrDefault().equals(o.alignmentOrDefault())
                && (fontPath == null ? o.fontPath == null : fontPath.equals(o.fontPath))
                && java.util.Objects.equals(normMode(unitMode), normMode(o.unitMode))
                && Math.abs(stagger - o.stagger) < 1e-3f
                && java.util.Objects.equals(order, o.order)
                && java.util.Objects.equals(inAnimationId, o.inAnimationId)
                && java.util.Objects.equals(outAnimationId, o.outAnimationId);
    }

    private static String normMode(String m) { return m == null ? "NONE" : m; }

    /** Key part for texture / measurement caches: everything that changes the pixels. */
    public String cacheKey() {
        return fontPath + "|" + colorArgb + "|" + outlineColorArgb + "|" + outlineWidth
                + "|" + bold + "|" + italic + "|" + alignmentOrDefault();
    }

    // ---- built-in presets ---------------------------------------------------------------

    private static TextStyle builtIn(String id, String name, int color, int outlineColor, float outlineWidth) {
        TextStyle s = new TextStyle();
        s.id = id;
        s.name = name;
        s.colorArgb = color;
        s.outlineColorArgb = outlineColor;
        s.outlineWidth = outlineWidth;
        return s;
    }

    private static TextStyle animated(String id, String name, int color, int outlineColor, float outlineWidth,
                                      String unitMode, float stagger, String order, String in, String out) {
        TextStyle s = builtIn(id, name, color, outlineColor, outlineWidth);
        s.unitMode = unitMode;
        s.stagger = stagger;
        s.order = order;
        s.inAnimationId = in;
        s.outAnimationId = out;
        s.supportedEngines = new ArrayList<>();
        s.supportedEngines.add(ENGINE_OPENGL);
        return s;
    }

    /** The styles that ship with the app. Fresh copies each call. */
    public static List<TextStyle> builtIns() {
        List<TextStyle> list = new ArrayList<>();
        list.add(builtIn("classic", "Classic", 0xFFFFFFFF, 0xFF000000, 0f));
        list.add(builtIn("bold-outline", "Bold Outline", 0xFFFFFFFF, 0xFF000000, 4f));
        list.add(builtIn("caption-yellow", "Caption Yellow", 0xFFFFD60A, 0xFF000000, 3f));
        list.add(builtIn("neon-pink", "Neon Pink", 0xFFFF4FD8, 0xFF6A0057, 3f));
        list.add(builtIn("sticker", "Sticker", 0xFF111111, 0xFFFFFFFF, 6f));
        // Per-unit animated styles (OpenGL only). Each puts its animation on the clip's in / out slots.
        list.add(animated("pop-letters", "Pop Letters", 0xFFFFFFFF, 0xFF000000, 3f, "CHARACTER", 0.7f, "FORWARD", "pop-in", "fade-out"));
        list.add(animated("typewriter", "Typewriter", 0xFFE8F5E9, 0xFF1B5E20, 2f, "CHARACTER", 0.9f, "FORWARD", "fade-in", null));
        list.add(animated("drop-words", "Drop Words", 0xFFFFD60A, 0xFF000000, 3f, "WORD", 0.6f, "FORWARD", "drop-in", "rise-out"));
        list.add(animated("rise-lines", "Rise Lines", 0xFFFFFFFF, 0xFF000000, 0f, "LINE", 0.6f, "FORWARD", "rise-in", "fade-out"));
        list.add(animated("spin-letters", "Spin Letters", 0xFFFF4FD8, 0xFF6A0057, 3f, "CHARACTER", 0.6f, "CENTER_OUT", "spin-in", "fade-out"));
        list.add(animated("shuffle", "Shuffle", 0xFF7CE0FF, 0xFF00334D, 3f, "CHARACTER", 0.8f, "RANDOM", "tilt-in", "fade-out"));
        return list;
    }

    // ---- colour text helpers ("#RRGGBB" or "#RRGGBBAA", like CSS and FFmpeg) ------------

    public static String toHex(int argb) {
        int a = (argb >>> 24) & 0xFF;
        String rgb = String.format(Locale.US, "#%06X", argb & 0xFFFFFF);
        return a == 0xFF ? rgb : rgb + String.format(Locale.US, "%02X", a);
    }

    /** Parses "#RRGGBB", "RRGGBB", "#RRGGBBAA" (case-insensitive). Returns {@code fallback} if it isn't a colour. */
    public static int parseHex(String text, int fallback) {
        if (text == null) return fallback;
        String t = text.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        try {
            if (t.length() == 6) return 0xFF000000 | (int) Long.parseLong(t, 16);
            if (t.length() == 8) {
                long v = Long.parseLong(t, 16);
                int rgb = (int) (v >> 8) & 0xFFFFFF;
                int a = (int) (v & 0xFF);
                return (a << 24) | rgb;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return fallback;
    }

    /** FFmpeg colour syntax "0xRRGGBBAA". */
    public static String toFfmpegColor(int argb) {
        return String.format(Locale.US, "0x%06X%02X", argb & 0xFFFFFF, (argb >>> 24) & 0xFF);
    }
}
