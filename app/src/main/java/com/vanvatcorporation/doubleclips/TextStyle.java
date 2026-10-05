package com.vanvatcorporation.doubleclips;

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
public class TextStyle implements Serializable {

    public static final String ENGINE_FFMPEG = "FFMPEG";
    public static final String ENGINE_OPENGL = "OPENGL";

    /** Built-in id, or null for custom values. */
    @Expose public String id;
    @Expose public String name;
    /** Community attribution such as "@username"; null for built-ins and local styles. */
    @Expose public String author;
    /** Absolute path of a .ttf / .otf, or null for the default font (DroidSans, what the FFmpeg export uses). */
    @Expose public String fontPath;
    @Expose public int colorArgb = 0xFFFFFFFF;
    @Expose public int outlineColorArgb = 0xFF000000;
    /** Outline thickness in canvas pixels, around each glyph. 0 = none. */
    @Expose public float outlineWidth = 0f;
    /** Engines this style renders on; null or empty = both. Later styles (per-character animation) will list only OPENGL. */
    @Expose public List<String> supportedEngines;

    public TextStyle() { }

    public TextStyle(TextStyle other) {
        this.id = other.id;
        this.name = other.name;
        this.author = other.author;
        this.fontPath = other.fontPath;
        this.colorArgb = other.colorArgb;
        this.outlineColorArgb = other.outlineColorArgb;
        this.outlineWidth = other.outlineWidth;
        this.supportedEngines = other.supportedEngines == null ? null : new ArrayList<>(other.supportedEngines);
    }

    /** Shared read-only default for clips with no style yet: plain white text, no outline. Never mutate it. */
    public static final TextStyle DEFAULT = builtIn("classic", "Classic", 0xFFFFFFFF, 0xFF000000, 0f);

    public boolean supportsEngine(String engine) {
        return supportedEngines == null || supportedEngines.isEmpty() || supportedEngines.contains(engine);
    }

    /** Same look (ignores id / name / author), used to tell whether hand edits left a preset unchanged. */
    public boolean sameLookAs(TextStyle o) {
        if (o == null) return false;
        return colorArgb == o.colorArgb && outlineColorArgb == o.outlineColorArgb
                && Math.abs(outlineWidth - o.outlineWidth) < 1e-3f
                && (fontPath == null ? o.fontPath == null : fontPath.equals(o.fontPath));
    }

    /** Key part for texture / measurement caches: everything that changes the pixels. */
    public String cacheKey() {
        return fontPath + "|" + colorArgb + "|" + outlineColorArgb + "|" + outlineWidth;
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

    /** The styles that ship with the app. Fresh copies each call. */
    public static List<TextStyle> builtIns() {
        List<TextStyle> list = new ArrayList<>();
        list.add(builtIn("classic", "Classic", 0xFFFFFFFF, 0xFF000000, 0f));
        list.add(builtIn("bold-outline", "Bold Outline", 0xFFFFFFFF, 0xFF000000, 4f));
        list.add(builtIn("caption-yellow", "Caption Yellow", 0xFFFFD60A, 0xFF000000, 3f));
        list.add(builtIn("neon-pink", "Neon Pink", 0xFFFF4FD8, 0xFF6A0057, 3f));
        list.add(builtIn("sticker", "Sticker", 0xFF111111, 0xFFFFFFFF, 6f));
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
