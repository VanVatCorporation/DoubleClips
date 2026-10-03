package com.vanvatcorporation.doubleclips;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * One loaded clip animation (an "in" or an "out"): a set of channels, each a curve over
 * progress p in [0, 1]. Immutable and thread-safe. Built by {@link ClipAnimationLoader}
 * from a JSON file; nothing in here executes anything from the file - a curve is just
 * numbers plus one of three fixed formulas (constant / piecewise-linear knots / gaussian).
 *
 * Progress p is the animation's OWN time: for an "in" animation p = 0 at the clip's first
 * frame, for an "out" animation p = 0 at the start of the out window and p = 1 at the
 * clip's end (see {@link #progress} / {@link #progressOut}). An out animation may be
 * authored directly, or be the time-reverse of an in animation ("mirrorOf" in the file).
 *
 * Plain Java (no Android classes) so the desktop port can use it as-is.
 */
public final class ClipAnimation {

    public enum Direction {
        IN("in"), OUT("out");
        public final String json;
        Direction(String json) { this.json = json; }
        static Direction fromJson(String s) {
            for (Direction d : values()) if (d.json.equals(s)) return d;
            return null;
        }
    }

    /**
     * Every channel an animation may drive - exactly what the OpenGL export can apply.
     * min/max are enforced on load (every knot / gaussian endpoint of a file must lie inside).
     * BLUR is capped at 5% of the canvas width because the blur shader is a fixed 17-tap
     * kernel (taps sigma/3 apart): beyond roughly that it starts to band.
     */
    public enum Channel {
        OPACITY("opacity", 1, 0, 1),
        SCALE("scale", 1, 0.01, 10),
        OFFSET_X("offsetX", 0, -2, 2),
        OFFSET_Y("offsetY", 0, -2, 2),
        ROTATION("rotation", 0, -3600, 3600),
        HUE("hue", 0, -360, 360),
        SATURATION("saturation", 1, 0, 10),
        BRIGHTNESS("brightness", 0, -10, 10),
        CONTRAST("contrast", 1, 0, 10),
        TEMPERATURE("temperature", 0, -6000, 6000),
        BLUR("blur", 0, 0, 0.05),
        WARP_TOP_WIDTH("warp.topWidth", 1, 0.1, 2),
        WARP_BOTTOM_WIDTH("warp.bottomWidth", 1, 0.1, 2),
        WARP_HEIGHT("warp.height", 1, 0.1, 2);

        public final String json;
        public final double neutral;
        public final double min;
        public final double max;

        Channel(String json, double neutral, double min, double max) {
            this.json = json;
            this.neutral = neutral;
            this.min = min;
            this.max = max;
        }

        static Channel fromJson(String s) {
            for (Channel c : values()) if (c.json.equals(s)) return c;
            return null;
        }
    }

    // ---- curves ------------------------------------------------------------------------

    /** A channel's value as a function of progress. Three fixed kinds only. */
    public static abstract class Curve {
        /** Value at progress p (p is clamped to [0, 1] by the caller). */
        public abstract double at(double p);
        /** The "kind" string used in the file. */
        public abstract String kind();
    }

    public static final class ConstantCurve extends Curve {
        private final double value;
        ConstantCurve(double value) { this.value = value; }
        public double getValue() { return value; }
        @Override public double at(double p) { return value; }
        @Override public String kind() { return "constant"; }
    }

    /**
     * Piecewise-linear (or smoothstep-eased) through (p, value) knots, p strictly
     * increasing. Holds the first value before the first knot and the last value after
     * the last knot.
     */
    public static final class KnotsCurve extends Curve {
        private final double[] ps;
        private final double[] vs;
        private final boolean smooth;

        KnotsCurve(double[] ps, double[] vs, boolean smooth) {
            this.ps = ps;
            this.vs = vs;
            this.smooth = smooth;
        }

        public int size() { return ps.length; }
        public double getP(int i) { return ps[i]; }
        public double getValue(int i) { return vs[i]; }
        public boolean isSmooth() { return smooth; }

        @Override public double at(double p) {
            int last = ps.length - 1;
            if (p <= ps[0]) return vs[0];
            if (p >= ps[last]) return vs[last];
            int i = 1;
            while (ps[i] < p) i++;            // first knot at or after p; i >= 1 here
            double t = (p - ps[i - 1]) / (ps[i] - ps[i - 1]);
            if (smooth) t = t * t * (3.0 - 2.0 * t);
            return vs[i - 1] + (vs[i] - vs[i - 1]) * t;
        }

        @Override public String kind() { return "knots"; }
    }

    /**
     * base + peak * envelope(p), envelope(p) = (exp(-(p/tau)^2) - tail) / (1 - tail) with
     * tail = exp(-(1/tau)^2): exactly 1 at p = 0 and exactly 0 at p = 1, falling smoothly.
     */
    public static final class GaussianCurve extends Curve {
        private final double base;
        private final double peak;
        private final double tau;
        private final double tail;

        GaussianCurve(double base, double peak, double tau) {
            this.base = base;
            this.peak = peak;
            this.tau = tau;
            this.tail = Math.exp(-(1.0 / tau) * (1.0 / tau));
        }

        public double getBase() { return base; }
        public double getPeak() { return peak; }
        public double getTau() { return tau; }

        @Override public double at(double p) {
            double env = (Math.exp(-(p / tau) * (p / tau)) - tail) / (1.0 - tail);
            return base + peak * env;
        }

        @Override public String kind() { return "gaussian"; }
    }

    // ---- the animation -----------------------------------------------------------------

    private final String id;
    private final String name;
    private final Direction direction;
    private final float defaultDuration;
    private final boolean reversed;
    private final int referenceFrames;
    private final Channel[] activeChannels;
    private final Curve[] activeCurves;
    private final Map<Channel, Curve> curves;

    ClipAnimation(String id, String name, Direction direction, float defaultDuration,
                  Map<Channel, Curve> curves, boolean reversed, int referenceFrames) {
        this.id = id;
        this.name = name;
        this.direction = direction;
        this.defaultDuration = defaultDuration;
        this.reversed = reversed;
        this.referenceFrames = referenceFrames;
        EnumMap<Channel, Curve> copy = new EnumMap<>(Channel.class);
        copy.putAll(curves);
        this.curves = Collections.unmodifiableMap(copy);
        this.activeChannels = copy.keySet().toArray(new Channel[0]);
        this.activeCurves = new Curve[activeChannels.length];
        for (int i = 0; i < activeChannels.length; i++) activeCurves[i] = copy.get(activeChannels[i]);
    }

    /** The time-reverse of {@code base}, sharing its curves, as a new animation. */
    static ClipAnimation mirror(ClipAnimation base, String id, String name, Direction direction, float defaultDuration) {
        return new ClipAnimation(id, name, direction, defaultDuration, base.curves, !base.reversed, base.referenceFrames);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Direction getDirection() { return direction; }
    /** Suggested duration in seconds (the editor's duration field default for this animation). */
    public float getDefaultDuration() { return defaultDuration; }
    /**
     * The file's "referenceFrames" (how many frames of the capture the animation was measured from,
     * 0 if the file didn't say). A hint for exporters that have to step through time in slices.
     */
    public int getReferenceFrames() { return referenceFrames; }
    /** True if p is flipped (1 - p) before sampling the curves (a "mirrorOf" animation). */
    public boolean isReversed() { return reversed; }
    /** The channels this animation drives (the rest stay neutral). */
    public Set<Channel> channels() { return curves.keySet(); }
    /** Raw curve of one channel (for exporters such as the FFmpeg generator), or null. */
    public Curve curveFor(Channel channel) { return curves.get(channel); }

    /**
     * Channel values at progress p. p < 0 means "not inside the animation window" and
     * returns {@link ClipAnimationFrame#NEUTRAL} without allocating; p > 1 is held at 1.
     */
    public ClipAnimationFrame evaluate(float p) {
        if (p < 0f || Float.isNaN(p)) return ClipAnimationFrame.NEUTRAL;
        double q = p > 1f ? 1.0 : p;
        if (reversed) q = 1.0 - q;
        float[] v = ClipAnimationFrame.neutralValues();
        for (int i = 0; i < activeChannels.length; i++) {
            v[activeChannels[i].ordinal()] = (float) activeCurves[i].at(q);
        }
        return new ClipAnimationFrame(v);
    }

    // ---- progress helpers --------------------------------------------------------------

    /**
     * Progress of an IN animation: elapsed seconds since the clip's first frame over the
     * animation's duration, or -1 outside [0, duration).
     */
    public static float progress(float elapsedSeconds, float durationSeconds) {
        if (durationSeconds <= 0f || elapsedSeconds < 0f || elapsedSeconds >= durationSeconds) return -1f;
        return elapsedSeconds / durationSeconds;
    }

    /**
     * Progress of an OUT animation: -1 before the window opens at (clipEnd - duration), then
     * rising from 0 to 1 at the clip's last frame, and HELD at 1 from the clip's end on.
     * The hold matters for transitions: the outgoing clip keeps being drawn past its nominal
     * end while the transition blends it away, and snapping back to neutral there would
     * make e.g. a faded-out clip pop back to full opacity.
     */
    public static float progressOut(float clipEndSeconds, float tSeconds, float durationSeconds) {
        if (durationSeconds <= 0f) return -1f;
        float elapsed = tSeconds - (clipEndSeconds - durationSeconds);
        if (elapsed < 0f) return -1f;
        return elapsed >= durationSeconds ? 1f : elapsed / durationSeconds;
    }

    /**
     * Shrinks {@code duration} so that it and the clip's other animation fit inside the clip
     * together: when duration + otherDuration exceeds clipDuration both are scaled by the same
     * factor (so an in and an out never overlap and a short clip still plays each one whole,
     * just faster). Returns duration unchanged when it already fits, 0 for no animation.
     * Pass otherDuration = 0 when the clip has no (usable) other animation.
     */
    public static float fitDuration(float duration, float otherDuration, float clipDuration) {
        if (!(duration > 0f)) return 0f;
        float other = otherDuration > 0f ? otherDuration : 0f;
        float total = duration + other;
        if (!(clipDuration > 0f) || total <= clipDuration) return duration;
        return duration * clipDuration / total;
    }
}
