package com.vanvatcorporation.doubleclips;

import java.nio.ByteBuffer;

/**
 * The parts of a clip's optical flow that are plain arithmetic (no OpenGL): where on the frame-rate grid a moment falls, how
 * deep the motion search pyramid is for a picture size, and whether two frames are a scene cut. {@link OpticalFlowGl} does the
 * pixels with them; OpticalFlowMathTest checks them.
 */
public final class OpticalFlowMath {

    private OpticalFlowMath() {}

    /** The finest flow level is about this many pixels on its long side for an export (a quarter of 1080p). */
    public static final int EXPORT_FLOW_SIDE = 480;
    /** ... and for the live preview, which has to keep up with playback. */
    public static final int PREVIEW_FLOW_SIDE = 320;
    /** The pyramid never goes deeper than this many levels, and never below this many pixels on its short side. */
    public static final int MAX_LEVELS = 4, MIN_SIDE = 24;

    /**
     * The start of the grid frame that {@code elapsed} (seconds since the clip's first frame, on the timeline, after its speed
     * is applied) falls in, for frames made {@code fps} times a second: ffmpeg's {@code minterpolate=fps=N} makes its frames at
     * 0, 1/N, 2/N ... from the first one. Before the clip starts (a transition's pre-roll) nothing is snapped.
     */
    public static double snapToGrid(double elapsed, double fps) {
        if (elapsed <= 0 || !(fps > 0) || Double.isInfinite(fps)) return elapsed;
        return Math.floor(elapsed * fps + 1e-6) / fps;
    }

    /** The position of a source time between the clip's frames: frame index = floor, the fraction = how far to the next one. */
    public static double framePosition(double sourceSeconds, double sourceFps) {
        return Math.max(0.0, sourceSeconds) * sourceFps + 1e-4;
    }

    /** A fraction this close to a frame is that frame (no flow needed, and the cheap path is exact). */
    public static final double SNAP_ALPHA = 0.02;

    /** What to draw for a position between two frames. */
    public enum Mode { FIRST, SECOND, MIX }

    public static Mode modeFor(double alpha) {
        if (alpha < SNAP_ALPHA) return Mode.FIRST;
        if (alpha > 1.0 - SNAP_ALPHA) return Mode.SECOND;
        return Mode.MIX;
    }

    /**
     * The motion search runs on a pyramid: its finest level is the mip level of the picture whose long side is nearest above
     * {@code flowSide}; {@code levels} are stacked on it, fewer for a small picture so the coarsest one keeps at least
     * {@link #MIN_SIDE} pixels on its short side.
     *
     * @return {finest mip level, number of levels}
     */
    public static int[] plan(int width, int height, int flowSide) {
        int longSide = Math.max(width, height), shortSide = Math.min(width, height);
        int finest = 0;
        while ((longSide >> finest) > flowSide) finest++;
        int levels = MAX_LEVELS;
        while (levels > 1 && (shortSide >> (finest + levels - 1)) < MIN_SIDE) levels--;
        return new int[]{finest, levels};
    }

    /** The size of mip level {@code lod} of a width x height picture (what OpenGL's glGenerateMipmap makes). */
    public static int mipSize(int size, int lod) {
        return Math.max(1, size >> lod);
    }

    /**
     * A scene is cut between two frames when BOTH their 32 x 18 mosaics differ this much (mean luma, 0 ... 1) and their
     * brightness histograms (16 bins; half the sum of the bins' differences, 0 ... 1) do: a pan over a high contrast picture
     * moves the mosaic a lot but keeps the histogram, a fade moves the histogram a little but not the mosaic.
     */
    public static final double SCENE_CUT_MOSAIC = 0.15, SCENE_CUT_HISTOGRAM = 0.35;

    /**
     * True when {@code a} and {@code b} (RGBA, top row first, width * height * 4 bytes) are different shots, see
     * {@link #SCENE_CUT_MOSAIC}. A wrong "yes" only costs the in-between frame (the nearer frame is held), which is also what a
     * cut should do, so the limits are on the cautious side.
     */
    public static boolean isSceneCut(ByteBuffer a, ByteBuffer b, int width, int height) {
        final int cols = 32, rows = 18, sub = 4;
        if (width < cols || height < rows) return false;
        double total = 0;
        final int bins = 16;
        int[] ha = new int[bins], hb = new int[bins];
        ByteBuffer pa = a.duplicate(), pb = b.duplicate();
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                double sa = 0, sb = 0;
                for (int sy = 0; sy < sub; sy++) {
                    for (int sx = 0; sx < sub; sx++) {
                        int x = (int) ((cx + (sx + 0.5) / sub) * width / cols);
                        int y = (int) ((cy + (sy + 0.5) / sub) * height / rows);
                        int at = (y * width + x) * 4;
                        double la = luma(pa, at), lb = luma(pb, at);
                        sa += la;
                        sb += lb;
                        ha[Math.min(bins - 1, (int) (la * bins))]++;
                        hb[Math.min(bins - 1, (int) (lb * bins))]++;
                    }
                }
                total += Math.abs(sa - sb) / (sub * sub);
            }
        }
        if (total / (cols * rows) <= SCENE_CUT_MOSAIC) return false;
        double histogram = 0, n = (double) cols * rows * sub * sub;
        for (int i = 0; i < bins; i++) histogram += Math.abs(ha[i] - hb[i]) / n;
        return histogram / 2 > SCENE_CUT_HISTOGRAM;
    }

    private static double luma(ByteBuffer rgba, int at) {
        int r = rgba.get(at) & 0xFF, g = rgba.get(at + 1) & 0xFF, bl = rgba.get(at + 2) & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * bl) / 255.0;
    }
}
