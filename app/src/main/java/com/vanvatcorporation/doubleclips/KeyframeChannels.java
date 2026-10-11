package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Per-property keyframes, the iOS model: a keyframe can belong to just some of the clip's properties (its
 * {@code channels} list) instead of snapshotting all of them.
 *
 * <ul>
 * <li>{@code channels == null} is the old kind (what Android and desktop always wrote): it holds every property, so
 *     existing projects behave exactly as before.</li>
 * <li>{@code channels == ["posX"]} only animates Pos X.</li>
 * </ul>
 *
 * Each property is interpolated on its own, through the keyframes that hold it: no keyframe holds it -> its static
 * value; otherwise the value between those keyframes (before the first / after the last it holds the nearest one),
 * using the easing of the keyframe the segment starts at. A keyframe's stored {@code value} always carries a complete
 * look (the properties it doesn't hold are filled with what they were), so a reader that knows nothing about
 * {@code channels} still plays something close.
 *
 * The names are iOS's {@code ValueType} raw values ("posX", "scaleY", ...). A list may carry names this build doesn't
 * know ("strength", an iOS-only effect property); they are kept as they are and never held by any property here.
 *
 * Plain Java (no Android classes): the rules live here so they can be tested off-device; EditingActivity's Keyframe
 * and AnimatedProperty delegate to it.
 */
public final class KeyframeChannels {

    /**
     * The properties an "all properties" keyframe ({@code channels == null}) holds, in iOS's order. rotInRadians is only
     * Rot in radians and "strength" (an effect clip's strength, iOS only) is held only by a keyframe that lists it, so
     * neither is in this list.
     */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            "posX", "posY", "rot", "scaleX", "scaleY", "pivotX", "pivotY",
            "opacity", "speed", "volume", "hue", "saturation", "brightness", "temperature"));

    /** Held only by a keyframe that names it explicitly. */
    private static final String STRENGTH = "strength";

    /** Values closer than this are "the same" when a whole look is written back (the sheet's sliders round to 2 decimals). */
    public static final float SAME_VALUE_TOLERANCE = 0.0051f;

    private KeyframeChannels() {}

    /** Does a keyframe with this {@code channels} list hold the property {@code name}? */
    public static boolean holds(List<String> channels, String name) {
        return channels == null ? !STRENGTH.equals(name) : channels.contains(name);
    }

    /**
     * The {@code channels} list after the keyframe starts (or stops) holding {@code name}; null when it then holds every
     * property (stored as "no list"). The input list is not modified.
     */
    public static List<String> with(List<String> channels, String name, boolean on) {
        List<String> list = channels == null ? new ArrayList<>(ALL) : new ArrayList<>(channels);
        if (on) {
            if (!list.contains(name)) list.add(name);
        } else {
            while (list.remove(name)) { /* every copy */ }
        }
        return list.size() == ALL.size() && list.containsAll(ALL) ? null : list;
    }

    /** True when the list holds none of the properties known here nor anything else (the keyframe has no purpose left). */
    public static boolean holdsNothing(List<String> channels) {
        return channels != null && channels.isEmpty();
    }

    /** A copy of a channels list (null stays null). */
    public static List<String> copy(List<String> channels) {
        return channels == null ? null : new ArrayList<>(channels);
    }

    // ---- interpolation -----------------------------------------------------------------

    /** The keyframes of one clip as seen by one property (the caller adapts its own Keyframe class). */
    public interface Keys {
        int size();
        /** Does keyframe {@code i} hold the property being read? */
        boolean holds(int i);
        /** Local clip time of keyframe {@code i}, seconds. Keyframes are sorted by time. */
        float time(int i);
        /** Keyframe {@code i}'s stored value of the property. */
        float value(int i);
        /** The easing of keyframe {@code i} applied to t (0..1): NONE gives 0, i.e. "hold until the next keyframe". */
        float ease(int i, float t);
    }

    /**
     * One property at {@code local} seconds into the clip. Only keyframes that hold the property take part; none = the
     * static value. Before the first holder it holds that keyframe's value, after the last it holds the last one's.
     */
    public static float valueAt(Keys keys, float local, float staticValue) {
        int prev = -1;
        int n = keys.size();
        for (int i = 0; i < n; i++) {
            if (!keys.holds(i)) continue;
            if (prev < 0) {
                if (local < keys.time(i)) return keys.value(i);
                prev = i;
                continue;
            }
            if (local < keys.time(i)) {
                float span = keys.time(i) - keys.time(prev);
                // Never NaN, even for two keyframes at the same time.
                float raw = span > 0f ? (local - keys.time(prev)) / span : 0f;
                float t = Math.max(0f, Math.min(1f, raw));
                float a = keys.value(prev);
                float b = keys.value(i);
                return a + (b - a) * keys.ease(prev, t);
            }
            prev = i;
        }
        return prev < 0 ? staticValue : keys.value(prev);
    }
}
