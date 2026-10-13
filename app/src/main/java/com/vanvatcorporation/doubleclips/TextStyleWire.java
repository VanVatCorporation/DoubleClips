package com.vanvatcorporation.doubleclips;

import java.util.Locale;

/**
 * The spellings of {@link TextStyle#unitMode} and {@link TextStyle#order} in a project file.
 * <p>
 * iOS writes {@code "none" | "character" | "word" | "line"} and {@code "forward" | "reverse" | "centerOut" | "random"}.
 * This build keeps {@code CHARACTER / WORD / LINE (null = whole text)} and {@code FORWARD / REVERSE / CENTER_OUT / RANDOM}
 * in memory, so the conversion happens only when a style is read from or written to JSON. Reading accepts either
 * spelling, so projects saved by earlier Android builds (upper case) still load.
 * <p>
 * Plain Java, no Android types, so the desktop port can share it.
 */
public final class TextStyleWire {

    private TextStyleWire() { }

    /** In-memory unit mode ("CHARACTER", "WORD", "LINE") for a file value; null = the whole text (also for "none" and anything unknown). */
    public static String unitModeFromWire(String value) {
        if (value == null) return null;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "character": return "CHARACTER";
            case "word": return "WORD";
            case "line": return "LINE";
            default: return null;
        }
    }

    /** The file value for an in-memory unit mode (either spelling, null = none). */
    public static String unitModeToWire(String mode) {
        String m = unitModeFromWire(mode);
        return m == null ? "none" : m.toLowerCase(Locale.ROOT);
    }

    /** In-memory order ("FORWARD", "REVERSE", "CENTER_OUT", "RANDOM") for a file value; anything unknown is FORWARD. */
    public static String orderFromWire(String value) {
        if (value == null) return "FORWARD";
        switch (value.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "")) {
            case "reverse": return "REVERSE";
            case "centerout": return "CENTER_OUT";
            case "random": return "RANDOM";
            default: return "FORWARD";
        }
    }

    /** The file value for an in-memory order (either spelling). */
    public static String orderToWire(String order) {
        switch (orderFromWire(order)) {
            case "REVERSE": return "reverse";
            case "CENTER_OUT": return "centerOut";
            case "RANDOM": return "random";
            default: return "forward";
        }
    }
}
