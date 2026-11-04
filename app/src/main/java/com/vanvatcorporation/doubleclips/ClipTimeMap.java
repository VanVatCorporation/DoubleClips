package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Timeline time <-> source time of one clip (iOS's {@code ClipTimeMap}; ported from the Desktop build).
 * <p>
 * A clip's {@code duration} is its length ON THE TIMELINE; the source between the trims is played at the clip's speed,
 * which is one number or, when speed has keyframes, a curve over the clip's local time. So
 * <pre>
 *   source seconds consumed after t timeline seconds = integral of speed(u) du, u = 0..t
 * </pre>
 * Speed at the instant times the elapsed time (what Android did before) is only right when the speed never changes.
 * <p>
 * Plain Java, no JavaFX / GL types. A curve's integral is summed in 1/120 s trapezoids and cached; a constant speed
 * takes an exact fast path and is NOT clamped (it behaves exactly as before), a curve is clamped to
 * {@link #MIN_SPEED}..{@link #MAX_SPEED} like iOS.
 */
public final class ClipTimeMap {

    public static final double MIN_SPEED = 0.1, MAX_SPEED = 8.0;
    private static final double STEP = 1.0 / 120.0;

    /** Non-null = the same speed everywhere. */
    private final Double constantSpeed;
    private final DoubleUnaryOperator speedFunction;
    /** cumulative[i] = source offset after i * STEP timeline seconds (curves only; grows on demand). */
    private double[] cumulative = new double[]{0};
    private int cumulativeCount = 1;

    private ClipTimeMap(Double constant, DoubleUnaryOperator function) {
        this.constantSpeed = constant;
        this.speedFunction = function;
    }

    /** The same speed everywhere; a speed of 0 or less (a bad value) counts as 1. */
    public static ClipTimeMap constant(double speed) {
        final double s = speed > 0 ? speed : 1.0;
        return new ClipTimeMap(s, t -> s);
    }

    /** @param speedAt speed at a clip-local time in seconds */
    public static ClipTimeMap curve(DoubleUnaryOperator speedAt) {
        return new ClipTimeMap(null, t -> Math.min(Math.max(speedAt.applyAsDouble(t), MIN_SPEED), MAX_SPEED));
    }

    public boolean isConstant() { return constantSpeed != null; }

    public boolean isIdentity() { return constantSpeed != null && Math.abs(constantSpeed - 1.0) < 0.001; }

    /** Speed at a clip-local time. */
    public double speedAt(double t) { return speedFunction.applyAsDouble(Math.max(t, 0)); }

    private void extend(int index) {
        while (cumulativeCount <= index) {
            int i = cumulativeCount - 1;
            double a = speedFunction.applyAsDouble(i * STEP), b = speedFunction.applyAsDouble((i + 1) * STEP);
            if (cumulativeCount == cumulative.length) cumulative = java.util.Arrays.copyOf(cumulative, cumulative.length * 2);
            cumulative[cumulativeCount++] = cumulative[i] + 0.5 * (a + b) * STEP;
        }
    }

    /**
     * Source seconds consumed after {@code t} timeline seconds (before the trim offset is added). A negative t (footage
     * played before the clip's nominal start, for a transition) continues at the starting speed.
     */
    public synchronized double sourceOffset(double t) {
        if (constantSpeed != null) return t * constantSpeed;
        if (t < 0) return t * speedFunction.applyAsDouble(0);
        double position = t / STEP;
        int index = (int) Math.floor(position);
        extend(index + 1);
        double fraction = position - index;
        return cumulative[index] + (cumulative[index + 1] - cumulative[index]) * fraction;
    }

    /** Timeline seconds after which {@code offset} source seconds have been consumed (the inverse of {@link #sourceOffset}). */
    public synchronized double localTime(double offset) {
        if (offset <= 0) return 0;
        if (constantSpeed != null) return offset / constantSpeed;
        int guard = 0;
        while (cumulative[cumulativeCount - 1] < offset && guard < 4_000_000) {
            extend(cumulativeCount);
            guard++;
        }
        int lo = 0, hi = cumulativeCount - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < offset) lo = mid; else hi = mid;
        }
        double span = cumulative[hi] - cumulative[lo];
        double fraction = span > 0 ? Math.min(Math.max((offset - cumulative[lo]) / span, 0), 1) : 0;
        return (lo + fraction) * STEP;
    }

    /** Timeline [a, b] plays source offsets [s0, s1] (before the trim offset). */
    public static final class Piece {
        public final double a, b, s0, s1;
        Piece(double a, double b, double s0, double s1) { this.a = a; this.b = b; this.s0 = s0; this.s1 = s1; }
        /** Average speed of the piece. */
        public double speed() { return b > a ? (s1 - s0) / (b - a) : 1.0; }
    }

    /** The first {@code duration} timeline seconds cut into pieces of (nearly) constant speed; one piece for a constant speed. */
    public List<Piece> pieces(double duration, double maxPieceSeconds) {
        List<Piece> out = new ArrayList<>();
        if (duration <= 0) return out;
        if (constantSpeed != null) {
            out.add(new Piece(0, duration, 0, duration * constantSpeed));
            return out;
        }
        int count = Math.max(1, (int) Math.ceil(duration / maxPieceSeconds));
        double previous = 0;
        for (int i = 1; i <= count; i++) {
            double b = i == count ? duration : duration * i / count;
            out.add(new Piece(previous, b, sourceOffset(previous), sourceOffset(b)));
            previous = b;
        }
        return out;
    }
}
