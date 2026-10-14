package com.vanvatcorporation.doubleclips;

/**
 * A clip's mask for one frame: the resolved shape plus the size, in canvas pixels, of the box it works in (the clip as
 * drawn, scaled and before it is rotated and moved; for an effect clip the whole canvas). Carried by a DrawCommand /
 * EffectCommand to whoever draws it. Plain Java.
 */
public final class ClipMaskFrame {
    public final ClipMaskResolver.State state;
    public final float boxW, boxH;

    public ClipMaskFrame(ClipMaskResolver.State state, float boxW, float boxH) {
        this.state = state;
        this.boxW = boxW;
        this.boxH = boxH;
    }
}
