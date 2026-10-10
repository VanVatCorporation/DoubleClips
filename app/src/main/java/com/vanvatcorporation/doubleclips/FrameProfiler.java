package com.vanvatcorporation.doubleclips;

import java.util.Locale;

/**
 * Where does an export spend its time? Accumulates time per stage (decoding the source clips, encoding, and
 * everything else) and, every {@code reportEvery} frames, hands one readable line to a sink:
 * <pre>perf: 66.4 fps over the last 60 frames | per frame: decode 9.2 ms, encode 4.1 ms, draw+other 1.8 ms = 15.1 ms | slowest frame 41 ms</pre>
 * "fps" is wall clock (first frame start to last frame end of the window), so a pause between frames shows up as a
 * lower fps instead of being hidden; the per-frame milliseconds are the time the frame was actually being worked on.
 * Plain Java with an injectable clock, so it is testable and the desktop port can use it as is.
 * Timing a stage costs two clock reads; leave it on, it is how a "why is it slow" report gets its numbers.
 */
public final class FrameProfiler {

    public static final int DECODE = 0, ENCODE = 1;
    private static final int STAGES = 2;

    public interface Clock { long nanos(); }

    public interface Sink { void line(String text); }

    private final int reportEvery;
    private final Clock clock;
    private final Sink sink;
    private final String label0, label1;
    /** A gap this long between two frames ends the measuring window (a pause, not slowness). Default: never. */
    private long idleResetNanos = Long.MAX_VALUE;
    private long lastFrameEndNanos = -1;
    private long windowStartNanos = -1;

    private final long[] stageNanos = new long[STAGES];
    private final long[] stageStart = new long[STAGES];
    private final boolean[] stageOpen = new boolean[STAGES];

    private long frameStartNanos;
    private long windowFrames;
    private long windowNanos;
    private long windowWallNanos;
    private long windowSlowest;

    private final long[] totalStageNanos = new long[STAGES];
    private long totalFrames;
    private long totalNanos;
    private long totalWallNanos;
    private long totalSlowest;

    public FrameProfiler(int reportEvery, Clock clock, Sink sink) {
        this(reportEvery, clock, sink, "decode", "encode");
    }

    /** {@code stage0} / {@code stage1} name the two timed stages in the report (an export: decode, encode). */
    public FrameProfiler(int reportEvery, Clock clock, Sink sink, String stage0, String stage1) {
        this.reportEvery = Math.max(1, reportEvery);
        this.clock = clock;
        this.sink = sink;
        this.label0 = stage0;
        this.label1 = stage1;
    }

    /** After a pause this long between frames the current window is dropped, so a stop doesn't read as slowness. */
    public FrameProfiler idleReset(long nanos) {
        this.idleResetNanos = nanos;
        return this;
    }

    public void frameStart() {
        long now = clock.nanos();
        if (lastFrameEndNanos >= 0 && now - lastFrameEndNanos > idleResetNanos) {
            // paused: forget the unfinished window (finished windows were already reported)
            windowFrames = 0;
            windowNanos = 0;
            windowSlowest = 0;
            windowStartNanos = -1;
            for (int i = 0; i < STAGES; i++) stageNanos[i] = 0;
        }
        if (windowStartNanos < 0) windowStartNanos = now;
        frameStartNanos = now;
    }

    public void begin(int stage) {
        stageStart[stage] = clock.nanos();
        stageOpen[stage] = true;
    }

    public void end(int stage) {
        if (!stageOpen[stage]) return;
        stageOpen[stage] = false;
        stageNanos[stage] += clock.nanos() - stageStart[stage];
    }

    /** Closes the frame; every {@code reportEvery} frames it reports the window and starts a new one. */
    public void frameEnd() {
        long now = clock.nanos();
        long frameNanos = now - frameStartNanos;
        lastFrameEndNanos = now;
        windowFrames++;
        windowNanos += frameNanos;
        windowWallNanos = now - windowStartNanos;
        windowSlowest = Math.max(windowSlowest, frameNanos);
        if (windowFrames >= reportEvery) {
            sink.line(format("perf", windowFrames, windowWallNanos, windowNanos, stageNanos, windowSlowest));
            for (int i = 0; i < STAGES; i++) {
                totalStageNanos[i] += stageNanos[i];
                stageNanos[i] = 0;
            }
            totalFrames += windowFrames;
            totalNanos += windowNanos;
            totalWallNanos += windowWallNanos;
            totalSlowest = Math.max(totalSlowest, windowSlowest);
            windowFrames = 0;
            windowNanos = 0;
            windowWallNanos = 0;
            windowSlowest = 0;
            windowStartNanos = -1;
        }
    }

    /** One line for the whole export (call once at the end). Includes the unfinished last window. */
    public String summary() {
        long frames = totalFrames + windowFrames, nanos = totalNanos + windowNanos;
        long wall = totalWallNanos + windowWallNanos;
        long[] stages = new long[STAGES];
        for (int i = 0; i < STAGES; i++) stages[i] = totalStageNanos[i] + stageNanos[i];
        return format("perf summary", frames, wall, nanos, stages, Math.max(totalSlowest, windowSlowest));
    }

    private String format(String label, long frames, long wallNanos, long busyNanos, long[] stages, long slowest) {
        if (frames <= 0 || busyNanos <= 0) return label + ": no frames";
        double fps = frames / (Math.max(wallNanos, busyNanos) / 1e9);
        double perFrameMs = busyNanos / 1e6 / frames;
        double ms0 = stages[DECODE] / 1e6 / frames, ms1 = stages[ENCODE] / 1e6 / frames;
        double otherMs = Math.max(0, perFrameMs - ms0 - ms1);
        return String.format(Locale.US, "%s: %.1f fps over %d frames | per frame: %s %.1f ms, %s %.1f ms, draw+other %.1f ms = %.1f ms | slowest frame %.0f ms",
                label, fps, frames, label0, ms0, label1, ms1, otherMs, perFrameMs, slowest / 1e6);
    }
}
