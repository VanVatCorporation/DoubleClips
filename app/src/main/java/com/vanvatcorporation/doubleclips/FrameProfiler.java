package com.vanvatcorporation.doubleclips;

import java.util.Locale;

/**
 * Where does an export spend its time? Accumulates time per stage (decoding the source clips, encoding, and
 * everything else) and, every {@code reportEvery} frames, hands one readable line to a sink:
 * <pre>perf: 66.4 fps over the last 60 frames | per frame: decode 9.2 ms, encode 4.1 ms, draw+other 1.8 ms = 15.1 ms | slowest frame 41 ms</pre>
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

    private final long[] stageNanos = new long[STAGES];
    private final long[] stageStart = new long[STAGES];
    private final boolean[] stageOpen = new boolean[STAGES];

    private long frameStartNanos;
    private long windowFrames;
    private long windowNanos;
    private long windowSlowest;

    private final long[] totalStageNanos = new long[STAGES];
    private long totalFrames;
    private long totalNanos;
    private long totalSlowest;

    public FrameProfiler(int reportEvery, Clock clock, Sink sink) {
        this.reportEvery = Math.max(1, reportEvery);
        this.clock = clock;
        this.sink = sink;
    }

    public void frameStart() {
        frameStartNanos = clock.nanos();
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
        long frameNanos = clock.nanos() - frameStartNanos;
        windowFrames++;
        windowNanos += frameNanos;
        windowSlowest = Math.max(windowSlowest, frameNanos);
        if (windowFrames >= reportEvery) {
            sink.line(format("perf", windowFrames, windowNanos, stageNanos, windowSlowest));
            for (int i = 0; i < STAGES; i++) {
                totalStageNanos[i] += stageNanos[i];
                stageNanos[i] = 0;
            }
            totalFrames += windowFrames;
            totalNanos += windowNanos;
            totalSlowest = Math.max(totalSlowest, windowSlowest);
            windowFrames = 0;
            windowNanos = 0;
            windowSlowest = 0;
        }
    }

    /** One line for the whole export (call once at the end). Includes the unfinished last window. */
    public String summary() {
        long frames = totalFrames + windowFrames, nanos = totalNanos + windowNanos;
        long[] stages = new long[STAGES];
        for (int i = 0; i < STAGES; i++) stages[i] = totalStageNanos[i] + stageNanos[i];
        return format("perf summary", frames, nanos, stages, Math.max(totalSlowest, windowSlowest));
    }

    private static String format(String label, long frames, long nanos, long[] stages, long slowest) {
        if (frames <= 0 || nanos <= 0) return label + ": no frames";
        double seconds = nanos / 1e9;
        double fps = frames / seconds;
        double perFrameMs = nanos / 1e6 / frames;
        double decodeMs = stages[DECODE] / 1e6 / frames, encodeMs = stages[ENCODE] / 1e6 / frames;
        double otherMs = Math.max(0, perFrameMs - decodeMs - encodeMs);
        return String.format(Locale.US, "%s: %.1f fps over %d frames | per frame: decode %.1f ms, encode %.1f ms, draw+other %.1f ms = %.1f ms | slowest frame %.0f ms",
                label, fps, frames, decodeMs, encodeMs, otherMs, perFrameMs, slowest / 1e6);
    }
}
