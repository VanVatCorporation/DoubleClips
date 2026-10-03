package com.vanvatcorporation.doubleclips;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a loaded {@link ClipAnimation} into FFmpeg filters / expressions, so the FFmpeg export
 * can play every animation the OpenGL export can (as far as FFmpeg is able to express it).
 * Plain Java (no Android classes); FFmpegEdit only splices the strings this returns into a clip's
 * filter chain. The same code serves "in" and "out" animations.
 *
 * WHAT FFMPEG CAN DO (see {@link #SUPPORTED}); everything else is reported, never faked:
 *  - brightness / contrast / saturation: one {@code eq} with eval=frame, each a continuous
 *    expression of t (brightness mapped through the same calibrated eq curve the OpenGL shader was
 *    matched against).
 *  - hue: {@code hue=h=} expression of t.
 *  - opacity and blur: FFmpeg has no per-frame parameter for them, so they are applied as a short
 *    chain of {@code colorchannelmixer} / {@code gblur} filters, each enabled for one time slice and
 *    holding that slice's start value (blur: one slice per reference frame of the animation).
 *  - warp.*: {@code perspective} with per-frame corner expressions of the frame counter (`in`).
 *  - offsetX / offsetY: an extra term for the overlay's position expressions.
 *  NOT expressible here yet: scale, rotation, temperature (they feed the scale / rotate /
 *  colortemperature filters and the overlay geometry that FFmpegEdit builds from the clip's own
 *  keyframes) - see {@link #unsupportedChannels}.
 *
 * A curve becomes an expression without any nesting: piecewise-linear knots are written as a flat
 * sum of clip() "hinge" terms, a gaussian as base + peak*envelope, a constant as a number.
 *
 * WINDOW RULES match OpenGLEdit.animationFrame: an in-animation is neutral outside
 * [start, start+duration); an out-animation is neutral before its window and HELD at its end state
 * afterwards (the tpad freeze that extends a clip under a transition keeps drawing it).
 */
public final class ClipAnimationFFmpeg {

    private ClipAnimationFFmpeg() {}

    /** Channels this class can express in FFmpeg. */
    public static final Set<ClipAnimation.Channel> SUPPORTED = Collections.unmodifiableSet(EnumSet.of(
            ClipAnimation.Channel.BRIGHTNESS, ClipAnimation.Channel.CONTRAST, ClipAnimation.Channel.SATURATION,
            ClipAnimation.Channel.HUE, ClipAnimation.Channel.OPACITY, ClipAnimation.Channel.BLUR,
            ClipAnimation.Channel.WARP_TOP_WIDTH, ClipAnimation.Channel.WARP_BOTTOM_WIDTH, ClipAnimation.Channel.WARP_HEIGHT,
            ClipAnimation.Channel.OFFSET_X, ClipAnimation.Channel.OFFSET_Y));

    /** The animation's channels FFmpeg can't reproduce (they are simply left out of an FFmpeg export). */
    public static Set<ClipAnimation.Channel> unsupportedChannels(ClipAnimation a) {
        Set<ClipAnimation.Channel> out = EnumSet.noneOf(ClipAnimation.Channel.class);
        out.addAll(a.channels());
        out.removeAll(SUPPORTED);
        return out;
    }

    // FFmpeg eq calibration, regressed from eq's measured transfer curve (FFmpeg 6.1,
    // yuv420p, grey ramps over brightness 0..0.4 x contrast 1..1.8, residual 0.67 of 255).
    // These match the theory for 16..235 limited-range luma (gain 255/219, pivot code 128).
    /** Effective luma gain of eq's brightness parameter in full-range terms. */
    private static final double EQ_BRIGHTNESS_GAIN = 1.1631;
    /** eq's contrast pivot in 0..1 full-range luma, minus 0.5. */
    private static final double EQ_PIVOT_SHIFT = 0.0100;
    /** eq darkens by ~1.01 levels whenever it is not a pure pass-through; add that back (in eq brightness units). */
    private static final double EQ_BIAS_COMPENSATION = 1.011 / 255.0 / EQ_BRIGHTNESS_GAIN;

    /** Same -0.0005 s nudge for every window / slice edge, so a frame stamped exactly on an edge lands in the slice that STARTS there. */
    private static final double EDGE_NUDGE = 0.0005;
    private static final double BLUR_MIN_SIGMA_PX = 0.3;
    private static final double OPACITY_MIN_DELTA = 0.0005;
    private static final int MAX_OPACITY_SLICES = 120;
    private static final int MIN_SLICES = 2;

    // ---- when the animation plays --------------------------------------------------------

    /** Where one animation plays on the output timeline. */
    public static final class Window {
        final boolean isOut;
        final double start;      // output-timeline seconds when the window opens
        final double duration;   // seconds
        final double startFrame; // frame index (counted from the clip's first frame) when it opens
        final double fps;

        private Window(boolean isOut, double start, double duration, double startFrame, double fps) {
            this.isOut = isOut;
            this.start = start;
            this.duration = duration;
            this.startFrame = startFrame;
            this.fps = fps;
        }

        /** An in-animation: opens at the clip's first frame. */
        public static Window forIn(double clipStartSeconds, double durationSeconds, double fps) {
            return new Window(false, clipStartSeconds, durationSeconds, 0.0, fps);
        }

        /** An out-animation: closes at the clip's last frame. */
        public static Window forOut(double clipStartSeconds, double clipDurationSeconds, double durationSeconds, double fps) {
            return new Window(true, clipStartSeconds + clipDurationSeconds - durationSeconds, durationSeconds,
                    (clipDurationSeconds - durationSeconds) * fps, fps);
        }

        double end() { return start + duration; }
        double endFrame() { return startFrame + duration * fps; }
    }

    /** What FFmpegEdit needs for one clip: filters to append to its chain, plus overlay offset terms. */
    public static final class Plan {
        /** Starts with a comma, or empty. Goes after setpts (so t is output-timeline time) and tpad. */
        public final String filters;
        /** Pixel offset expressions of t to ADD to the overlay centre ("" = none). */
        public final String offsetXPixels;
        public final String offsetYPixels;

        Plan(String filters, String offsetXPixels, String offsetYPixels) {
            this.filters = filters;
            this.offsetXPixels = offsetXPixels;
            this.offsetYPixels = offsetYPixels;
        }
    }

    /**
     * Plans both of a clip's animations. Either may be null (none / not installed / wrong direction).
     * Durations are fitted exactly like OpenGLEdit does so in + out never overlap in a short clip.
     *
     * @param blurWidthPx width of the picture being blurred (blur sigma is a fraction of it)
     */
    public static Plan plan(ClipAnimation in, float inDurationRaw, ClipAnimation out, float outDurationRaw,
                            double clipStart, double clipDuration, double fps, int blurWidthPx, int canvasW, int canvasH) {
        float inRaw = in != null ? inDurationRaw : 0f;
        float outRaw = out != null ? outDurationRaw : 0f;
        StringBuilder filters = new StringBuilder();
        StringBuilder offX = new StringBuilder();
        StringBuilder offY = new StringBuilder();
        if (in != null) {
            double d = ClipAnimation.fitDuration(inRaw, outRaw, (float) clipDuration);
            if (d > 0.0) append(filters, offX, offY, in, Window.forIn(clipStart, d, fps), fps, blurWidthPx, canvasW, canvasH);
        }
        if (out != null) {
            double d = ClipAnimation.fitDuration(outRaw, inRaw, (float) clipDuration);
            if (d > 0.0) append(filters, offX, offY, out, Window.forOut(clipStart, clipDuration, d, fps), fps, blurWidthPx, canvasW, canvasH);
        }
        return new Plan(filters.toString(), offX.toString(), offY.toString());
    }

    private static void append(StringBuilder filters, StringBuilder offX, StringBuilder offY, ClipAnimation a, Window w,
                               double fps, int blurWidthPx, int canvasW, int canvasH) {
        filters.append(perspective(a, w));
        filters.append(eq(a, w));
        filters.append(hue(a, w));
        filters.append(opacitySlices(a, w));
        filters.append(blurSlices(a, w, blurWidthPx));
        String x = offset(a, w, ClipAnimation.Channel.OFFSET_X, canvasW);
        String y = offset(a, w, ClipAnimation.Channel.OFFSET_Y, canvasH);
        if (!x.isEmpty()) offX.append("+").append(x);
        if (!y.isEmpty()) offY.append("+").append(y);
    }

    // ---- expressions ---------------------------------------------------------------------

    private static String num(double v) {
        String s = String.format(Locale.US, "%.7f", v);
        return v < 0 ? "(" + s + ")" : s;
    }

    private static String timeStr(double v) { return String.format(Locale.US, "%.5f", v); }

    /** Progress 0..1 as an expression of t (clamped), flipped for a reversed animation. */
    private static String progressOfT(ClipAnimation a, Window w) {
        String p = "clip((t-" + timeStr(w.start) + ")/" + timeStr(w.duration) + ",0,1)";
        return a.isReversed() ? "(1-" + p + ")" : p;
    }

    /** Progress 0..1 as an expression of the frame counter `in` (clamped), flipped for a reversed animation. */
    private static String progressOfFrame(ClipAnimation a, Window w) {
        String p = "clip((in-" + String.format(Locale.US, "%.4f", w.startFrame) + ")/"
                + String.format(Locale.US, "%.4f", w.duration * w.fps) + ",0,1)";
        return a.isReversed() ? "(1-" + p + ")" : p;
    }

    /** The curve as an FFmpeg expression of x (x = progress expression, already in curve time). */
    static String curveExpr(ClipAnimation.Curve c, String x) {
        if (c instanceof ClipAnimation.ConstantCurve) {
            return num(((ClipAnimation.ConstantCurve) c).getValue());
        }
        if (c instanceof ClipAnimation.GaussianCurve) {
            ClipAnimation.GaussianCurve g = (ClipAnimation.GaussianCurve) c;
            double tail = Math.exp(-(1.0 / g.getTau()) * (1.0 / g.getTau()));
            return "(" + num(g.getBase()) + "+" + num(g.getPeak())
                    + "*((exp(-pow(" + x + "/" + num(g.getTau()) + ",2))-" + num(tail) + ")/" + num(1.0 - tail) + "))";
        }
        ClipAnimation.KnotsCurve k = (ClipAnimation.KnotsCurve) c;
        StringBuilder sb = new StringBuilder("(").append(num(k.getValue(0)));
        for (int i = 0; i + 1 < k.size(); i++) {
            double dv = k.getValue(i + 1) - k.getValue(i);
            if (dv == 0.0) continue; // a flat segment adds nothing
            double dp = k.getP(i + 1) - k.getP(i);
            if (k.isSmooth()) {
                String u = "clip((" + x + "-" + num(k.getP(i)) + ")/" + num(dp) + ",0,1)";
                sb.append("+").append(num(dv)).append("*(").append(u).append("*").append(u).append("*(3-2*").append(u).append("))");
            } else {
                sb.append("+").append(num(dv / dp)).append("*clip(").append(x).append("-").append(num(k.getP(i)))
                        .append(",0,").append(num(dp)).append(")");
            }
        }
        return sb.append(")").toString();
    }

    private static String chan(ClipAnimation a, ClipAnimation.Channel ch, String x, String ifAbsent) {
        ClipAnimation.Curve c = a.curveFor(ch);
        return c == null ? ifAbsent : curveExpr(c, x);
    }

    /** enable= expression of t: the window for an in-animation, [open, forever) for an out-animation (held). */
    private static String enableExpr(Window w) {
        return w.isOut
                ? "gte(t," + timeStr(w.start - EDGE_NUDGE) + ")"
                : "between(t," + timeStr(w.start - EDGE_NUDGE) + "," + timeStr(w.end() - EDGE_NUDGE) + ")";
    }

    // ---- filters ---------------------------------------------------------------------------

    private static String eq(ClipAnimation a, Window w) {
        if (a.curveFor(ClipAnimation.Channel.BRIGHTNESS) == null && a.curveFor(ClipAnimation.Channel.CONTRAST) == null
                && a.curveFor(ClipAnimation.Channel.SATURATION) == null) return "";
        String x = progressOfT(a, w);
        String b = chan(a, ClipAnimation.Channel.BRIGHTNESS, x, "0");
        String c = chan(a, ClipAnimation.Channel.CONTRAST, x, "1");
        String s = chan(a, ClipAnimation.Channel.SATURATION, x, "1");
        // FFmpeg's eq works on LIMITED-range luma code values (16..235) with its contrast pivot at
        // code 128, not on 0..1 luma with a pivot at 0.5 like the OpenGL shader. Mapping the shader's
        //   y' = c*(y-0.5) + 0.5 + B*0.1   onto eq:
        //   eq brightness = (B*0.1 + EQ_PIVOT_SHIFT*(c-1)) / EQ_BRIGHTNESS_GAIN + EQ_BIAS_COMPENSATION
        String bExpr = "(" + b + "*0.1+" + num(EQ_PIVOT_SHIFT) + "*(" + c + "-1))/" + num(EQ_BRIGHTNESS_GAIN) + "+" + num(EQ_BIAS_COMPENSATION);
        return ",eq=eval=frame:brightness='" + bExpr + "':contrast='" + c + "':saturation='" + s
                + "':enable='" + enableExpr(w) + "'";
    }

    private static String hue(ClipAnimation a, Window w) {
        if (a.curveFor(ClipAnimation.Channel.HUE) == null) return "";
        return ",hue=h='" + chan(a, ClipAnimation.Channel.HUE, progressOfT(a, w), "0") + "':enable='" + enableExpr(w) + "'";
    }

    /** Slice count for time-stepped channels: one per reference frame when the file says, else ~30 per second. */
    private static int slices(ClipAnimation a, Window w, int max) {
        int n = a.getReferenceFrames() > 0 ? a.getReferenceFrames() : (int) Math.round(w.duration * 30.0);
        return Math.max(MIN_SLICES, Math.min(max, n));
    }

    private static String opacitySlices(ClipAnimation a, Window w) {
        if (a.curveFor(ClipAnimation.Channel.OPACITY) == null) return "";
        // opacity wants a slice per OUTPUT frame so fades stay smooth at 60 fps too
        int n = Math.max(MIN_SLICES, Math.min(MAX_OPACITY_SLICES, (int) Math.round(w.duration * w.fps)));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            double v = a.evaluate((float) k / n).opacity();
            if (Math.abs(v - 1.0) < OPACITY_MIN_DELTA) continue;
            sb.append(",colorchannelmixer=aa=").append(String.format(Locale.US, "%.5f", v))
                    .append(":enable='").append(sliceEnable(w, k, n)).append("'");
        }
        if (w.isOut) {
            double v = a.evaluate(1f).opacity();
            if (Math.abs(v - 1.0) >= OPACITY_MIN_DELTA) {
                sb.append(",colorchannelmixer=aa=").append(String.format(Locale.US, "%.5f", v))
                        .append(":enable='gte(t,").append(timeStr(w.end() - EDGE_NUDGE)).append(")'");
            }
        }
        return sb.toString();
    }

    private static String blurSlices(ClipAnimation a, Window w, int blurWidthPx) {
        if (a.curveFor(ClipAnimation.Channel.BLUR) == null) return "";
        int n = slices(a, w, 90);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            double sigmaPx = a.evaluate((float) k / n).blurWidthFraction() * blurWidthPx;
            if (sigmaPx < BLUR_MIN_SIGMA_PX) continue;
            sb.append(String.format(Locale.US, ",gblur=sigma=%.3f:steps=2:enable='", sigmaPx)).append(sliceEnable(w, k, n)).append("'");
        }
        if (w.isOut) {
            double sigmaPx = a.evaluate(1f).blurWidthFraction() * blurWidthPx;
            if (sigmaPx >= BLUR_MIN_SIGMA_PX) {
                sb.append(String.format(Locale.US, ",gblur=sigma=%.3f:steps=2:enable='gte(t,%s)'", sigmaPx, timeStr(w.end() - EDGE_NUDGE)));
            }
        }
        return sb.toString();
    }

    private static String sliceEnable(Window w, int k, int n) {
        double a = w.start + k * w.duration / n - EDGE_NUDGE;
        double b = w.start + (k + 1) * w.duration / n - EDGE_NUDGE;
        return "gte(t," + timeStr(a) + ")*lt(t," + timeStr(b) + ")";
    }

    /**
     * The squish as a perspective filter evaluated per frame from the frame counter `in` (0 at the
     * clip's first frame). `perspective` replicates edge pixels like the OpenGL shader does, but it
     * is a true projective map while the shader squishes row by row, so the two differ by a few
     * levels in the first (heavily blurred) frames and match closely afterwards. W and H inside the
     * expressions are the clip frame's own size at that point of the chain.
     */
    private static String perspective(ClipAnimation a, Window w) {
        if (a.curveFor(ClipAnimation.Channel.WARP_TOP_WIDTH) == null && a.curveFor(ClipAnimation.Channel.WARP_BOTTOM_WIDTH) == null
                && a.curveFor(ClipAnimation.Channel.WARP_HEIGHT) == null) return "";
        String x = progressOfFrame(a, w);
        String top = gateFrames(chan(a, ClipAnimation.Channel.WARP_TOP_WIDTH, x, "1"), w);
        String bot = gateFrames(chan(a, ClipAnimation.Channel.WARP_BOTTOM_WIDTH, x, "1"), w);
        String hgt = gateFrames(chan(a, ClipAnimation.Channel.WARP_HEIGHT, x, "1"), w);
        return ",perspective=eval=frame"
                + ":x0='W/2*(1-" + top + ")':y0=0"
                + ":x1='W/2*(1+" + top + ")':y1=0"
                + ":x2='W/2*(1-" + bot + ")':y2='H*" + hgt + "'"
                + ":x3='W/2*(1+" + bot + ")':y3='H*" + hgt + "'"
                + ":sense=destination:interpolation=linear";
    }

    /** Neutral (1) outside the window; an out-animation keeps its end state after the window. */
    private static String gateFrames(String expr, Window w) {
        String s = String.format(Locale.US, "%.4f", w.startFrame);
        String inner = w.isOut ? expr : "if(gte(in," + String.format(Locale.US, "%.4f", w.endFrame()) + "),1," + expr + ")";
        return w.startFrame > 0.0 ? "if(lt(in," + s + "),1," + inner + ")" : inner;
    }

    /** One offset channel as a pixel expression of t, zero outside the window (held for out), or "". */
    private static String offset(ClipAnimation a, Window w, ClipAnimation.Channel ch, int canvasPx) {
        ClipAnimation.Curve c = a.curveFor(ch);
        if (c == null) return "";
        return enableExpr(w) + "*" + curveExpr(c, progressOfT(a, w)) + "*" + canvasPx;
    }
}
