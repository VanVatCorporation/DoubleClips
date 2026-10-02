package com.vanvatcorporation.doubleclips;

import java.util.Locale;

/**
 * The "unfold" in-animation, modelled from CapCut's own Unfold (30 fps reference
 * capture: frame 24 clean, frame 25 = hard cut into the effect, settled by ~frame 70,
 * i.e. 45 frames = 1.5 s at the default duration).
 *
 * Everything below was fitted per reference frame against the settled frame:
 *
 *  - GEOMETRY: a squish anchored at the TOP-CENTRE of the clip. At p = 0 the bottom edge is
 *    ~86% of normal width and the height ~96%; the picture then unfolds outward and
 *    downward, overshoots to ~+4% height around frames 32-34, undershoots to ~-1.4% around frames 47-53
 *    and settles by frame ~68 (a damped spring).
 *    The area a shrunken picture no longer covers is filled with edge pixels (edge clamp).
 *  - COLOUR FLASH: brightness +0.4 luma, saturation x1.68, contrast x1.72 on the first
 *    frame, easing out as a Gaussian over the whole animation (neutral at p = 1).
 *  - BLUR: Gaussian, sigma = 2.5% of the frame width on the first frame, decaying smoothly to
 *    ~0.5% by frame 36 and gone at frame 37. (An apparent snap-off at frame 35 in the
 *    capture is the screen-recording's keyframe sharpening, not part of the effect.)
 *
 * Progress p = elapsed / duration, so the whole shape stretches with the clip's
 * inAnimation.duration. Plain Java (no Android classes) so the desktop port can use it as-is.
 */
public final class UnfoldAnimation {

    private UnfoldAnimation() {}

    public static final String TYPE = "unfold";

    // ---- colour flash (Gaussian envelopes, least-squares fitted per frame) ------
    /** Same -10..10 scale as VideoProperties.Brightness (shaders multiply by 0.1 -> +0.397 luma). */
    private static final float PEAK_BRIGHTNESS = 3.97f;
    /** Saturation multiplier at p = 0 is 1 + this. */
    private static final float PEAK_SATURATION_BOOST = 0.676f;
    /** Contrast multiplier (about mid-grey) at p = 0 is 1 + this. */
    private static final float PEAK_CONTRAST_BOOST = 0.716f;
    private static final float TAU_BRIGHTNESS = 0.467f;
    private static final float TAU_SATURATION = 0.506f;
    private static final float TAU_CONTRAST = 0.494f;

    // ---- blur ------------------------------------------------------------------
    /** Reference frame count: knot k sits at p = k / REFERENCE_FRAMES (reference frame 25 + k). */
    private static final float REFERENCE_FRAMES = 45f;
    /** Gaussian sigma in pixels of the 640 px wide reference at knots k = 0..11 (frames 25..36). Gone at k = 12. */
    private static final float[] BLUR_SIGMA_640 = {16f, 14f, 12f, 10f, 8f, 7f, 6f, 5f, 4f, 4f, 3f, 3f};
    private static final float BLUR_END_KNOT = 12f;
    private static final float BLUR_REFERENCE_WIDTH = 640f;

    // ---- geometry (top-centre squish): knots k = frames 25+k, 1.0 past the last knot ---------
    /**
     * Overall height relative to normal, anchored at the TOP edge, knots k = 0..43 (frames 25..68): squished to
     * 0.955, overshoots to +4% around frame 33, undershoots to -1.4% around frames 47-53, settles at frame 68
     * (a damped spring). Widths settle by knot 17; all tables hold 1.0 past their last knot.
     */
    private static final float[] HEIGHT = {
            0.9550f, 0.9690f, 0.9800f, 0.9920f, 1.0130f, 1.0220f, 1.0350f, 1.0390f, 1.0410f,
            1.0380f, 1.0330f, 1.0330f, 1.0250f, 1.0210f, 1.0130f, 1.0080f, 1.0036f, 1.0010f,
            0.9950f, 0.9929f, 0.9910f, 0.9890f, 0.9877f, 0.9870f, 0.9860f, 0.9858f, 0.9860f,
            0.9870f, 0.9876f, 0.9890f, 0.9900f, 0.9915f, 0.9930f, 0.9940f, 0.9954f, 0.9960f,
            0.9970f, 0.9976f, 0.9980f, 0.9990f, 0.9994f, 0.9997f, 0.9999f, 1.0000f};

    /** Width of the TOP edge relative to normal (about the vertical centre line). */
    private static final float[] TOP_WIDTH = {0.976f, 0.987f, 0.990f, 0.992f, 0.992f, 0.992f, 0.998f, 0.999f, 0.999f,
            0.999f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f, 1.000f};
    /** Width of the BOTTOM edge relative to normal. */
    private static final float[] BOTTOM_WIDTH = {0.863f, 0.885f, 0.908f, 0.930f, 0.961f, 0.970f, 0.975f, 0.981f, 0.986f,
            0.990f, 0.994f, 0.994f, 0.995f, 0.995f, 0.996f, 0.998f, 0.999f, 1.000f};
    /** 0 = no squish at all, 1 = measured, >1 = exaggerated. Tuning knob. */
    private static final float SQUISH_GAIN = 1f;

    // FFmpeg eq calibration, regressed from eq's measured transfer curve (FFmpeg 6.1,
    // yuv420p, grey ramps over brightness 0..0.4 x contrast 1..1.8, residual 0.67 of 255).
    // These match the theory for 16..235 limited-range luma (gain 255/219, pivot code 128).
    /** Effective luma gain of eq's brightness parameter in full-range terms. */
    private static final double EQ_BRIGHTNESS_GAIN = 1.1631;
    /** eq's contrast pivot in 0..1 full-range luma, minus 0.5. */
    private static final double EQ_PIVOT_SHIFT = 0.0100;
    /** eq darkens by ~1.01 levels whenever it is not a pure pass-through; add that back (in eq brightness units). */
    private static final double EQ_BIAS_COMPENSATION = 1.011 / 255.0 / EQ_BRIGHTNESS_GAIN;

    // ---- helpers -----------------------------------------------------------------

    /** Progress 0..1 through the animation, or -1 when t is outside [start, start + duration). */
    public static float progress(float elapsedSeconds, float durationSeconds) {
        if (durationSeconds <= 0f || elapsedSeconds < 0f || elapsedSeconds >= durationSeconds) return -1f;
        return elapsedSeconds / durationSeconds;
    }

    /** exp(-(p/tau)^2), shifted/scaled so it is exactly 1 at p = 0 and exactly 0 at p = 1. */
    private static float envelope(float p, float tau) {
        double tail = Math.exp(-(1.0 / tau) * (1.0 / tau));
        double v = Math.exp(-(p / tau) * (p / tau));
        return (float) ((v - tail) / (1.0 - tail));
    }

    private static double envelopeTail(float tau) {
        return Math.exp(-(1.0 / tau) * (1.0 / tau));
    }

    /** Additive brightness in the app's -10..10 units. 0 outside the animation (p < 0). */
    public static float brightness(float p) {
        return p < 0f ? 0f : PEAK_BRIGHTNESS * envelope(p, TAU_BRIGHTNESS);
    }

    /** Saturation MULTIPLIER (1 = unchanged). */
    public static float saturationMultiplier(float p) {
        return p < 0f ? 1f : 1f + PEAK_SATURATION_BOOST * envelope(p, TAU_SATURATION);
    }

    /** Contrast MULTIPLIER about mid-grey (1 = unchanged). */
    public static float contrastMultiplier(float p) {
        return p < 0f ? 1f : 1f + PEAK_CONTRAST_BOOST * envelope(p, TAU_CONTRAST);
    }

    /**
     * Gaussian blur sigma as a fraction of the canvas WIDTH (so it scales with the
     * export resolution). Linear between the per-frame knots, reaching 0 at knot 12.
     */
    public static float blurSigmaFraction(float p) {
        if (p < 0f) return 0f;
        float k = p * REFERENCE_FRAMES;
        if (k >= BLUR_END_KNOT) return 0f;
        int last = BLUR_SIGMA_640.length - 1;
        float s;
        if (k >= last) {
            s = BLUR_SIGMA_640[last] * (BLUR_END_KNOT - k) / (BLUR_END_KNOT - last);
        } else {
            int i = (int) Math.floor(k);
            s = BLUR_SIGMA_640[i] + (BLUR_SIGMA_640[i + 1] - BLUR_SIGMA_640[i]) * (k - i);
        }
        return s / BLUR_REFERENCE_WIDTH;
    }

    private static float sampleKnots(float[] table, float p) {
        float k = p * REFERENCE_FRAMES;
        int last = table.length - 1;
        if (k >= last) return 1f;
        int i = (int) Math.floor(k);
        return table[i] + (table[i + 1] - table[i]) * (k - i);
    }

    private static float gain(float v) { return 1f + (v - 1f) * SQUISH_GAIN; }

    /** Height scale of the squish (1 = none), anchored at the top edge. */
    public static float heightScale(float p) { return p < 0f ? 1f : gain(sampleKnots(HEIGHT, p)); }
    /** Top-edge width scale (1 = none). */
    public static float topWidth(float p) { return p < 0f ? 1f : gain(sampleKnots(TOP_WIDTH, p)); }
    /** Bottom-edge width scale (1 = none). */
    public static float bottomWidth(float p) { return p < 0f ? 1f : gain(sampleKnots(BOTTOM_WIDTH, p)); }

    // ---- FFmpeg ------------------------------------------------------------------

    /**
     * Filters implementing the same look for FFmpegEdit, to be appended to a clip's
     * filter chain AFTER setpts (so t is the output-timeline time) and BEFORE the
     * clip's output label. Returns a string that starts with a comma.
     *
     *  - eq with eval=frame: brightness / contrast / saturation as continuous
     *    Gaussian expressions of t, enabled only inside the animation window.
     *  - a short chain of gblur filters, one per reference frame, each gated to its
     *    own time slice with enable= (FFmpeg has no per-frame sigma), so the blur
     *    steps down exactly like the table above and snaps off at p = 10/45.
     *
     * @param canvasWidth output canvas width in pixels (blur sigma is a fraction of it)
     */
    public static String ffmpegFilters(double startSeconds, double durationSeconds, int canvasWidth) {
        if (durationSeconds <= 0.0) return "";
        Locale l = Locale.US;
        String p = String.format(l, "((t-%.5f)/%.5f)", startSeconds, durationSeconds);
                // Same -0.0005 s nudge as the blur slices below, so a first frame stamped exactly on start is inside the window.
        String startTime = String.format(l, "%.5f", startSeconds - 0.0005);

        // FFmpeg's eq works on LIMITED-range luma code values (16..235) with its
        // contrast pivot at code 128, not on 0..1 luma with a pivot at 0.5 like the
        // OpenGL shader. Mapping the shader's  y' = c*(y-0.5) + 0.5 + B  onto eq:
        //   eq brightness = (B + EQ_PIVOT_SHIFT*(c-1)) / EQ_BRIGHTNESS_GAIN + EQ_BIAS_COMPENSATION
        // (checked against a software mirror of the shader on a real frame).
        String bExpr = String.format(l, "(%.5f*%s+%.5f*%s)/%.5f+%.5f",
                PEAK_BRIGHTNESS * 0.1f, envExpr(p, TAU_BRIGHTNESS),
                EQ_PIVOT_SHIFT * PEAK_CONTRAST_BOOST, envExpr(p, TAU_CONTRAST),
                EQ_BRIGHTNESS_GAIN, EQ_BIAS_COMPENSATION);

        StringBuilder sb = new StringBuilder();
        sb.append(",eq=eval=frame")
                .append(":brightness='").append(bExpr).append("'")
                .append(":contrast='1+").append(String.format(l, "%.5f", PEAK_CONTRAST_BOOST)).append("*").append(envExpr(p, TAU_CONTRAST)).append("'")
                .append(":saturation='1+").append(String.format(l, "%.5f", PEAK_SATURATION_BOOST)).append("*").append(envExpr(p, TAU_SATURATION)).append("'")
                .append(":enable='between(t,").append(startTime).append(",").append(String.format(l, "%.5f", startSeconds + durationSeconds - 0.0005)).append(")'");

        // One gblur per reference frame (knots 0..11), gated to [start + k*d/45, start + (k+1)*d/45).
        // The -0.0005 s nudge keeps a frame whose timestamp lands exactly on a slice
        // boundary in the slice that STARTS there (float rounding would flip it).
        double slice = durationSeconds / REFERENCE_FRAMES;
        for (int k = 0; k < BLUR_SIGMA_640.length; k++) {
            double sigmaPx = BLUR_SIGMA_640[k] / BLUR_REFERENCE_WIDTH * canvasWidth;
            if (sigmaPx < 0.3) continue;
            double a = startSeconds + k * slice - 0.0005;
            double b = startSeconds + (k + 1) * slice - 0.0005;
            sb.append(String.format(l, ",gblur=sigma=%.3f:steps=2:enable='gte(t,%.5f)*lt(t,%.5f)'", sigmaPx, a, b));
        }
        return sb.toString();
    }

    /**
     * The squish as an FFmpeg perspective filter (starts with a comma), evaluated per frame from the frame
     * counter `in` (0 at the clip's first frame). `perspective` replicates edge pixels like the OpenGL shader does, but it is a
     * true projective map while the shader squishes row by row, so the two differ by a few levels in the first
     * few (heavily blurred) frames and match closely afterwards. W and H inside the expressions are the
     * clip frame's own size at that point in the chain.
     *
     * @param fps output frame rate (one reference frame = fps/30 output frames)
     */
    public static String ffmpegPerspective(double durationSeconds, double fps) {
        if (durationSeconds <= 0.0 || fps <= 0.0) return "";
        Locale l = Locale.US;
        // reference knot position of the current frame, clamped to the animation
        String kk = String.format(l, "(min(in/%.5f,1)*%.1f)", durationSeconds * fps, REFERENCE_FRAMES);
        String top = knotExpr(TOP_WIDTH, kk);
        String bot = knotExpr(BOTTOM_WIDTH, kk);
        String hgt = knotExpr(HEIGHT, kk);
        return ",perspective=eval=frame"
                + ":x0='W/2*(1-" + top + ")':y0=0"
                + ":x1='W/2*(1+" + top + ")':y1=0"
                + ":x2='W/2*(1-" + bot + ")':y2='H*" + hgt + "'"
                + ":x3='W/2*(1+" + bot + ")':y3='H*" + hgt + "'"
                + ":sense=destination:interpolation=linear";
    }

    /** Piecewise-linear lookup of a knot table (1.0 past the last knot) as a nested if() expression of kk. */
    private static String knotExpr(float[] table, String kk) {
        Locale l = Locale.US;
        // Build from the tail inwards so the expression nests to the right.
        String expr = "1";
        for (int i = table.length - 1; i >= 0; i--) {
            float next = i + 1 < table.length ? table[i + 1] : 1f;
            String seg = String.format(l, "(%.4f+%.5f*(%s-%d))", table[i], next - table[i], kk, i);
            expr = (i == table.length - 1 ? "if(lt(" + kk + "," + (i + 1) + ")," + seg + ",1)"
                    : "if(lt(" + kk + "," + (i + 1) + ")," + seg + "," + expr + ")");
        }
        return expr;
    }

    /** The same envelope as envelope(), as an FFmpeg expression of the progress expression p. */
    private static String envExpr(String p, float tau) {
        double tail = envelopeTail(tau);
        return String.format(Locale.US, "((exp(-pow(%s/%.4f,2))-%.6f)/%.6f)", p, tau, tail, 1.0 - tail);
    }
}
