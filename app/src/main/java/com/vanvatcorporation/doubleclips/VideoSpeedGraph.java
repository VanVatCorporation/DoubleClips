package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The video half of {@link AudioSpeedGraph}: the FFmpeg {@code setpts} expression that plays a clip's source at its speed CURVE.
 * <p>
 * The old export wrote {@code setpts=(PTS-STARTPTS)/speed(t)}, which is only right when the speed never changes: it reads the
 * speed at an instant and divides the source time by it, instead of integrating. Here the clip's {@link ClipTimeMap} (the same
 * integral the OpenGL engine, the preview and {@link AudioSpeedGraph} use) is cut into pieces of nearly constant speed
 * ({@link ClipTimeMap#pieces}); the expression maps a frame's SOURCE time {@code S} (seconds after the trim) to its TIMELINE
 * time through those pieces, so picture, sound and the OpenGL export all agree.
 * <p>
 * The pieces are tested through a balanced {@code if(lt(S,..),..)} tree, not a chain, so a clip with 200 pieces nests about
 * 8 deep. Plain Java on {@link ClipTimeMap}, no FFmpeg or UI types, so it can be checked on its own.
 */
public final class VideoSpeedGraph {

    /** Longest piece of a changing speed, in timeline seconds... */
    public static final double MAX_PIECE_SECONDS = AudioSpeedGraph.MAX_PIECE_SECONDS;
    /** ...but never more pieces than this for one clip (a long clip gets longer pieces rather than a huge expression). */
    public static final int MAX_PIECES = 120;
    /** Neighbouring pieces whose speeds differ by less than this (relative) are one piece: a flat stretch of a curve stays short. */
    private static final double MERGE_TOLERANCE = 1e-4;

    private VideoSpeedGraph() {}

    /** A knot of the source-time to timeline-time line: source seconds (after the trim) -> timeline seconds. */
    private static final class Segment {
        final double s0, a0, slope; // timeline = a0 + (S - s0) * slope, from s0 on
        Segment(double s0, double a0, double slope) { this.s0 = s0; this.a0 = a0; this.slope = slope; }
    }

    /**
     * The body of {@code setpts='...'} that places a source frame at its timeline time plus {@code shiftSeconds} (the clip's
     * start), or null when the clip plays at one constant speed (the caller keeps its plain {@code (PTS-STARTPTS)/speed}).
     * {@code timelineDuration} is the clip's length on the timeline; frames past it (a transition's extension) continue at the
     * last piece's speed.
     */
    public static String setptsBody(ClipTimeMap map, double timelineDuration, double shiftSeconds) {
        List<Segment> segments = segments(map, timelineDuration);
        if (segments == null) return null;
        String source = "(PTS-STARTPTS)*TB";
        String body = tree(segments, 0, segments.size() - 1, source);
        // The expression is in seconds; PTS is in time-base units.
        return "(" + body + "+" + num(shiftSeconds) + ")/TB";
    }

    /** The pieces as segments, merged where the speed does not change; null for a constant speed. */
    static List<Segment> segments(ClipTimeMap map, double timelineDuration) {
        if (map == null || map.isConstant() || timelineDuration <= 0) return null;
        double maxPiece = Math.max(MAX_PIECE_SECONDS, timelineDuration / MAX_PIECES);
        List<ClipTimeMap.Piece> pieces = map.pieces(timelineDuration, maxPiece);
        if (pieces.isEmpty()) return null;

        List<Segment> out = new ArrayList<>();
        for (ClipTimeMap.Piece piece : pieces) {
            if (piece.s1 - piece.s0 <= 1e-9 || piece.b - piece.a <= 1e-9) continue; // a piece that consumes nothing has no slope
            double slope = (piece.b - piece.a) / (piece.s1 - piece.s0); // timeline seconds per source second = 1 / speed
            if (!out.isEmpty()) {
                Segment last = out.get(out.size() - 1);
                if (Math.abs(last.slope - slope) <= MERGE_TOLERANCE * Math.max(last.slope, slope)) continue;
            }
            out.add(new Segment(piece.s0, piece.a, slope));
        }
        if (out.isEmpty()) return null;
        // The first segment also covers anything before the clip's first frame (a negative source time).
        return out;
    }

    private static String tree(List<Segment> segments, int lo, int hi, String source) {
        if (lo == hi) {
            Segment s = segments.get(lo);
            return num(s.a0) + "+(" + source + "-" + num(s.s0) + ")*" + num(s.slope);
        }
        int mid = (lo + hi + 1) >>> 1; // first segment of the upper half
        return "if(lt(" + source + "," + num(segments.get(mid).s0) + ")," + tree(segments, lo, mid - 1, source) + ","
                + tree(segments, mid, hi, source) + ")";
    }

    private static String num(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
