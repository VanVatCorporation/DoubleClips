package com.vanvatcorporation.doubleclips;

import java.util.Locale;

/** Plain Java: the arithmetic behind the template preview strip (so it can be checked off-device). */
public final class TemplateStripMath {
    private TemplateStripMath() {}

    /** "m:ss" (seconds rounded down; negative -> 0:00). */
    public static String clock(double seconds) {
        int s = Math.max(0, (int) Math.floor(seconds));
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** The time under the playhead while dragging: dragging the strip LEFT moves time FORWARD. Clamped to [0, duration]. */
    public static double scrubTime(double timeAtDragStart, double dragDx, double pixelsPerSecond, double duration) {
        double t = timeAtDragStart - dragDx / pixelsPerSecond;
        return Math.min(Math.max(t, 0), Math.max(duration, 0));
    }

    /** Height of one track row: the strip's height less 8 of padding and the gaps, shared by the rows, kept within [min, max]. */
    public static float rowHeight(float stripHeight, int rows, float gap, float min, float max) {
        int r = Math.max(rows, 1);
        float raw = (stripHeight - 8f - (r - 1) * gap) / r;
        return Math.min(Math.max(raw, min), max);
    }

    /** x of a clip's left edge inside the strip: the current time sits under the middle of the strip. */
    public static float clipLeft(float stripWidth, double currentTime, double clipStart, float pixelsPerSecond) {
        return (float) (stripWidth / 2f - currentTime * pixelsPerSecond + clipStart * pixelsPerSecond);
    }
}
