package com.vanvatcorporation.doubleclips;

import com.google.gson.annotations.Expose;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One chroma key of a clip (clip JSON key {@code "chromaKeys"}, the "Background" panel): a colour that is made transparent,
 * so what is behind the clip shows through (a green screen). A clip can carry several (up to {@link #MAX_KEYS}); a pixel that
 * matches ANY of them goes away. Same format as desktop; iOS follows it.
 *
 * <p>The four controls are CapCut's, each 0 ... {@link #SCALE_MAX} (the numbers its panel shows):
 * <ul>
 * <li>{@code intensity}: how wide the range of colours around the picked one that is removed (a tolerance);</li>
 * <li>{@code shadow}: also removes the darker shades of the picked colour (the shadows that fall on the screen);</li>
 * <li>{@code feather}: how soft the edge of what stays is;</li>
 * <li>{@code cleanUpEdge}: shrinks the edge a little and takes the key colour's tint off the pixels along it
 *     (the green fringe round a subject).</li>
 * </ul>
 * Each slider can be keyframed (channels "chromaIntensity0" ... "chromaCleanUp5", see KeyframeChannels), under the key's PLACE
 * in the clip's list. The keying itself runs in the fragment shader (OpenGLEditNative). Plain Java, no Android classes.
 */
public class ChromaKey implements Serializable {

    /** The most chroma keys one clip can carry: the panel stops offering "Add" there, and it is the shader's array size. */
    public static final int MAX_KEYS = 6;
    /** The sliders a key has (intensity, shadow, feather edge, clean up edge): each can be keyframed. */
    public static final int PARAM_COUNT = 4;
    public static final int INTENSITY = 0, SHADOW = 1, FEATHER = 2, CLEAN_UP = 3;
    /** Every control runs 0 ... SCALE_MAX. */
    public static final float SCALE_MAX = 100f;
    public static final String DEFAULT_COLOR = "#00FF00";
    public static final float DEFAULT_INTENSITY = 20f;

    /** A switched-off key is kept (and can be switched on again) but doesn't key. */
    @Expose public boolean enabled = true;
    /** "#RRGGBB". */
    @Expose public String color = DEFAULT_COLOR;
    @Expose public float intensity = DEFAULT_INTENSITY;
    @Expose public float shadow;
    @Expose public float feather;
    @Expose public float cleanUpEdge;

    public ChromaKey() { }

    public ChromaKey(ChromaKey other) {
        this.enabled = other.enabled;
        this.color = other.color;
        this.intensity = other.intensity;
        this.shadow = other.shadow;
        this.feather = other.feather;
        this.cleanUpEdge = other.cleanUpEdge;
    }

    public static List<ChromaKey> copyAll(List<ChromaKey> keys) {
        if (keys == null) return null;
        List<ChromaKey> out = new ArrayList<>(keys.size());
        for (ChromaKey k : keys) if (k != null) out.add(new ChromaKey(k));
        return out;
    }

    /** The colour as r, g, b in 0 ... 1; the default green when the text isn't a "#RRGGBB". */
    public float[] rgb() {
        int v = parse(color);
        if (v < 0) v = parse(DEFAULT_COLOR);
        return new float[]{((v >> 16) & 0xFF) / 255f, ((v >> 8) & 0xFF) / 255f, (v & 0xFF) / 255f};
    }

    /** The colour as 0xAARRGGBB (opaque), for views. */
    public int argb() {
        int v = parse(color);
        if (v < 0) v = parse(DEFAULT_COLOR);
        return 0xFF000000 | v;
    }

    private static int parse(String hex) {
        if (hex == null) return -1;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 8) s = s.substring(0, 6); // "#RRGGBBAA": the alpha means nothing for a key
        if (s.length() != 6) return -1;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True when {@code text} is a colour {@link #rgb()} would read ("#RRGGBB" or "RRGGBB"). */
    public static boolean isValidHex(String text) {
        return parse(text) >= 0;
    }

    public static String toHex(float r, float g, float b) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(clamp01(r) * 255), Math.round(clamp01(g) * 255), Math.round(clamp01(b) * 255));
    }

    /** "#RRGGBB" of an 0xAARRGGBB colour (the alpha is dropped). */
    public static String toHex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    /** The slider {@code param} of this key (INTENSITY ... CLEAN_UP): its plain value. */
    public float get(int param) {
        switch (param) {
            case INTENSITY: return intensity;
            case SHADOW: return shadow;
            case FEATHER: return feather;
            default: return cleanUpEdge;
        }
    }

    public void set(int param, float value) {
        switch (param) {
            case INTENSITY: intensity = value; break;
            case SHADOW: shadow = value; break;
            case FEATHER: feather = value; break;
            default: cleanUpEdge = value; break;
        }
    }

    // ---- what the fragment shader takes ------------------------------------------------------------------

    /** Where a slider's value comes from at the moment being drawn: the key's own value, or its keyframed one. */
    public interface SliderSource {
        /** Slider {@code param} (INTENSITY ...) of key number {@code index} of the clip's list (a switched-off key keeps its place). */
        float value(int index, int param, ChromaKey key);
    }

    /** What the keys of a clip are as the fragment shader takes them: the enabled ones only, at most {@link #MAX_KEYS}. */
    public static final class Packed {
        public final int count;
        /** Per key (r, g, b), 0 ... 1. */
        public final float[] color;
        /** Per key (intensity, shadow, feather, clean up edge), each 0 ... 1. */
        public final float[] param;
        /**
         * One canvas pixel of the clip's box, as a step in the picture's own 0 ... 1 space (x right, y down): how far "next to this
         * pixel" is for the edge clean-up. In canvas pixels, not source ones, so the preview and the export look the same.
         */
        public final float stepX, stepY;

        Packed(int count, float stepX, float stepY) {
            this.count = count;
            this.color = new float[count * 3];
            this.param = new float[count * 4];
            this.stepX = stepX;
            this.stepY = stepY;
        }
    }

    /**
     * The clip's keys packed for the shader; null when none is switched on (the overwhelming majority of clips).
     *
     * @param source  where each slider's value comes from; null = the keys' own plain values
     * @param boxW    the clip as drawn (scaled, not rotated), in canvas pixels, and the same for {@code boxH}
     */
    public static Packed pack(List<ChromaKey> keys, SliderSource source, float boxW, float boxH) {
        if (keys == null || keys.isEmpty()) return null;
        int n = 0;
        for (ChromaKey k : keys) if (k != null && k.enabled && n < MAX_KEYS) n++;
        if (n == 0) return null;
        Packed out = new Packed(n, boxW > 1f ? 1f / boxW : 1f, boxH > 1f ? 1f / boxH : 1f);
        int i = 0;
        for (int index = 0; index < keys.size() && i < n; index++) {
            ChromaKey k = keys.get(index);
            if (k == null || !k.enabled) continue;
            float[] rgb = k.rgb();
            out.color[i * 3] = rgb[0];
            out.color[i * 3 + 1] = rgb[1];
            out.color[i * 3 + 2] = rgb[2];
            for (int p = 0; p < PARAM_COUNT; p++) {
                float v = source == null ? k.get(p) : source.value(index, p, k);
                out.param[i * 4 + p] = clamp01(v / SCALE_MAX);
            }
            i++;
        }
        return out;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.min(Math.max(v, 0f), 1f);
    }
}
