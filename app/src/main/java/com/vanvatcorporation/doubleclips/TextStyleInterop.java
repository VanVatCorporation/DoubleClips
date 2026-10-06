package com.vanvatcorporation.doubleclips;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Bridge between this build's text style (a nested "textStyle" object, same layout as the iOS port) and the
 * desktop port's flat clip keys: textColor ("#RRGGBB"), textOutlineWidth, textOutlineColor, textBold, textItalic,
 * textAlign (0 left, 1 centre, 2 right), plus textFontFamily / textFontFile.
 * <p>
 * Reading: a clip that has the desktop keys but no "textStyle" (a project last saved by the desktop port) gets a
 * style built from them. Writing: the desktop keys are always written next to "textStyle", so a project saved here
 * still looks right on desktop (a clip without them means "black, default font" there).
 * <p>
 * Desktop fonts can't be used here (an absolute path on another machine, or a Java logical font), so the desktop
 * font keys are left as they were unless a font file was picked in this build, which makes them stale.
 * Plain Java + Gson, no Android types.
 */
public final class TextStyleInterop {

    private static final String[] DESKTOP_KEYS = {
            "textColor", "textOutlineWidth", "textOutlineColor", "textBold", "textItalic", "textAlign"
    };

    private TextStyleInterop() { }

    /** The style the desktop keys of {@code clip} describe, or null when the clip has none of them. */
    public static TextStyle fromDesktopKeys(JsonObject clip) {
        if (clip == null) return null;
        boolean any = false;
        for (String k : DESKTOP_KEYS) if (clip.has(k)) { any = true; break; }
        if (!any) return null;

        TextStyle s = new TextStyle();
        s.id = null;
        s.name = "Custom";
        // A missing colour means "the old look" on desktop: black.
        s.colorArgb = parseColor(clip, "textColor", 0xFF000000);
        s.outlineColorArgb = parseColor(clip, "textOutlineColor", 0xFF000000);
        s.outlineWidth = Math.max(0f, number(clip, "textOutlineWidth", 0f));
        s.bold = bool(clip, "textBold");
        s.italic = bool(clip, "textItalic");
        int align = (int) number(clip, "textAlign", 0f);
        s.alignment = align == 1 ? TextStyle.ALIGN_CENTER : align == 2 ? TextStyle.ALIGN_RIGHT : TextStyle.ALIGN_LEFT;
        return s;
    }

    /** Writes {@code style} into the desktop's flat keys of {@code clipTree}. */
    public static void writeDesktopKeys(JsonObject clipTree, TextStyle style) {
        if (clipTree == null || style == null) return;
        clipTree.addProperty("textColor", rgb(style.colorArgb));
        clipTree.addProperty("textOutlineWidth", style.outlineWidth);
        clipTree.addProperty("textOutlineColor", rgb(style.outlineColorArgb));
        clipTree.addProperty("textBold", style.bold);
        clipTree.addProperty("textItalic", style.italic);
        String a = style.alignmentOrDefault();
        clipTree.addProperty("textAlign", TextStyle.ALIGN_CENTER.equals(a) ? 1 : TextStyle.ALIGN_RIGHT.equals(a) ? 2 : 0);
        if (style.fontPath != null && !style.fontPath.isEmpty()) {
            clipTree.remove("textFontFamily");
            clipTree.remove("textFontFile");
        }
    }

    /** Desktop colours are "#RRGGBB": the alpha of a style colour has no place there. */
    private static String rgb(int argb) {
        return String.format(java.util.Locale.US, "#%06X", argb & 0xFFFFFF);
    }

    private static int parseColor(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return fallback;
        return TextStyle.parseHex(e.getAsString(), fallback);
    }

    private static float number(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        return e.getAsFloat();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
