package com.vanvatcorporation.doubleclips;

/**
 * The numbers of a text bitmap's layout that don't need any drawing: how much room goes around the letters, where a
 * wrapping text breaks, how tall several lines are. They follow iOS's TextRenderer.metrics so both platforms size a text
 * block the same way. Plain Java.
 */
public final class TextLayoutMath {

    private TextLayoutMath() { }

    /** Room around a piece of text for its outline and shadow: 2 + outline + shadow reach (iOS: ceil(2 + outline + reach)). */
    public static int pad(TextStyle style) {
        return 2 + (int) Math.ceil(Math.max(0f, style.outlineWidth) + style.shadowReach());
    }

    /** The same plus the background box's padding, for a whole text block. */
    public static int blockPad(TextStyle style) {
        float box = style.hasBackgroundBox() ? Math.max(0f, style.backgroundPadding) : 0f;
        return 2 + (int) Math.ceil(Math.max(0f, style.outlineWidth) + style.shadowReach() + box);
    }

    /** The widest a line may be: wrapWidth x the canvas width, at least 20 px; no limit (MAX_VALUE) when wrapWidth is 0. */
    public static int wrapLimit(float wrapWidth, int canvasWidth) {
        return wrapWidth > 0.01f ? Math.max(20, Math.round(wrapWidth * canvasWidth)) : Integer.MAX_VALUE;
    }

    /** Height of a block of {@code lineCount} lines: line spacing goes BETWEEN lines only (it can be negative). */
    public static int blockHeight(int layoutHeight, int lineCount, float lineSpacing) {
        return Math.max(1, layoutHeight + Math.round(lineSpacing * Math.max(0, lineCount - 1)));
    }

    /** Corner radius of the background box: as asked, but never more than half the box's shorter side. */
    public static float boxRadius(float radius, float boxWidth, float boxHeight) {
        return Math.min(Math.max(0f, radius), Math.min(boxWidth, boxHeight) / 2f);
    }
}
