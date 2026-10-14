package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The mask of one clip at one moment: static values from {@link ClipMask} overridden, parameter by parameter, by the
 * keyframes that hold that parameter (spec section 5). Plain Java.
 */
public final class ClipMaskResolver {

    private ClipMaskResolver() { }

    /** The clip keyframes' easing curves by name; NONE must return 0 (the value holds until the next keyframe). */
    public interface Easing {
        float apply(String name, float t);
    }

    /** The keyframe parameter names of {@link ClipMask.Key#values}. */
    public static final String X = "x", Y = "y", WIDTH = "width", HEIGHT = "height", ROTATION = "rotation",
            FEATHER = "feather", ROUNDNESS = "roundness";

    private static final String[] NAMES = {X, Y, WIDTH, HEIGHT, ROTATION, FEATHER, ROUNDNESS};
    /** Panel units per fraction (rotation is degrees on both sides). */
    private static final float[] DIVISOR = {100f, 100f, 100f, 100f, 1f, 200f, 100f};
    private static final float[] MIN = {-50f, -50f, 1f, 1f, -180f, 0f, 0f};
    private static final float[] MAX = {150f, 150f, 200f, 200f, 180f, 100f, 100f};

    /** Panel-unit range of a parameter, for the UI's sliders and number boxes. */
    public static float panelMin(String name) { return MIN[indexOf(name)]; }
    public static float panelMax(String name) { return MAX[indexOf(name)]; }
    /** Panel units = fraction x this (rotation: 1). */
    public static float panelPerFraction(String name) { return DIVISOR[indexOf(name)]; }

    private static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException(name);
    }

    /** The resolved mask: fractions, ready for drawing. */
    public static final class State {
        public final String shape;
        public final float centerX, centerY, width, height, rotation, feather, roundness;
        public final boolean invert;

        State(String shape, float centerX, float centerY, float width, float height, float rotation, float feather, float roundness, boolean invert) {
            this.shape = shape;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.feather = feather;
            this.roundness = roundness;
            this.invert = invert;
        }
    }

    /**
     * @param keys      the clip's mask keyframes, or null / empty
     * @param localTime seconds from the clip start
     */
    public static State resolve(ClipMask mask, List<ClipMask.Key> keys, float localTime, Easing easing) {
        String shape = ClipMask.normalizeShape(mask.shape);
        float[] statics = {
                mask.centerX, mask.centerY,
                mask.width != null ? mask.width : ClipMask.defaultWidth(shape),
                mask.height != null ? mask.height : ClipMask.defaultHeight(shape),
                mask.rotation, mask.feather, mask.roundness};
        float[] v = new float[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            Float animated = keys == null || keys.isEmpty() ? null : valueAt(keys, NAMES[i], localTime, easing);
            if (animated == null) {
                v[i] = statics[i];
            } else {
                float panel = Math.max(MIN[i], Math.min(MAX[i], animated));
                v[i] = panel / DIVISOR[i];
            }
        }
        return new State(shape, v[0], v[1], v[2], v[3], v[4], v[5], v[6], mask.invert);
    }

    /** One parameter in panel units at localTime, or null when no keyframe holds it. */
    static Float valueAt(List<ClipMask.Key> keys, String name, float localTime, Easing easing) {
        List<ClipMask.Key> holding = null;
        for (ClipMask.Key k : keys) {
            if (k != null && k.values != null && k.values.get(name) != null) {
                if (holding == null) holding = new ArrayList<>();
                holding.add(k);
            }
        }
        if (holding == null) return null;
        if (holding.size() > 1) Collections.sort(holding, (a, b) -> Float.compare(a.time, b.time));

        ClipMask.Key first = holding.get(0);
        if (localTime <= first.time) return first.values.get(name);
        ClipMask.Key last = holding.get(holding.size() - 1);
        if (localTime >= last.time) return last.values.get(name);
        for (int i = 0; i + 1 < holding.size(); i++) {
            ClipMask.Key a = holding.get(i), b = holding.get(i + 1);
            if (localTime < b.time) {
                float span = b.time - a.time;
                if (span <= 0f) return b.values.get(name);
                float t = (localTime - a.time) / span;
                float e = easing.apply(a.easing == null ? "LINEAR" : a.easing.toUpperCase(Locale.ROOT), t);
                float av = a.values.get(name), bv = b.values.get(name);
                return av + (bv - av) * e;
            }
        }
        return last.values.get(name);
    }
}
