package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ordered list of transition styles the picker shows, built from the built-in xfade styles
 * ({@code FXCommandEmitter.FXRegistry.transitionFXMap}) plus the data-driven ones ({@link TransitionStyleLoader}).
 * Each entry says which export engine can draw it, so the picker can badge it.
 *
 * Order: "none", the styles both engines draw, the data-driven (OpenGL) ones, then the FFmpeg-only built-ins
 * (and the [Custom] ones) in the registry's own order. A style id stored in a project that is not in either
 * source (a style from a newer build or another platform) gets a trailing "[not installed]" entry so that
 * opening the picker and closing it again does not overwrite it.
 *
 * Plain Java (no Android classes).
 */
public final class TransitionStyleCatalog {

    public enum Engine { BOTH, OPENGL_ONLY, FFMPEG_ONLY, UNKNOWN }

    public static final class Entry {
        public final String id;
        public final String label;
        public final Engine engine;
        public final boolean dataDriven;

        Entry(String id, String label, Engine engine, boolean dataDriven) {
            this.id = id;
            this.label = label;
            this.engine = engine;
            this.dataDriven = dataDriven;
        }

        @Override public String toString() { return label; }
    }

    private TransitionStyleCatalog() {}

    /**
     * @param builtIn            id -> label of the built-in styles, in picker order ("none" first)
     * @param glStyles           the built-in ids the OpenGL export draws (OpenGLEdit.SUPPORTED_TRANSITION_STYLES)
     * @param dataDriven         the loaded data-driven styles
     * @param glDrawsDataDriven  whether the OpenGL renderer can draw the data-driven styles yet (a label hint only)
     * @param currentStyle       the style stored on the transition being edited; may be null
     */
    public static List<Entry> build(Map<String, String> builtIn, Set<String> glStyles, List<TransitionStyle> dataDriven,
                                    boolean glDrawsDataDriven, String currentStyle) {
        List<Entry> none = new ArrayList<>();
        List<Entry> shared = new ArrayList<>();
        List<Entry> ffmpegOnly = new ArrayList<>();
        for (Map.Entry<String, String> e : builtIn.entrySet()) {
            String id = e.getKey();
            if ("none".equals(id)) {
                none.add(new Entry(id, e.getValue(), Engine.BOTH, false));
            } else if (glStyles.contains(id)) {
                shared.add(new Entry(id, e.getValue(), Engine.BOTH, false));
            } else {
                // [Custom] ones already say what they are; the rest get the badge.
                String label = id.startsWith("custom_") ? e.getValue() : e.getValue() + " [FFmpeg only]";
                ffmpegOnly.add(new Entry(id, label, Engine.FFMPEG_ONLY, false));
            }
        }
        List<Entry> data = new ArrayList<>();
        for (TransitionStyle s : dataDriven) {
            if (builtIn.containsKey(s.getId())) continue; // a built-in name wins; the file would be unreachable anyway
            String badge = glDrawsDataDriven ? " [OpenGL only]" : " [OpenGL only - not drawn yet]";
            data.add(new Entry(s.getId(), s.getName() + badge, Engine.OPENGL_ONLY, true));
        }

        List<Entry> all = new ArrayList<>(none);
        all.addAll(shared);
        all.addAll(data);
        all.addAll(ffmpegOnly);

        String current = currentStyle == null ? "" : currentStyle.trim();
        if (!current.isEmpty() && indexOf(all, current) < 0) {
            all.add(new Entry(currentStyle, current + " [not installed]", Engine.UNKNOWN, false));
        }
        return all;
    }

    /** Index of the entry for a stored style id (compared trimmed and case-insensitively), or -1. */
    public static int indexOf(List<Entry> entries, String style) {
        if (style == null) return -1;
        String wanted = style.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.trim().toLowerCase(Locale.ROOT).equals(wanted)) return i;
        }
        return -1;
    }
}
