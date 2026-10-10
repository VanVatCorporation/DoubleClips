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
    /** Motion-blur streak length as a fraction of the canvas WIDTH (0 = none). */
    public float motionBlurWidthFraction() { return get(ClipAnimation.Channel.MOTION_BLUR); }
    /** Horizontal edge slide as a fraction of the canvas WIDTH (+ = right). */
    public float edgeSlideFraction() { return get(ClipAnimation.Channel.EDGE_SLIDE); }
    /** Vertical edge slide as a fraction of the canvas HEIGHT (+ = down). */
    public float edgeSlideYFraction() { return get(ClipAnimation.Channel.EDGE_SLIDE_Y); }
    /** Brightness multiplier, 1 = unchanged. */
    public float exposure() { return get(ClipAnimation.Channel.EXPOSURE); }
    /** Motion-blur direction in degrees (0 = horizontal). */
    public float blurAngleDegrees() { return get(ClipAnimation.Channel.BLUR_ANGLE); }
    /** Horizontal squeeze factor, 1 = unchanged. */
    public float squeezeX() { return get(ClipAnimation.Channel.SQUEEZE_X); }
    /** Squeeze anchor, 0 = left edge, 1 = right edge. */
    public float squeezeAnchor() { return get(ClipAnimation.Channel.SQUEEZE_ANCHOR); }
    /** True when the picture's edge pixels should fill the whole canvas. */
    public boolean edgeFill() { return get(ClipAnimation.Channel.EDGE_FILL) > 0.5f; }
    /** Red colour cast, 0 = none .. 1 = strong. */
    public float redTint() { return get(ClipAnimation.Channel.RED_TINT); }
    /** Wash-out toward white, 0 = none .. 1 = all white. */
    public float whiteMix() { return get(ClipAnimation.Channel.WHITE_MIX); }

    /**
     * This frame combined with {@code other} (a transition style's side) the way the two combine with a clip's own
     * properties, same rules as iOS: additive channels add, multiplicative ones multiply (all clamped to their range),
     * the streak angle follows the longer streak, the squeeze anchor follows the stronger squeeze, edge fill takes
     * the larger. Returns {@code this} / {@code other} unchanged when the other one is neutral.
     */
    public ClipAnimationFrame merged(ClipAnimationFrame other) {
        if (other == null || other.isNeutral()) return this;
        if (isNeutral()) return other;
        ClipAnimation.Channel[] channels = ClipAnimation.Channel.values();
        float[] out = new float[channels.length];
        for (ClipAnimation.Channel c : channels) {
            double x = get(c), y = other.get(c), r;
            switch (c) {
                case OFFSET_X: case OFFSET_Y: case ROTATION: case HUE: case BRIGHTNESS: case TEMPERATURE:
                case EDGE_SLIDE: case EDGE_SLIDE_Y: case MOTION_BLUR: case BLUR: case RED_TINT: case WHITE_MIX:
                    r = x + y;
                    break;
                case BLUR_ANGLE:
                    r = motionBlurWidthFraction() >= other.motionBlurWidthFraction() ? x : y;
                    break;
                case SQUEEZE_ANCHOR:
                    r = Math.abs(squeezeX() - 1f) >= Math.abs(other.squeezeX() - 1f) ? x : y;
                    break;
                case EDGE_FILL:
                    r = Math.max(x, y);
                    break;
                default: // opacity, scale, saturation, contrast, exposure, warp.*, squeezeX
                    r = x * y;
                    break;
            }
            out[c.ordinal()] = (float) Math.min(Math.max(r, c.min), c.max);
        }
        return new ClipAnimationFrame(out);
    }

    /**
     * This frame with every channel's distance from its neutral value multiplied by {@code amount} (clamped to the
     * channel's range): a 12% push becomes 24% at amount 2. This is what an effect's strength slider does. The streak
     * angle, the squeeze anchor and edge fill are not "amounts", so they stay as they are. Same rule as iOS.
     */
    public ClipAnimationFrame scaledDeviation(float amount) {
        if (isNeutral() || Math.abs(amount - 1f) < 0.0001f) return this;
        float[] out = new float[v.length];
        for (ClipAnimation.Channel c : ClipAnimation.Channel.values()) {
            int i = c.ordinal();
            if (c == ClipAnimation.Channel.BLUR_ANGLE || c == ClipAnimation.Channel.SQUEEZE_ANCHOR
                    || c == ClipAnimation.Channel.EDGE_FILL) {
                out[i] = v[i];
                continue;
            }
            double n = c.neutral;
            out[i] = (float) Math.min(Math.max(n + (v[i] - n) * amount, c.min), c.max);
        }
        return new ClipAnimationFrame(out);
    }
}
