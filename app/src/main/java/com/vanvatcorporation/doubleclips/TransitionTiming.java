package com.vanvatcorporation.doubleclips;

/**
 * The timing of a data-driven transition ({@link TransitionStyle}) for its three modes, the same maths as iOS's
 * TransitionPlan. Plain Java (no Android classes) so it can be unit-tested and reused by the desktop port.
 *
 * Let {@code cut} be where clip A ends (= where clip B starts) and {@code d} the transition duration:
 * <pre>
 *   END_FIRST     window [cut - 2d, cut - d)   A ends early, B has already started: B is drawn from the window's
 *                                              start (pre-roll) and A is gone at its end, so B shows alone in between
 *   OVERLAP       window [cut - d, cut)        B pre-rolls by d
 *   BEGIN_SECOND  window [cut, cut + d)        A post-rolls (keeps playing past its end) by d, B starts on time
 * </pre>
 * Inside the window exactly one picture is drawn: A while the window progress is below the style's cut, B after it.
 * Outside the window clip A is visible only until the window's end and clip B from the window's start, which is
 * what makes the pre-roll and post-roll happen.
 */
public final class TransitionTiming {

    /** Shortest transition that is drawn at all (iOS: Constants.TRANSITION_MIN_SECONDS). */
    public static final float MIN_SECONDS = 0.1f;
    /** How far B may start from where A ends and still count as the next clip (iOS: TRACK_CLIPS_SNAP_THRESHOLD_SECONDS). */
    public static final float ADJACENT_TOLERANCE_SECONDS = 0.1f;

    public enum Mode { END_FIRST, OVERLAP, BEGIN_SECOND }

    /** The window in output-timeline seconds. */
    public static final class Window {
        public final float start;
        public final float end;

        Window(float start, float end) {
            this.start = start;
            this.end = end;
        }

        public float duration() { return end - start; }

        /** Window progress 0..1 at output time t (clamped). */
        public float progressAt(float t) {
            float d = end - start;
            if (d <= 0f) return 1f;
            return Math.min(1f, Math.max(0f, (t - start) / d));
        }

        public boolean contains(float t) { return t >= start && t < end; }
    }

    private TransitionTiming() {}

    /** Longest blend that fits: OVERLAP needs A to last that long, END_FIRST twice that, BEGIN_SECOND needs B to last that long. */
    public static float maxDuration(Mode mode, float aDuration, float bDuration) {
        switch (mode) {
            case END_FIRST: return aDuration / 2f;
            case BEGIN_SECOND: return bDuration;
            case OVERLAP:
            default: return aDuration;
        }
    }

    /** The window of a transition of {@code duration} at the cut. */
    public static Window windowRange(Mode mode, float cut, float duration) {
        switch (mode) {
            case END_FIRST: return new Window(cut - 2f * duration, cut - duration);
            case BEGIN_SECOND: return new Window(cut, cut + duration);
            case OVERLAP:
            default: return new Window(cut - duration, cut);
        }
    }

    /**
     * The window for clip A (starting at {@code aStart}, lasting {@code aDuration}) followed by clip B, or null
     * when the transition is too short to draw once it is limited to what fits.
     */
    public static Window window(Mode mode, float aStart, float aDuration, float bDuration, float requestedDuration) {
        float limit = maxDuration(mode, aDuration, bDuration);
        float duration = Math.min(Math.max(requestedDuration, 0f), limit);
        if (!(duration >= MIN_SECONDS)) return null;
        return windowRange(mode, aStart + aDuration, duration);
    }
}
