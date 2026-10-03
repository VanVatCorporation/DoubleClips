package com.vanvatcorporation.doubleclips;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The actual checks behind ClipAnimationTest, kept as plain Java (no JUnit) so they can also
 * be run with a simple main(). Returns one message per failed check; empty = all good.
 */
public final class ClipAnimationChecks {

    private static final String UNFOLD_PATH = "src/main/assets/animations/in/unfold.json";

    private ClipAnimationChecks() {}

    public static List<String> runAll() throws Exception {
        List<String> fails = new ArrayList<>();
        ClipAnimationLoader.clearForTests();
        String unfoldJson = new String(Files.readAllBytes(new File(UNFOLD_PATH).toPath()), StandardCharsets.UTF_8);
        ClipAnimation unfold = ClipAnimationLoader.register(unfoldJson, "unfold.json", true);

        // 1. unfold.json must reproduce the hand-coded UnfoldAnimation (the look that was approved on device)
        double mb = 0, ms = 0, mc = 0, mbl = 0, mt = 0, mbo = 0, mh = 0;
        for (int i = 0; i <= 4000; i++) {
            float p = i / 4000f;
            ClipAnimationFrame f = unfold.evaluate(p);
            mb = Math.max(mb, Math.abs(f.brightness() - LegacyUnfoldReference.brightness(p)));
            ms = Math.max(ms, Math.abs(f.saturation() - LegacyUnfoldReference.saturationMultiplier(p)));
            mc = Math.max(mc, Math.abs(f.contrast() - LegacyUnfoldReference.contrastMultiplier(p)));
            mbl = Math.max(mbl, Math.abs(f.blurWidthFraction() - LegacyUnfoldReference.blurSigmaFraction(p)));
            mt = Math.max(mt, Math.abs(f.warpTopWidth() - LegacyUnfoldReference.topWidth(p)));
            mbo = Math.max(mbo, Math.abs(f.warpBottomWidth() - LegacyUnfoldReference.bottomWidth(p)));
            mh = Math.max(mh, Math.abs(f.warpHeight() - LegacyUnfoldReference.heightScale(p)));
        }
        System.out.printf("unfold.json vs UnfoldAnimation, max abs diff: brightness %.2e saturation %.2e contrast %.2e blur %.2e top %.2e bottom %.2e height %.2e%n",
                mb, ms, mc, mbl, mt, mbo, mh);
        if (mb > 1e-4) fails.add("brightness differs by " + mb);
        if (ms > 1e-4) fails.add("saturation differs by " + ms);
        if (mc > 1e-4) fails.add("contrast differs by " + mc);
        if (mbl > 1e-6) fails.add("blur differs by " + mbl);
        if (mt > 1e-5) fails.add("top width differs by " + mt);
        if (mbo > 1e-5) fails.add("bottom width differs by " + mbo);
        if (mh > 1e-5) fails.add("height differs by " + mh);

        // 2. neutral outside the window and (for unfold) at the very end
        if (unfold.evaluate(-1f) != ClipAnimationFrame.NEUTRAL) fails.add("p<0 must return NEUTRAL");
        if (unfold.evaluate(Float.NaN) != ClipAnimationFrame.NEUTRAL) fails.add("NaN must return NEUTRAL");
        ClipAnimationFrame end = unfold.evaluate(1f);
        if (Math.abs(end.brightness()) > 1e-6 || Math.abs(end.saturation() - 1) > 1e-6 || end.blurWidthFraction() != 0f
                || end.warpHeight() != 1f) fails.add("unfold must end neutral");
        if (unfold.evaluate(5f).brightness() != end.brightness()) fails.add("p>1 must hold the p=1 value");
        if (unfold.getDefaultDuration() != 1.5f || unfold.getDirection() != ClipAnimation.Direction.IN) fails.add("unfold metadata wrong");
        if (unfold.channels().size() != 7) fails.add("unfold should drive 7 channels, got " + unfold.channels().size());

        // 3. mirrorOf: out = in reversed
        String mirror = "{\"schema\":1,\"id\":\"unfold_out\",\"direction\":\"out\",\"mirrorOf\":\"unfold\"}";
        ClipAnimation out = ClipAnimationLoader.register(mirror, "mirror", false);
        for (float p : new float[]{0f, 0.1f, 0.37f, 0.8f, 1f}) {
            if (Math.abs(out.evaluate(p).brightness() - unfold.evaluate(1f - p).brightness()) > 1e-6) fails.add("mirror differs at p=" + p);
        }
        if (!out.isReversed() || out.getName().equals("") || !out.getName().equals("unfold_out")) fails.add("mirror metadata wrong");
        if (ClipAnimationLoader.list(ClipAnimation.Direction.OUT).size() != 1 || ClipAnimationLoader.get("unfold_out") != out) fails.add("registry lookup wrong");

        // 3b. the bundled fold.json (out) mirrors unfold, loaded after it exactly like ClipAnimationAssets would
        String foldJson = new String(Files.readAllBytes(new File("src/main/assets/animations/out/fold.json").toPath()), StandardCharsets.UTF_8);
        ClipAnimation fold = ClipAnimationLoader.register(foldJson, "fold.json", true);
        if (fold.getDirection() != ClipAnimation.Direction.OUT || !fold.isReversed() || fold.getDefaultDuration() != 1.5f
                || !fold.getName().equals("Fold")) fails.add("fold.json metadata wrong");
        for (float p : new float[]{0f, 0.2f, 0.5f, 1f}) {
            if (Math.abs(fold.evaluate(p).warpBottomWidth() - unfold.evaluate(1f - p).warpBottomWidth()) > 1e-6
                    || Math.abs(fold.evaluate(p).blurWidthFraction() - unfold.evaluate(1f - p).blurWidthFraction()) > 1e-9) fails.add("fold != unfold reversed at p=" + p);
        }
        if (fold.evaluate(0f).brightness() > 1e-5f || fold.evaluate(1f).brightness() < 3.9f) fails.add("fold should start clean and end at unfold's peak flash");
        if (ClipAnimationLoader.list(ClipAnimation.Direction.OUT).size() != 2 || ClipAnimationLoader.list(ClipAnimation.Direction.IN).size() != 1) fails.add("picker lists wrong (fold + unfold_out out, unfold in)");
        // a built-in out animation can't be shadowed by a user file either
        expectReject(fails, "shadowing built-in fold", foldJson.replace("\"mirrorOf\": \"unfold\"", "\"mirrorOf\": \"unfold\""), false);

        // 4. every channel kind + smooth interpolation + defaults
        String all = "{\"schema\":1,\"id\":\"t-all\",\"direction\":\"in\",\"channels\":{"
                + "\"opacity\":{\"kind\":\"knots\",\"points\":[[0,0],[1,1]]},"
                + "\"scale\":{\"kind\":\"constant\",\"value\":1.5},"
                + "\"offsetX\":{\"kind\":\"knots\",\"points\":[[0,0],[0.5,0.2],[1,0]],\"interp\":\"smooth\"},"
                + "\"rotation\":{\"kind\":\"gaussian\",\"peak\":-30,\"tau\":0.5},"
                + "\"temperature\":{\"kind\":\"constant\",\"value\":-500}}}";
        ClipAnimation a = ClipAnimationLoader.register(all, "t", false);
        ClipAnimationFrame f = a.evaluate(0.25f);
        if (Math.abs(f.opacity() - 0.25f) > 1e-6) fails.add("linear knots wrong: " + f.opacity());
        if (f.scale() != 1.5f || f.temperatureKelvin() != -500f) fails.add("constant wrong");
        if (Math.abs(f.offsetX() - 0.1f) > 1e-6) fails.add("smoothstep at t=.5 of first segment should be 0.5*0.2=0.1, got " + f.offsetX());
        if (Math.abs(a.evaluate(0f).rotationDegrees() + 30f) > 1e-5) fails.add("gaussian rotation at p=0 should be -30");
        if (f.brightness() != 0f || f.saturation() != 1f || f.warpTopWidth() != 1f) fails.add("undeclared channels must stay neutral");
        if (a.getDefaultDuration() != 0.5f || !a.getName().equals("t-all")) fails.add("defaults (duration 0.5, name=id) wrong");

        // 5. progress helpers
        if (ClipAnimation.progress(0.25f, 1f) != 0.25f || ClipAnimation.progress(-0.01f, 1f) != -1f
                || ClipAnimation.progress(1f, 1f) != -1f || ClipAnimation.progress(0.5f, 0f) != -1f) fails.add("progress() wrong");
        if (ClipAnimation.progressOut(10f, 9.5f, 1f) != 0.5f || ClipAnimation.progressOut(10f, 8.9f, 1f) != -1f
                || ClipAnimation.progressOut(10f, 9f, 1f) != 0f || ClipAnimation.progressOut(10f, 5f, 0f) != -1f) fails.add("progressOut() wrong");
        // past the clip's end (outgoing clip of a transition) the out animation is HELD at its end state
        if (ClipAnimation.progressOut(10f, 10f, 1f) != 1f || ClipAnimation.progressOut(10f, 10.4f, 1f) != 1f) fails.add("progressOut() must hold 1 past the clip end");
        // fitDuration: in + out share a short clip proportionally, never overlap
        if (ClipAnimation.fitDuration(1f, 1f, 5f) != 1f) fails.add("fitDuration should leave fitting durations alone");
        if (Math.abs(ClipAnimation.fitDuration(1.5f, 1.5f, 2f) - 1f) > 1e-6) fails.add("fitDuration should halve two 1.5s animations in a 2s clip... got " + ClipAnimation.fitDuration(1.5f, 1.5f, 2f));
        if (Math.abs(ClipAnimation.fitDuration(3f, 1f, 2f) - 1.5f) > 1e-6 || Math.abs(ClipAnimation.fitDuration(1f, 3f, 2f) - 0.5f) > 1e-6) fails.add("fitDuration proportions wrong");
        if (ClipAnimation.fitDuration(3f, 0f, 2f) != 2f) fails.add("a lone animation longer than the clip should shrink to the clip");
        if (ClipAnimation.fitDuration(0f, 1f, 2f) != 0f || ClipAnimation.fitDuration(-1f, 0f, 2f) != 0f) fails.add("no animation must stay 0");
        if (ClipAnimation.fitDuration(1f, 1f, 0f) != 1f) fails.add("unknown clip duration must not change anything");
        float inD = ClipAnimation.fitDuration(1.5f, 1.5f, 2f), outD = ClipAnimation.fitDuration(1.5f, 1.5f, 2f);
        if (inD + outD > 2f + 1e-5f) fails.add("in + out must fit in the clip");

        // 5b. folder / direction: a file in the "out" folder must declare "out" (and vice versa), registering nothing otherwise
        try {
            ClipAnimationLoader.register(unfoldJson.replace("\"id\": \"unfold\"", "\"id\": \"unfold_x\""), "out/unfold_x.json", false, ClipAnimation.Direction.OUT);
            fails.add("an 'in' animation in the out folder must be rejected");
        } catch (ClipAnimationLoader.FormatException expected) {
            if (!expected.getMessage().contains("\"out\" folder")) fails.add("folder mismatch message unclear: " + expected.getMessage());
        }
        if (ClipAnimationLoader.get("unfold_x") != null) fails.add("a folder-mismatched file got registered");
        if (ClipAnimationLoader.get("unfold", ClipAnimation.Direction.IN) != unfold || ClipAnimationLoader.get("unfold", ClipAnimation.Direction.OUT) != null
                || ClipAnimationLoader.get("nope", ClipAnimation.Direction.IN) != null) fails.add("get(id, direction) wrong");
        if (!ClipAnimationLoader.isBuiltIn("unfold") || ClipAnimationLoader.isBuiltIn("t-all")) fails.add("isBuiltIn wrong");
        if (ClipAnimationLoader.unregister("unfold") || !ClipAnimationLoader.unregister("t-all") || ClipAnimationLoader.get("t-all") != null
                || ClipAnimationLoader.unregister("t-all")) fails.add("unregister must remove user animations only, once");
        // a mirror can use a base from extraBases without registering anything
        java.util.Map<String, ClipAnimation> extra = new java.util.HashMap<>();
        extra.put("staged_base", unfold);
        ClipAnimation viaExtra = ClipAnimationLoader.parse("{\"schema\":1,\"id\":\"m2\",\"direction\":\"out\",\"mirrorOf\":\"staged_base\"}", "m2", extra, null);
        if (!viaExtra.isReversed() || ClipAnimationLoader.get("m2") != null) fails.add("parse() with extraBases must not register");

        // 6. built-ins can't be shadowed; later user files may replace user files
        String shadow = "{\"schema\":1,\"id\":\"unfold\",\"direction\":\"in\",\"channels\":{\"opacity\":{\"kind\":\"constant\",\"value\":0.5}}}";
        expectReject(fails, "shadowing a built-in", shadow, false);

        // 7. rejections
        String ok = "{\"schema\":1,\"id\":\"x\",\"direction\":\"in\",\"channels\":{\"blur\":{\"kind\":\"constant\",\"value\":0.01}}}";
        if (ClipAnimationLoader.parse(ok, "ok") == null) fails.add("baseline file should parse");
        expectReject(fails, "bad schema", ok.replace("\"schema\":1", "\"schema\":2"));
        expectReject(fails, "missing schema", ok.replace("\"schema\":1,", ""));
        expectReject(fails, "id none", ok.replace("\"id\":\"x\"", "\"id\":\"none\""));
        expectReject(fails, "id with caps/path", ok.replace("\"id\":\"x\"", "\"id\":\"../X\""));
        expectReject(fails, "bad direction", ok.replace("\"in\"", "\"sideways\""));
        expectReject(fails, "unknown top key", ok.replace("\"id\"", "\"evil\":1,\"id\""));
        expectReject(fails, "unknown channel", ok.replace("\"blur\"", "\"gravity\""));
        expectReject(fails, "unknown curve key", ok.replace("\"value\":0.01", "\"value\":0.01,\"expr\":\"1+1\""));
        expectReject(fails, "unknown kind", ok.replace("\"constant\"", "\"expression\""));
        expectReject(fails, "blur above cap", ok.replace("0.01", "0.2"));
        expectReject(fails, "opacity above 1", ok.replace("\"blur\"", "\"opacity\"").replace("0.01", "2"));
        expectReject(fails, "duplicate key", ok.replace("\"schema\":1,", "\"schema\":1,\"schema\":1,"));
        expectReject(fails, "trailing garbage", ok + " x");
        expectReject(fails, "NaN literal", ok.replace("0.01", "NaN"));
        expectReject(fails, "1e999", ok.replace("0.01", "1e999"));
        expectReject(fails, "leading zero number", ok.replace("0.01", "01"));
        expectReject(fails, "empty channels", ok.replace("\"blur\":{\"kind\":\"constant\",\"value\":0.01}", ""));
        expectReject(fails, "both mirrorOf and channels", ok.replace("\"channels\"", "\"mirrorOf\":\"unfold\",\"channels\""));
        expectReject(fails, "mirror of unknown", "{\"schema\":1,\"id\":\"m\",\"direction\":\"out\",\"mirrorOf\":\"nope\"}");
        expectReject(fails, "duration 0", ok.replace("\"direction\"", "\"defaultDuration\":0,\"direction\""));
        expectReject(fails, "duration 31", ok.replace("\"direction\"", "\"defaultDuration\":31,\"direction\""));
        expectReject(fails, "gaussian tau 0", "{\"schema\":1,\"id\":\"g\",\"direction\":\"in\",\"channels\":{\"hue\":{\"kind\":\"gaussian\",\"peak\":10,\"tau\":0}}}");
        expectReject(fails, "gaussian peak out of range", "{\"schema\":1,\"id\":\"g\",\"direction\":\"in\",\"channels\":{\"hue\":{\"kind\":\"gaussian\",\"peak\":999,\"tau\":0.5}}}");
        expectReject(fails, "non-increasing knots", "{\"schema\":1,\"id\":\"k\",\"direction\":\"in\",\"channels\":{\"hue\":{\"kind\":\"knots\",\"points\":[[0.5,1],[0.5,2]]}}}");
        expectReject(fails, "knot time > 1", "{\"schema\":1,\"id\":\"k\",\"direction\":\"in\",\"channels\":{\"hue\":{\"kind\":\"knots\",\"points\":[[0,1],[1.5,2]]}}}");
        expectReject(fails, "frames without referenceFrames", "{\"schema\":1,\"id\":\"k\",\"direction\":\"in\",\"channels\":{\"hue\":{\"kind\":\"knots\",\"frames\":[[0,1],[5,2]]}}}");
        expectReject(fails, "points and frames", "{\"schema\":1,\"id\":\"k\",\"direction\":\"in\",\"referenceFrames\":9,\"channels\":{\"hue\":{\"kind\":\"knots\",\"points\":[[0,1]],\"frames\":[[0,1]]}}}");
        expectReject(fails, "knot not a pair", "{\"schema\":1,\"id\":\"k\",\"direction\":\"in\",\"channels\":{\"hue\":{\"kind\":\"knots\",\"points\":[[0,1,2]]}}}");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i <= ClipAnimationLoader.MAX_KNOTS_PER_CHANNEL; i++) many.append(i == 0 ? "" : ",").append("[").append(i / 1000.0).append(",1]");
        expectReject(fails, "too many knots", "{\"schema\":1,\"id\":\"k\",\"direction\":\"in\",\"channels\":{\"hue\":{\"kind\":\"knots\",\"points\":" + many + "]}}}");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) deep.append("[");
        expectReject(fails, "deep nesting", deep.toString());
        StringBuilder huge = new StringBuilder("{\"schema\":1,\"description\":\"");
        for (int i = 0; i < ClipAnimationLoader.MAX_JSON_CHARS; i++) huge.append('a');
        expectReject(fails, "oversized file", huge.append("\"}").toString());
        expectReject(fails, "null input", null);
        expectReject(fails, "empty input", "");
        expectReject(fails, "control char in string", ok.replace("\"x\"", "\"x\n\""));
        // rejected files must not have registered anything
        if (ClipAnimationLoader.get("../X") != null || ClipAnimationLoader.get("none") != null || ClipAnimationLoader.get(null) != null) fails.add("a rejected file got registered");
        // error messages should point at the problem
        try {
            ClipAnimationLoader.parse(ok.replace("0.01", "0.2"), "f.json");
            fails.add("range error expected");
        } catch (ClipAnimationLoader.FormatException e) {
            if (!e.getMessage().startsWith("f.json: ") || !e.getMessage().contains("$.channels.blur")) fails.add("error message lacks source/path: " + e.getMessage());
        }
        return fails;
    }

    private static void expectReject(List<String> fails, String what, String json) {
        expectReject(fails, what, json, true);
    }

    private static void expectReject(List<String> fails, String what, String json, boolean viaParse) {
        try {
            if (viaParse) ClipAnimationLoader.parse(json, null);
            else ClipAnimationLoader.register(json, null, false);
            fails.add("should have been rejected: " + what);
        } catch (ClipAnimationLoader.FormatException expected) {
            // good
        } catch (RuntimeException unexpected) {
            fails.add("rejected with the wrong exception type (" + unexpected + "): " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> fails = runAll();
        if (fails.isEmpty()) System.out.println("ALL CHECKS PASSED");
        else {
            for (String f : fails) System.out.println("FAIL: " + f);
            System.exit(1);
        }
    }
}
