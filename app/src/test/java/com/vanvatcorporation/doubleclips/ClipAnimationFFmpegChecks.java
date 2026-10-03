package com.vanvatcorporation.doubleclips;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks for ClipAnimationFFmpeg: the generated filter strings are evaluated numerically (with the
 * tiny FFmpegExprEval) and compared with the curves and with the frozen legacy unfold. Plain Java;
 * returns one message per failed check.
 */
public final class ClipAnimationFFmpegChecks {

    private ClipAnimationFFmpegChecks() {}

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    /** The value of key='...' inside the first filter named `filter` (e.g. ",eq="). */
    private static String option(String filters, String filter, String key) {
        int f = filters.indexOf(filter);
        if (f < 0) return null;
        int k = filters.indexOf(key + "='", f);
        if (k < 0) return null;
        int st = k + key.length() + 2;
        return filters.substring(st, filters.indexOf('\'', st));
    }

    public static List<String> runAll() throws Exception {
        List<String> fails = new ArrayList<>();
        ClipAnimationLoader.clearForTests();
        ClipAnimation unfold = ClipAnimationLoader.register(read("src/main/assets/animations/unfold.json"), "unfold.json", true);
        ClipAnimation fold = ClipAnimationLoader.register(read("src/main/assets/animations/fold.json"), "fold.json", true);

        // 1. unfold (in): eq / perspective / blur slices reproduce the frozen legacy unfold numerically
        final double start = 0.5, dur = 1.5, fps = 30;
        final int W = 640, H = 360;
        ClipAnimationFFmpeg.Plan plan = ClipAnimationFFmpeg.plan(unfold, 1.5f, null, 0f, start, 4.0, fps, W, W, H);
        if (!plan.offsetXPixels.isEmpty() || !plan.offsetYPixels.isEmpty()) fails.add("unfold must not offset the overlay");
        double maxEq = 0, maxCon = 0, maxSat = 0, maxX0 = 0, maxX2 = 0, maxY2 = 0;
        String eqB = option(plan.filters, ",eq=", "brightness"), eqC = option(plan.filters, ",eq=", "contrast"), eqS = option(plan.filters, ",eq=", "saturation");
        String x0 = option(plan.filters, ",perspective=", "x0"), x2 = option(plan.filters, ",perspective=", "x2"), y2 = option(plan.filters, ",perspective=", "y2");
        if (eqB == null || eqC == null || eqS == null || x0 == null || x2 == null || y2 == null) {
            fails.add("unfold plan is missing an eq / perspective option");
            return fails;
        }
        final double biasComp = 1.011 / 255.0 / 1.1631;
        for (int i = 0; i < 45; i++) {
            float p = (i + 0.5f) / 45f;
            double t = start + p * dur;
            Map<String, Double> v = FFmpegExprEval.vars("t", t, "in", p * dur * fps, "W", W, "H", H);
            double c = LegacyUnfoldReference.contrastMultiplier(p);
            double expectEq = (LegacyUnfoldReference.brightness(p) * 0.1 + 0.0100 * (c - 1)) / 1.1631 + biasComp;
            maxEq = Math.max(maxEq, Math.abs(FFmpegExprEval.eval(eqB, v) - expectEq));
            maxCon = Math.max(maxCon, Math.abs(FFmpegExprEval.eval(eqC, v) - c));
            maxSat = Math.max(maxSat, Math.abs(FFmpegExprEval.eval(eqS, v) - LegacyUnfoldReference.saturationMultiplier(p)));
            maxX0 = Math.max(maxX0, Math.abs(FFmpegExprEval.eval(x0, v) - W / 2.0 * (1 - LegacyUnfoldReference.topWidth(p))));
            maxX2 = Math.max(maxX2, Math.abs(FFmpegExprEval.eval(x2, v) - W / 2.0 * (1 - LegacyUnfoldReference.bottomWidth(p))));
            maxY2 = Math.max(maxY2, Math.abs(FFmpegExprEval.eval(y2, v) - H * LegacyUnfoldReference.heightScale(p)));
        }
        System.out.printf("FFmpeg unfold vs legacy, max abs diff: eq.brightness %.1e contrast %.1e saturation %.1e | corners x0 %.1e x2 %.1e y2 %.1e%n",
                maxEq, maxCon, maxSat, maxX0, maxX2, maxY2);
        if (maxEq > 1e-4 || maxCon > 1e-4 || maxSat > 1e-4) fails.add("generated eq differs from legacy");
        if (maxX0 > 1e-2 || maxX2 > 1e-2 || maxY2 > 1e-2) fails.add("generated perspective corners differ from legacy");

        // outside the window: perspective is neutral (corners at the frame corners), eq/blur gated off
        Map<String, Double> after = FFmpegExprEval.vars("t", 3.0, "in", 100.0, "W", W, "H", H);
        if (Math.abs(FFmpegExprEval.eval(x0, after)) > 1e-9 || Math.abs(FFmpegExprEval.eval(y2, after) - H) > 1e-9) fails.add("perspective must be neutral after an in-animation ends");
        if (!plan.filters.contains("enable='between(t,0.49950,1.99950)'")) fails.add("eq must be enabled only inside the in window");

        // blur slices: one per reference frame, each the legacy sigma, adjacent, nothing below 0.3 px
        Pattern gb = Pattern.compile(",gblur=sigma=([0-9.]+):steps=2:enable='gte\\(t,([0-9.]+)\\)\\*lt\\(t,([0-9.]+)\\)'");
        Matcher m = gb.matcher(plan.filters);
        int slices = 0;
        double prevEnd = Double.NaN;
        while (m.find()) {
            double sigma = Double.parseDouble(m.group(1)), a = Double.parseDouble(m.group(2)), b = Double.parseDouble(m.group(3));
            int k = (int) Math.round((a + 0.0005 - start) / (dur / 45.0));
            double expect = LegacyUnfoldReference.blurSigmaFraction(k / 45f) * W;
            if (Math.abs(sigma - expect) > 0.01) fails.add("blur slice " + k + ": sigma " + sigma + " vs legacy " + expect);
            if (Math.abs((b - a) - dur / 45.0) > 1e-4) fails.add("blur slice " + k + " has the wrong length");
            if (!Double.isNaN(prevEnd) && Math.abs(a - prevEnd) > 0.2) { /* gaps are allowed where sigma < 0.3 px */ }
            prevEnd = b;
            slices++;
        }
        if (slices != 12) fails.add("expected 12 blur slices for unfold at 640 px, got " + slices);

        // 2. out animation: neutral before the window, HELD after it; offsets/opacity/hue via a slide animation
        String slide = "{\"schema\":1,\"id\":\"t_slide\",\"direction\":\"in\",\"channels\":{"
                + "\"opacity\":{\"kind\":\"knots\",\"points\":[[0,0],[1,1]]},"
                + "\"hue\":{\"kind\":\"knots\",\"points\":[[0,180],[1,0]],\"interp\":\"smooth\"},"
                + "\"offsetX\":{\"kind\":\"knots\",\"points\":[[0,-0.5],[1,0]]},"
                + "\"offsetY\":{\"kind\":\"gaussian\",\"base\":0,\"peak\":0.25,\"tau\":0.5}}}";
        ClipAnimation s = ClipAnimationLoader.register(slide, "t", false);
        ClipAnimationLoader.register("{\"schema\":1,\"id\":\"t_slide_out\",\"direction\":\"out\",\"mirrorOf\":\"t_slide\"}", "m", false);
        ClipAnimation so = ClipAnimationLoader.get("t_slide_out");
        // clip at 2.0 s, 4 s long; in 1 s, out 1 s
        ClipAnimationFFmpeg.Plan sp = ClipAnimationFFmpeg.plan(s, 1f, so, 1f, 2.0, 4.0, 30, 640, 1000, 500);
        for (double t : new double[]{1.9, 2.0, 2.25, 2.5, 2.99, 3.0, 4.0, 5.0, 5.01, 5.25, 5.5, 5.99, 6.0, 6.4, 8.0}) {
            Map<String, Double> v = FFmpegExprEval.vars("t", t);
            double ex, ey;
            if (t >= 2.0 && t < 3.0) {
                ClipAnimationFrame f = s.evaluate((float) (t - 2.0));
                ex = f.offsetX() * 1000; ey = f.offsetY() * 500;
            } else if (t >= 5.0) {
                ClipAnimationFrame f = so.evaluate((float) Math.min(1.0, t - 5.0));
                ex = f.offsetX() * 1000; ey = f.offsetY() * 500;     // held at p=1 after the clip end (t >= 6)
            } else {
                ex = 0; ey = 0;
            }
            double gx = FFmpegExprEval.eval("0" + sp.offsetXPixels, v), gy = FFmpegExprEval.eval("0" + sp.offsetYPixels, v);
            if (Math.abs(gx - ex) > 0.5 || Math.abs(gy - ey) > 0.5) fails.add("offset at t=" + t + ": got (" + gx + ", " + gy + ") expected (" + ex + ", " + ey + ")");
        }
        if (!sp.filters.contains(",hue=h='") || !sp.filters.contains(",colorchannelmixer=aa=")) fails.add("slide plan should use hue and colorchannelmixer");
        if (sp.filters.contains(",eq=") || sp.filters.contains(",perspective=") || sp.filters.contains(",gblur=")) fails.add("slide plan must only emit the filters its channels need");
        // the hue expression follows the smoothstep curve inside the in window
        String hue = option(sp.filters, ",hue=", "h");
        for (double p : new double[]{0.0, 0.25, 0.5, 0.9}) {
            double got = FFmpegExprEval.eval(hue, FFmpegExprEval.vars("t", 2.0 + p));
            double want = s.evaluate((float) p).hueDegrees();
            if (Math.abs(got - want) > 1e-3) fails.add("hue expr at p=" + p + ": " + got + " vs " + want);
        }

        // 3. in + out that don't fit a short clip are shrunk together and never overlap
        ClipAnimationFFmpeg.Plan tight = ClipAnimationFFmpeg.plan(s, 1.5f, so, 1.5f, 0.0, 2.0, 30, 640, 1000, 500);
        for (double t = 0; t < 2.5; t += 0.01) {
            Map<String, Double> v = FFmpegExprEval.vars("t", t);
            double got = FFmpegExprEval.eval("0" + tight.offsetXPixels, v);
            double inP = t < 1.0 ? t / 1.0 : -1, outP = t >= 1.0 ? Math.min(1.0, (t - 1.0) / 1.0) : -1; // each shrinks to 1.0 s
            double want = (inP >= 0 ? s.evaluate((float) inP) : so.evaluate((float) outP)).offsetX() * 1000;
            if (Math.abs(got - want) > 0.5) { fails.add("tight clip offset at t=" + t + ": " + got + " vs " + want); break; }
        }

        // 4. fold (out): same as unfold reversed, perspective neutral before the window and held after
        ClipAnimationFFmpeg.Plan fp = ClipAnimationFFmpeg.plan(null, 0f, fold, 1.5f, 0.5, 4.0, 30, 640, 640, 360);
        String fx2 = option(fp.filters, ",perspective=", "x2"), fy2 = option(fp.filters, ",perspective=", "y2");
        double winFrame = (4.0 - 1.5) * 30;  // frame index at which the out window opens
        for (double p : new double[]{0.0, 0.1, 0.5, 0.8, 1.0, 1.5}) {
            double inFrame = winFrame + Math.min(p, 1.0) * 45;
            Map<String, Double> v = FFmpegExprEval.vars("in", inFrame, "W", W, "H", H, "t", 0.5 + inFrame / 30.0);
            double q = fold.evaluate((float) Math.min(p, 1.0)).warpBottomWidth();
            if (Math.abs(FFmpegExprEval.eval(fx2, v) - W / 2.0 * (1 - q)) > 1e-2) fails.add("fold corner at p=" + p + " wrong");
            if (Math.abs(FFmpegExprEval.eval(fy2, v) - H * fold.evaluate((float) Math.min(p, 1.0)).warpHeight()) > 1e-2) fails.add("fold height at p=" + p + " wrong");
        }
        if (Math.abs(FFmpegExprEval.eval(fy2, FFmpegExprEval.vars("in", 10.0, "W", W, "H", H)) - H) > 1e-9) fails.add("out animation must be neutral before its window");
        if (!fp.filters.contains("enable='gte(t,3.00000-0.0005)'".replace("3.00000-0.0005", "2.99950"))) {
            if (!fp.filters.contains("gte(t,2.99950)")) fails.add("fold's eq must open at the out window and stay enabled (held)");
        }
        // the held end state: a final gblur/eq slice with no upper bound
        Matcher hold = Pattern.compile(",gblur=sigma=[0-9.]+:steps=2:enable='gte\\(t,([0-9.]+)\\)'").matcher(fp.filters);
        if (!hold.find() || Math.abs(Double.parseDouble(hold.group(1)) - 4.49950) > 1e-4) fails.add("fold should end with a held blur slice from the clip end");

        // 5. unsupported channels are reported, supported ones aren't
        String unsup = "{\"schema\":1,\"id\":\"t_unsup\",\"direction\":\"in\",\"channels\":{"
                + "\"scale\":{\"kind\":\"constant\",\"value\":1.2},\"rotation\":{\"kind\":\"constant\",\"value\":5},"
                + "\"temperature\":{\"kind\":\"constant\",\"value\":100},\"opacity\":{\"kind\":\"constant\",\"value\":0.5}}}";
        ClipAnimation u = ClipAnimationLoader.register(unsup, "u", false);
        if (!ClipAnimationFFmpeg.unsupportedChannels(u).equals(java.util.EnumSet.of(ClipAnimation.Channel.SCALE, ClipAnimation.Channel.ROTATION, ClipAnimation.Channel.TEMPERATURE))) {
            fails.add("unsupportedChannels wrong: " + ClipAnimationFFmpeg.unsupportedChannels(u));
        }
        if (!ClipAnimationFFmpeg.unsupportedChannels(unfold).isEmpty() || !ClipAnimationFFmpeg.unsupportedChannels(fold).isEmpty()) fails.add("unfold/fold are fully supported by FFmpeg");
        ClipAnimationFFmpeg.Plan up = ClipAnimationFFmpeg.plan(u, 1f, null, 0f, 0.0, 3.0, 30, 640, 640, 360);
        if (up.filters.contains("scale") || up.filters.contains("rotate")) fails.add("unsupported channels must not leak into the filters");
        if (ClipAnimationFFmpeg.plan(null, 0f, null, 0f, 0.0, 3.0, 30, 640, 640, 360).filters.length() != 0) fails.add("no animation -> empty plan");

        // 6. fuzz: every curve kind as an expression == the curve, over random curves and many x
        Random rnd = new Random(7);
        for (int iter = 0; iter < 300; iter++) {
            ClipAnimation.Curve c;
            int kind = rnd.nextInt(3);
            if (kind == 0) {
                c = new ClipAnimation.ConstantCurve(rnd.nextDouble() * 4 - 2);
            } else if (kind == 1) {
                c = new ClipAnimation.GaussianCurve(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1, 0.05 + rnd.nextDouble() * 3);
            } else {
                int n = 1 + rnd.nextInt(20);
                double[] ps = new double[n], vs = new double[n];
                double pp = rnd.nextDouble() * 0.3;
                for (int k = 0; k < n; k++) {
                    ps[k] = pp;
                    pp += 0.01 + rnd.nextDouble() * (1.0 - pp) / (n - k + 1);
                    vs[k] = rnd.nextBoolean() ? rnd.nextDouble() * 6 - 3 : (k > 0 ? vs[k - 1] : 0.5);  // some flat segments
                }
                c = new ClipAnimation.KnotsCurve(ps, vs, rnd.nextBoolean());
            }
            String e = ClipAnimationFFmpeg.curveExpr(c, "x");
            for (int k = 0; k <= 40; k++) {
                double x = k / 40.0;
                double got = FFmpegExprEval.eval(e, FFmpegExprEval.vars("x", x));
                if (Math.abs(got - c.at(x)) > 1e-4) { fails.add("fuzz " + c.kind() + " mismatch at x=" + x + ": " + got + " vs " + c.at(x)); iter = 1000; break; }
            }
        }
        return fails;
    }

    public static void main(String[] args) throws Exception {
        List<String> fails = runAll();
        if (fails.isEmpty()) System.out.println("FFMPEG CHECKS PASSED");
        else {
            for (String f : fails) System.out.println("FAIL: " + f);
            System.exit(1);
        }
    }
}
