package com.vanvatcorporation.doubleclips;

/**
 * The numbers behind a reversed clip, kept free of any UI / GL. Same rules as iOS (EditingView+Reverse.swift) and Desktop (ReverseMap).
 * <p>
 * A reversed clip plays a reversed COPY of its whole source file, cached next to the source as {@code <clipName>.reversed.mp4}
 * (the same name on every platform, so a project that moves between the apps finds the copy it already has). The clip keeps its trims
 * and speed on the ORIGINAL's timeline; only the time handed to the decoder changes: time t of the original is time (copyLength - t)
 * in the copy.
 */
public final class ReverseMap {

    private ReverseMap() {}

    /** The cache file name of a source file's reversed copy. */
    public static final String SUFFIX = ".reversed.mp4";

    /** {@code clip.mp4} -> {@code clip.mp4.reversed.mp4}. */
    public static String cacheName(String sourceName) {
        return sourceName + SUFFIX;
    }

    /** True for a file name that is a reversed copy itself (never reverse those again). */
    public static boolean isCacheName(String name) {
        return name != null && name.endsWith(SUFFIX);
    }

    /**
     * Where the decoder should be in the reversed copy when the clip has played {@code sourceElapsed} seconds of source time. The clip's
     * used range is [startClipTrim, originalLength - endClipTrim] of the original and it plays backwards from its end, so the copy's
     * start offset is {@code copyLength - (originalLength - endClipTrim)}.
     *
     * @param copyVideoLength the reversed copy's VIDEO length (see {@link ReverseCopies}), not its container length
     * @param originalLength  {@code clip.originalDuration}, the source's length as the app measured it
     */
    public static double copyTime(double copyVideoLength, double originalLength, double endClipTrim, double sourceElapsed) {
        double t = copyVideoLength - (originalLength - endClipTrim) + sourceElapsed;
        return Math.max(0.0, t);
    }

    /**
     * A copy is only worth using when its length is close to the original's (a different length means it was cut short, e.g. the app
     * was closed while it was being made); the same tolerance iOS uses.
     */
    public static boolean plausibleLength(double copyLength, double originalLength) {
        if (copyLength <= 0 || originalLength <= 0) return false;
        double tolerance = Math.max(1.0, originalLength * 0.05);
        return Math.abs(copyLength - originalLength) <= tolerance;
    }

    /**
     * How many decoded frames the reverse pass may hold on the GPU at once: a window is decoded forward, kept as textures, and played
     * back to front into the encoder. Picked from a memory budget (RGBA, 4 bytes a pixel), between 4 and 90 frames.
     */
    public static int windowFrames(int width, int height) {
        long bytesPerFrame = Math.max(1L, (long) width) * Math.max(1L, (long) height) * 4L;
        long frames = 192_000_000L / bytesPerFrame;
        return (int) Math.max(4L, Math.min(90L, frames));
    }

    /** The video bitrate of the copy: a little above the source's (it is re-encoded once), never absurdly low or high. */
    public static int copyBitrate(long sourceBitrate, int width, int height, double fps) {
        double base = sourceBitrate > 0 ? sourceBitrate * 1.3 : width * (double) height * Math.max(1.0, fps) * 0.15;
        return (int) Math.max(2_000_000.0, Math.min(60_000_000.0, base));
    }
}
