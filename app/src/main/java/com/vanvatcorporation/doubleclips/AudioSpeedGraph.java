package com.vanvatcorporation.doubleclips;

import java.util.List;
import java.util.Locale;

/**
 * A clip's audio at the clip's speed, as FFmpeg filter statements, for the export's audio graph (the FFmpeg export and the
 * audio pass of the OpenGL export build it the same way). Before this the export cut {@code duration} seconds of the
 * source whatever the speed, so a clip at 2x kept only the first half of what the preview plays and ended up the wrong
 * length; now it takes the source the clip actually consumes ({@link ClipTimeMap}) and plays it at that speed.
 *
 * <p>It matches the preview mixer (AudioEngine): VARISPEED, i.e. a faster clip is higher-pitched ({@code asetrate}, then back
 * to the common rate), at 44100 Hz like the mixer. A constant speed is one cut of the source; a speed that changes (keyframes)
 * is cut into pieces of nearly constant speed ({@link ClipTimeMap#pieces}), each played at its own average speed and joined
 * with {@code concat}, so the pitch follows the curve in small steps.
 *
 * <p>Plain Java on {@link ClipTimeMap}, no FFmpeg or UI types, so it can be checked on its own.
 */
public final class AudioSpeedGraph {

    /** The rate the speed pieces are played and handed on at (the preview mixer's rate). */
    public static final int SAMPLE_RATE = 44100;
    /** Longest piece of a changing speed, in timeline seconds... */
    public static final double MAX_PIECE_SECONDS = 0.25;
    /** ...but never more pieces than this for one clip (a long clip gets longer pieces rather than a huge graph). */
    public static final int MAX_PIECES = 200;

    private AudioSpeedGraph() {}

    /**
     * The statements (each ending {@code ;\n}) that read {@code inputLabel} (e.g. {@code [3:a]}), take the source from
     * {@code trimSeconds} on that {@code timelineDuration} seconds of the timeline consume at {@code map}'s speed, play it at
     * that speed from time 0, and leave it under {@code outputLabel}. {@code stem} names the intermediate labels (unique per
     * clip). Returns null when the clip plays at speed 1: the caller then keeps its plain {@code atrim}.
     */
    public static String statements(String inputLabel, String outputLabel, String stem, double trimSeconds, ClipTimeMap map,
                                    double timelineDuration) {
        if (map == null || map.isIdentity() || timelineDuration <= 0) return null;

        double maxPiece = Math.max(MAX_PIECE_SECONDS, timelineDuration / MAX_PIECES);
        List<ClipTimeMap.Piece> pieces = map.pieces(timelineDuration, maxPiece);
        if (pieces.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        if (pieces.size() == 1) {
            sb.append(inputLabel).append(cut(trimSeconds, pieces.get(0))).append(outputLabel).append(";\n");
            return sb.toString();
        }

        // an input pad can be read once: the pieces each need their own copy of the stream
        sb.append(inputLabel).append("asplit=").append(pieces.size());
        for (int i = 0; i < pieces.size(); i++) sb.append("[").append(stem).append("p").append(i).append("]");
        sb.append(";\n");
        for (int i = 0; i < pieces.size(); i++) {
            sb.append("[").append(stem).append("p").append(i).append("]").append(cut(trimSeconds, pieces.get(i)))
                    .append("[").append(stem).append("q").append(i).append("];\n");
        }
        for (int i = 0; i < pieces.size(); i++) sb.append("[").append(stem).append("q").append(i).append("]");
        sb.append("concat=n=").append(pieces.size()).append(":v=0:a=1").append(outputLabel).append(";\n");
        return sb.toString();
    }

    /** One cut at one speed: the source span, from time 0, pitched by the speed and back at the common rate. */
    private static String cut(double trimSeconds, ClipTimeMap.Piece piece) {
        double speed = piece.speed();
        long rate = Math.max(1L, Math.round(SAMPLE_RATE * speed));
        return String.format(Locale.US, "atrim=start=%.6f:end=%.6f,asetpts=PTS-STARTPTS,aresample=%d,asetrate=%d,aresample=%d",
                trimSeconds + piece.s0, trimSeconds + piece.s1, SAMPLE_RATE, rate, SAMPLE_RATE);
    }
}
