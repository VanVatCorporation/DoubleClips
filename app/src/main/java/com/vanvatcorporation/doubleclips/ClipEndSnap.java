package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.helper.FrameHelper;

/**
 * Puts a clip's END (start + duration) on a frame, as its start already is. Before this only the start was snapped (every refresh,
 * {@code Track.reassignClips}), so a clip trimmed by its right handle - or one whose length isn't a whole number of frames - ended
 * between two frames while the next clip started on one, and a hairline gap showed on the track.
 * <p>
 * The start stays where it is. For a video / audio clip the end trim follows the length change in SOURCE seconds (through the
 * clip's speed), and the end never moves past the end of the source: if the nearest frame would need more source than is left, the
 * frame before it is used. Plain Java, so it can be checked on its own.
 */
public final class ClipEndSnap {

    private ClipEndSnap() {}

    /** The new length and end trim; {@code changed} is false when the end was already on a frame. */
    public static final class Result {
        public final float duration;
        public final float endClipTrim;
        public final boolean changed;

        Result(float duration, float endClipTrim, boolean changed) {
            this.duration = duration;
            this.endClipTrim = endClipTrim;
            this.changed = changed;
        }
    }

    /**
     * @param startTime   the clip's start (already on a frame)
     * @param duration    its length now
     * @param endClipTrim its end trim now (only used when {@code trims})
     * @param map         the clip's speed map at its start (only used when {@code trims})
     * @param trims       true for video / audio: the end trim follows the length (false for text, images, effects)
     */
    public static Result snap(float startTime, float duration, float endClipTrim, int fps, ClipTimeMap map, boolean trims) {
        if (fps <= 0 || duration <= 0) return new Result(duration, endClipTrim, false);
        float frame = 1f / fps;
        float end = startTime + duration;
        float snappedEnd = FrameHelper.calculateToNearestFrame(end, fps);
        float newDuration = Math.max(frame, snappedEnd - startTime);
        if (Math.abs(newDuration - duration) < 1e-6f) return new Result(duration, endClipTrim, false);
        if (!trims || map == null) return new Result(newDuration, endClipTrim, true);

        double base = map.sourceOffset(duration);
        double s = map.sourceOffset(newDuration) - base;           // source seconds the clip now takes more (+) or gives back (-) at its end
        if (endClipTrim - s < -1e-4) {
            // not enough source left for the nearest frame: use the frame before (a shorter clip always fits)
            float floorEnd = (float) (Math.floor(end * (double) fps + 1e-4) / fps);
            newDuration = Math.max(frame, floorEnd - startTime);
            s = map.sourceOffset(newDuration) - base;
            if (Math.abs(newDuration - duration) < 1e-6f) return new Result(duration, endClipTrim, false);
        }
        return new Result(newDuration, Math.max(0f, endClipTrim - (float) s), true);
    }
}
