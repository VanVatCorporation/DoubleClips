package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filter ops for effect styles ({@code animations/effects/*.json -> "filters"}) and mask shapes for transition styles
 * ({@code animations/transitions/*.json -> "mask"}). The same format iOS reads:
 *
 * <pre>
 *   "filters": [ { "op": "vignette", "intensity": 1.2, "radius": 1.4 },
 *                { "op": "blur", "radius": { "kind": "knots", "interp": "smooth", "points": [[0, 0], [1, 0.02]] } } ],
 *   "filterStrength": "scale"     // "scale" (default) | "mix" | "both" | "none"
 *
 *   "mask": { "shape": "wipe", "reveal": { "kind": "knots", "points": [[0, 0], [1, 1]] }, "angle": 180 }
 * </pre>
 *
 * An effect style may list filters in addition to (or instead of) its channels; channels are applied first, then the
 * filters in order. Every parameter is a number or a curve ("constant" / "knots"; p = 0..1 over the clip, or looped over
 * the style's period, or run over its span), validated against the ranges in {@link EffectFilterSpecs}. Which ops exist
 * is fixed in the app: JSON can pick, order and parameterize them, never invent new ones, so nothing in a file can
 * execute anything. Distances are fractions of the canvas (see each op in the renderer).
 *
 * Parsing only: this class draws nothing. Plain Java (no Android classes).
 */
public final class EffectFilterOps {

    public static final int MAX_FILTERS = 16;
    public static final int MAX_KNOTS = 512;

    private EffectFilterOps() {}

    // ---- registry types ----------------------------------------------------------------

    /** One numeric parameter of an op or mask shape. */
    public static final class Param {
        public final String name;
        /** Used when the file leaves the parameter out. */
        public final double def;
        /** "No effect" value: the strength slider scales the distance from here. */
        public final double neutral;
        public final double min, max;
        /** Whether the strength slider scales it. */
        public final boolean scaled;

        Param(String name, double def, double neutral, double min, double max, boolean scaled) {
            this.name = name;
            this.def = def;
            this.neutral = neutral;
            this.min = min;
            this.max = max;
            this.scaled = scaled;
        }
    }

    /** One string parameter that must be one of a fixed list (photoEffect's "name"). */
    public static final class StringOption {
        public final String name;
        public final List<String> allowed;
        public final String def;

        StringOption(String name, String[] allowed, String def) {
            this.name = name;
            this.allowed = Collections.unmodifiableList(Arrays.asList(allowed));
            this.def = def;
        }
    }

    /** Everything an op (or mask shape) accepts. */
    public static final class Spec {
        public final List<Param> params;
        public final List<String> flags;
        public final List<StringOption> strings;

        Spec(Param[] params, String[] flags, StringOption[] strings) {
            this.params = Collections.unmodifiableList(Arrays.asList(params));
            this.flags = Collections.unmodifiableList(Arrays.asList(flags));
            this.strings = Collections.unmodifiableList(Arrays.asList(strings));
        }

        public Param param(String name) {
            for (Param p : params) if (p.name.equals(name)) return p;
            return null;
        }
    }

    /** What the effect's strength slider does to a style's filters. */
    public enum Strength {
        /** Every parameter marked scaled moves from its neutral value by strength x (a vignette of 1.2 becomes 2.4 at 2). */
        SCALE("scale"),
        /** The result of the whole filter chain is blended over the picture before it, by strength (full at 1 and above). */
        MIX("mix"),
        /** Scale, then mix. */
        BOTH("both"),
        /** The slider doesn't change the filters. */
        NONE("none");

        public final String json;

        Strength(String json) { this.json = json; }

        public boolean scales() { return this == SCALE || this == BOTH; }
        public boolean mixes() { return this == MIX || this == BOTH; }

        static Strength fromJson(String s) {
            for (Strength v : values()) if (v.json.equals(s)) return v;
            return null;
        }
    }

    // ---- curves ------------------------------------------------------------------------

    /** A parameter's value over p = 0..1: a constant, or piecewise-linear (or smoothstep-eased) knots. */
    public static abstract class Curve {
        public abstract double at(double t);
    }

    static final class ConstantCurve extends Curve {
        private final double value;
        ConstantCurve(double value) { this.value = value; }
        @Override public double at(double t) { return value; }
    }

    static final class KnotsCurve extends Curve {
        private final double[] ps, vs;
        private final boolean smooth;

        KnotsCurve(double[] ps, double[] vs, boolean smooth) {
            this.ps = ps;
            this.vs = vs;
            this.smooth = smooth;
        }

        /** Holds the first / last value outside the knots. */
        @Override public double at(double t) {
            if (t <= ps[0]) return vs[0];
            int last = ps.length - 1;
            if (t >= ps[last]) return vs[last];
            int i = 1;
            while (i < last && t > ps[i]) i++;
            double u = (t - ps[i - 1]) / (ps[i] - ps[i - 1]);
            double k = smooth ? u * u * (3 - 2 * u) : u;
            return vs[i - 1] + (vs[i] - vs[i - 1]) * k;
        }
    }

    // ---- a parsed filter / mask --------------------------------------------------------

    /** One parsed {@code filters} entry or {@code mask} object. Immutable. */
    public static final class Filter {
        private final String op;
        private final Spec spec;
        private final Map<String, Curve> curves;
        private final Set<String> flags;
        private final Map<String, String> strings;

        Filter(String op, Spec spec, Map<String, Curve> curves, Set<String> flags, Map<String, String> strings) {
            this.op = op;
            this.spec = spec;
            this.curves = Collections.unmodifiableMap(curves);
            this.flags = Collections.unmodifiableSet(flags);
            this.strings = Collections.unmodifiableMap(strings);
        }

        /** The op name ({@code "vignette"}) or, for a mask, the shape name ({@code "wipe"}). */
        public String getOp() { return op; }
        public Spec getSpec() { return spec; }
        public boolean hasFlag(String flag) { return flags.contains(flag); }
        /** A string parameter's value ({@code photoEffect.name}); its default when the file left it out. */
        public String getString(String name) { return strings.get(name); }

        /** The raw (unscaled) value of a parameter at curve progress t, or 0 for a name the op doesn't have. */
        public double raw(String name, double t) {
            Curve c = curves.get(name);
            return c == null ? 0.0 : c.at(t);
        }

        /**
         * The parameter at curve progress {@code t}, moved by the strength slider when {@code scales} is set and the
         * parameter is one the slider scales, then clamped to its range. Same rule as iOS.
         */
        public double value(String name, double t, boolean scales, double strength) {
            Param p = spec.param(name);
            Curve c = curves.get(name);
            if (p == null || c == null) return 0.0;
            double x = c.at(t);
            if (scales && p.scaled) x = p.neutral + (x - p.neutral) * strength;
            return Math.min(Math.max(x, p.min), p.max);
        }
    }

    // ---- parsing -----------------------------------------------------------------------

    /** The {@code filters} array of an effect style. */
    public static List<Filter> parse(Object value, String path) throws ClipAnimationLoader.FormatException {
        if (!(value instanceof List)) throw new ClipAnimationLoader.FormatException(path + ": must be an array of filter objects");
        List<?> array = (List<?>) value;
        if (array.size() > MAX_FILTERS) throw new ClipAnimationLoader.FormatException(path + ": at most " + MAX_FILTERS + " filters");
        List<Filter> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String p = path + "[" + i + "]";
            out.add(parseSpec(ClipAnimationLoader.asObject(array.get(i), p), p, "op", EffectFilterSpecs.FILTERS));
        }
        return out;
    }

    /** A transition style's {@code mask} object: {@code { "shape": "wipe", params... }} ({@code reveal} is required). */
    public static Filter parseMask(Object value, String path) throws ClipAnimationLoader.FormatException {
        Map<String, Object> object = ClipAnimationLoader.asObject(value, path);
        // Checked on the FILE, not on the parsed result: parseSpec fills every missing parameter with its default, so a mask
        // without "reveal" would otherwise become "reveal = 0 forever" (B never appears). iOS has the same guard written
        // against the parsed result, where it can never fail.
        if (!object.containsKey("reveal")) throw new ClipAnimationLoader.FormatException(path + ".reveal: required");
        return parseSpec(object, path, "shape", EffectFilterSpecs.MASKS);
    }

    /** One {@code { "<key>": name, params... }} object checked against {@code table} (filters use "op", masks "shape"). */
    static Filter parseSpec(Map<String, Object> object, String p, String opKey, Map<String, Spec> table)
            throws ClipAnimationLoader.FormatException {
        String op = ClipAnimationLoader.reqString(object, opKey, p, 24);
        Spec spec = table.get(op);
        if (spec == null) throw new ClipAnimationLoader.FormatException(p + "." + opKey + ": unknown '" + op + "'");

        Set<String> allowed = new HashSet<>();
        allowed.add(opKey);
        for (Param param : spec.params) allowed.add(param.name);
        allowed.addAll(spec.flags);
        for (StringOption s : spec.strings) allowed.add(s.name);
        ClipAnimationLoader.allowKeys(object, p, allowed.toArray(new String[0]));

        Map<String, Curve> curves = new LinkedHashMap<>();
        for (Param param : spec.params) {
            String path = p + "." + param.name;
            if (!object.containsKey(param.name)) {
                curves.put(param.name, new ConstantCurve(param.def));
                continue;
            }
            Object raw = object.get(param.name);
            if (raw instanceof Map) {
                curves.put(param.name, parseCurve(ClipAnimationLoader.asObject(raw, path), param, path));
            } else {
                double v = ClipAnimationLoader.reqNumber(object, param.name, p);
                check(v, param, path);
                curves.put(param.name, new ConstantCurve(v));
            }
        }
        Set<String> flags = new HashSet<>();
        for (String flag : spec.flags) {
            if (!object.containsKey(flag)) continue;
            Object raw = object.get(flag);
            if (!(raw instanceof Boolean)) throw new ClipAnimationLoader.FormatException(p + "." + flag + ": must be true or false");
            if ((Boolean) raw) flags.add(flag);
        }
        Map<String, String> strings = new LinkedHashMap<>();
        for (StringOption option : spec.strings) {
            if (!object.containsKey(option.name)) {
                strings.put(option.name, option.def);
                continue;
            }
            String v = ClipAnimationLoader.reqString(object, option.name, p, 24);
            if (!option.allowed.contains(v)) {
                throw new ClipAnimationLoader.FormatException(p + "." + option.name + ": must be one of " + join(option.allowed));
            }
            strings.put(option.name, v);
        }
        return new Filter(op, spec, curves, flags, strings);
    }

    private static Curve parseCurve(Map<String, Object> m, Param param, String path) throws ClipAnimationLoader.FormatException {
        String kind = ClipAnimationLoader.reqString(m, "kind", path, 16);
        if ("constant".equals(kind)) {
            ClipAnimationLoader.allowKeys(m, path, "kind", "value");
            double v = ClipAnimationLoader.reqNumber(m, "value", path);
            check(v, param, path + ".value");
            return new ConstantCurve(v);
        }
        if ("knots".equals(kind)) {
            ClipAnimationLoader.allowKeys(m, path, "kind", "interp", "points");
            boolean smooth = false;
            if (m.containsKey("interp")) {
                String interp = ClipAnimationLoader.reqString(m, "interp", path, 16);
                if (!"linear".equals(interp) && !"smooth".equals(interp)) {
                    throw new ClipAnimationLoader.FormatException(path + ".interp: must be \"linear\" or \"smooth\"");
                }
                smooth = "smooth".equals(interp);
            }
            Object raw = m.get("points");
            if (!(raw instanceof List) || ((List<?>) raw).isEmpty() || ((List<?>) raw).size() > MAX_KNOTS) {
                throw new ClipAnimationLoader.FormatException(path + ".points: needs 1 to " + MAX_KNOTS + " [p, value] pairs");
            }
            List<?> points = (List<?>) raw;
            double[] ps = new double[points.size()];
            double[] vs = new double[points.size()];
            for (int i = 0; i < ps.length; i++) {
                String kp = path + ".points[" + i + "]";
                Object item = points.get(i);
                if (!(item instanceof List) || ((List<?>) item).size() != 2
                        || !(((List<?>) item).get(0) instanceof Double) || !(((List<?>) item).get(1) instanceof Double)) {
                    throw new ClipAnimationLoader.FormatException(kp + ": must be [p, value]");
                }
                double pp = (Double) ((List<?>) item).get(0);
                double v = (Double) ((List<?>) item).get(1);
                if (!(pp >= 0 && pp <= 1) || (i > 0 && !(pp > ps[i - 1]))) {
                    throw new ClipAnimationLoader.FormatException(kp + ": p must be 0..1 and increasing");
                }
                check(v, param, kp);
                ps[i] = pp;
                vs[i] = v;
            }
            return new KnotsCurve(ps, vs, smooth);
        }
        throw new ClipAnimationLoader.FormatException(path + ".kind: must be \"constant\" or \"knots\"");
    }

    private static void check(double v, Param param, String path) throws ClipAnimationLoader.FormatException {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < param.min || v > param.max) {
            throw new ClipAnimationLoader.FormatException(path + ": " + fmt(v) + " is outside " + fmt(param.min) + " ... " + fmt(param.max));
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString((long) d) : Double.toString(d);
    }
}
