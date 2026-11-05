package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks for VideoSpeedGraph: the generated setpts body is evaluated numerically (FFmpegExprEval) and compared with
 * ClipTimeMap, the integral the OpenGL export and the audio use. Plain Java; one message per failed check.
 */
public final class VideoSpeedGraphChecks {

    private VideoSpeedGraphChecks() {}

    /** Timeline seconds the expression gives a source frame at {@code source} seconds after the trim. */
    private static double place(String body, double source, double timeBase) {
        // PTS is in time-base units; setpts returns time-base units too.
        double pts = source / timeBase;
        Map<String, Double> vars = FFmpegExprEval.vars("PTS", pts, "STARTPTS", 0, "TB", timeBase);
        return FFmpegExprEval.eval(body, vars) * timeBase;
    }

    public static List<String> runAll() {
        List<String> fails = new ArrayList<>();
        final double tb = 1.0 / 25.0;

        // a constant speed keeps the plain (PTS-STARTPTS)/speed
        if (VideoSpeedGraph.setptsBody(ClipTimeMap.constant(2), 5, 0) != null) fails.add("a constant speed must return null");
        if (VideoSpeedGraph.setptsBody(null, 5, 0) != null) fails.add("no map must return null");

        // 1x -> 4x over 4 s, then 4x
        ClipTimeMap ramp = ClipTimeMap.curve(t -> t < 4 ? 1 + 3 * t / 4.0 : 4.0);
        double duration = 6.0, shift = 2.0;
        String body = VideoSpeedGraph.setptsBody(ramp, duration, shift);
        if (body == null) { fails.add("a ramp must produce an expression"); return fails; }
        for (double source : new double[]{0, 1, 2.5, 5, 8, 10, 14, 17.9}) {
            double want = shift + ramp.localTime(source);
            double got = place(body, source, tb);
            if (Math.abs(want - got) > 0.01) fails.add("source " + source + "s should land at " + want + "s on the timeline, got " + got);
        }
        // frames past the clip's end (a transition's extension) keep going at the last speed
        double past = place(body, 19.0, tb), last = place(body, 18.0, tb);
        if (Math.abs((past - last) - 0.25) > 0.01) fails.add("past the end the last speed (4x) should go on: 1 source second = 0.25 s, got " + (past - last));

        // a flat stretch is one piece: two keys with the same value make a short expression
        ClipTimeMap flat = ClipTimeMap.curve(t -> 2.0);
        String flatBody = VideoSpeedGraph.setptsBody(flat, 10, 0);
        if (flatBody == null || flatBody.contains("if(")) fails.add("an unchanging speed should merge to one piece, got " + flatBody);
        if (flatBody != null && Math.abs(place(flatBody, 8, tb) - 4.0) > 0.01) fails.add("2x: 8 source seconds should take 4 s");

        // a long, wiggly curve stays bounded
        ClipTimeMap wiggle = ClipTimeMap.curve(t -> 2 + Math.sin(t * 3));
        String big = VideoSpeedGraph.setptsBody(wiggle, 600, 0);
        if (big == null) fails.add("a wiggly curve must produce an expression");
        else {
            if (big.length() > 30000) fails.add("a 10 minute curve made an expression of " + big.length() + " characters");
            if (Math.abs(place(big, 100, tb) - wiggle.localTime(100)) > 0.5) fails.add("the 10 minute curve drifts at source 100 s");
        }
        return fails;
    }
}
