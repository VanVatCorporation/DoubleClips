package com.vanvatcorporation.doubleclips;

/**
 * The values of every animatable channel for ONE output frame, as produced by
 * {@link ClipAnimation#evaluate(float)}. Immutable. A channel an animation does not
 * define sits at its neutral value, so "no animation" and "animation outside its window"
 * are both just {@link #NEUTRAL}.
 *
 * HOW EACH VALUE IS COMBINED WITH THE CLIP'S OWN (keyframed) PROPERTIES is fixed in code,
 * never chosen by an animation file (see OpenGLEdit.buildDrawCommand):
 *   additive       offsetX, offsetY, rotation, hue, brightness, temperature
 *   multiplicative opacity, scale, saturation
 *   standalone     contrast, blur, warp.* (the clip itself has no such property)
 * Plain Java (no Android classes) so the desktop port can use it as-is.
 */
public final class ClipAnimationFrame {

    /** Every channel at its neutral value. */
    public static final ClipAnimationFrame NEUTRAL = new ClipAnimationFrame(neutralValues());

    private final float[] v;

    ClipAnimationFrame(float[] values) {
        this.v = values;
    }

    static float[] neutralValues() {
        ClipAnimation.Channel[] all = ClipAnimation.Channel.values();
        float[] a = new float[all.length];
        for (ClipAnimation.Channel c : all) a[c.ordinal()] = (float) c.neutral;
        return a;
    }

    public float get(ClipAnimation.Channel channel) { return v[channel.ordinal()]; }

    /** True when nothing is animated this frame (callers may skip all extra work). */
    public boolean isNeutral() { return this == NEUTRAL; }

    /** Multiplier, 1 = unchanged. */
    public float opacity() { return get(ClipAnimation.Channel.OPACITY); }
    /** Uniform scale multiplier about the clip's pivot, 1 = unchanged. */
    public float scale() { return get(ClipAnimation.Channel.SCALE); }
    /** Offset as a fraction of the canvas WIDTH (+ = right). */
    public float offsetX() { return get(ClipAnimation.Channel.OFFSET_X); }
    /** Offset as a fraction of the canvas HEIGHT (+ = down). */
    public float offsetY() { return get(ClipAnimation.Channel.OFFSET_Y); }
    /** Extra rotation in degrees. */
    public float rotationDegrees() { return get(ClipAnimation.Channel.ROTATION); }
    /** Extra hue rotation in degrees. */
    public float hueDegrees() { return get(ClipAnimation.Channel.HUE); }
    /** Multiplier, 1 = unchanged. */
    public float saturation() { return get(ClipAnimation.Channel.SATURATION); }
    /** Additive, same -10..10 scale as VideoProperties.Brightness. */
    public float brightness() { return get(ClipAnimation.Channel.BRIGHTNESS); }
    /** Multiplier about mid-grey, 1 = unchanged. */
    public float contrast() { return get(ClipAnimation.Channel.CONTRAST); }
    /** Additive colour-temperature offset in Kelvin (0 = unchanged). */
    public float temperatureKelvin() { return get(ClipAnimation.Channel.TEMPERATURE); }
    /** Gaussian blur sigma as a fraction of the canvas WIDTH (0 = none). */
    public float blurWidthFraction() { return get(ClipAnimation.Channel.BLUR); }
    /** Top-edge width of the top-centre-anchored squish, 1 = unchanged. */
    public float warpTopWidth() { return get(ClipAnimation.Channel.WARP_TOP_WIDTH); }
    /** Bottom-edge width of the squish, 1 = unchanged. */
    public float warpBottomWidth() { return get(ClipAnimation.Channel.WARP_BOTTOM_WIDTH); }
    /** Height of the squish (top edge fixed), 1 = unchanged. */
    public float warpHeight() { return get(ClipAnimation.Channel.WARP_HEIGHT); }
}
