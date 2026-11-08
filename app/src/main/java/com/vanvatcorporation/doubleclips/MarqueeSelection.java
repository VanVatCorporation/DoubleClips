package com.vanvatcorporation.doubleclips;

/**
 * Hit-testing for the selection rectangle ("marquee") of the timeline, kept free of views so it can be tested alone. The same rule as
 * Desktop: a clip is hit when the rectangle TOUCHES it (like a file manager), it does not have to be fully inside.
 * All rectangles are in the same coordinate space (the editor uses the tracks container, which scrolls with the content).
 */
public final class MarqueeSelection {
    private MarqueeSelection() {}

    /** True when the rectangle (left, top, right, bottom) touches the clip rectangle (clipLeft, clipTop, clipRight, clipBottom). */
    public static boolean touches(float left, float top, float right, float bottom,
                                  float clipLeft, float clipTop, float clipRight, float clipBottom) {
        return clipRight >= left && clipLeft <= right && clipBottom >= top && clipTop <= bottom;
    }
}
