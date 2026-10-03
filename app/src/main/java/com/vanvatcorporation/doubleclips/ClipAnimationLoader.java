package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses, validates and registers clip-animation JSON files (see {@link ClipAnimation}),
 * and is the lookup the export engines use: {@code ClipAnimationLoader.get("unfold")}.
 *
 * File format (schema 1):
 * <pre>
 * {
 *   "schema": 1,
 *   "id": "unfold",                 // [a-z0-9_-]{1,64}, not "none"; what Clip.inAnimation.type stores
 *   "name": "Unfold",               // optional, shown in pickers
 *   "direction": "in",              // "in" | "out"
 *   "defaultDuration": 1.5,         // optional, seconds, (0, 30]; default 0.5
 *   "referenceFrames": 45,          // only needed by "frames" knots below
 *   "channels": {                   // OR "mirrorOf": "&lt;id of an already-registered animation&gt;"
 *     "brightness": { "kind": "gaussian", "base": 0, "peak": 3.97, "tau": 0.467 },
 *     "blur":       { "kind": "knots", "points": [[0, 0.025], [0.3, 0.01], [0.5, 0]] },
 *     "warp.height":{ "kind": "knots", "frames": [[0, 0.955], [10, 1.017]], "interp": "smooth" },
 *     "opacity":    { "kind": "constant", "value": 1 }
 *   }
 * }
 * </pre>
 * Optional "description" / "author" strings (max 256 chars) are accepted and ignored.
 *
 * SAFETY. Files may one day come from outside the app, so this is strict and bounded:
 * a hand-written RFC 8259 parser (no reflection / no object mapping), 256K characters max,
 * nesting depth 12, 512 knots per channel, duplicate keys / unknown keys / unknown channels /
 * trailing data / NaN / Infinity all rejected, and every value must lie inside its channel's
 * range (ClipAnimation.Channel). Nothing is ever evaluated as an expression. A bad file
 * throws {@link FormatException} with a path to the problem and registers nothing.
 *
 * Plain Java (no Android classes); reading files/assets is the platform adapter's job
 * (see ClipAnimationAssets).
 */
public final class ClipAnimationLoader {

    public static final int SUPPORTED_SCHEMA = 1;
    public static final int MAX_JSON_CHARS = 256 * 1024;
    public static final int MAX_KNOTS_PER_CHANNEL = 512;
    public static final double MAX_DEFAULT_DURATION = 30.0;
    public static final double DEFAULT_DURATION_FALLBACK = 0.5;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_ARRAY_ELEMENTS = 4096;
    private static final int MAX_STRING_CHARS = 4096;
    private static final int MAX_NAME_CHARS = 64;
    private static final int MAX_NOTE_CHARS = 256;

    private ClipAnimationLoader() {}

    /** A rejected animation file. {@link #missingMirrorBase} lets callers retry once the base is registered. */
    public static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean missingMirrorBase;
        FormatException(String message) { this(message, false); }
        FormatException(String message, boolean missingMirrorBase) {
            super(message);
            this.missingMirrorBase = missingMirrorBase;
        }
    }

    // ---- registry ----------------------------------------------------------------------

    private static final Map<String, ClipAnimation> REGISTRY = new ConcurrentHashMap<>();
    private static final Set<String> BUILT_IN_IDS = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** The animation registered under id, or null (also for null / "none"). */
    public static ClipAnimation get(String id) {
        return id == null ? null : REGISTRY.get(id);
    }

    /**
     * The animation registered under id only if it has the wanted direction, else null. This is
     * how the engines read a clip's in / out slot: an unknown id or the wrong direction animates nothing.
     */
    public static ClipAnimation get(String id, ClipAnimation.Direction wanted) {
        ClipAnimation a = get(id);
        return (a != null && a.getDirection() == wanted) ? a : null;
    }

    /** All registered animations of one direction, sorted by id. */
    public static List<ClipAnimation> list(ClipAnimation.Direction direction) {
        List<ClipAnimation> out = new ArrayList<>();
        for (ClipAnimation a : REGISTRY.values()) if (a.getDirection() == direction) out.add(a);
        Collections.sort(out, new Comparator<ClipAnimation>() {
            @Override public int compare(ClipAnimation a, ClipAnimation b) { return a.getId().compareTo(b.getId()); }
        });
        return out;
    }

    /**
     * Parses and registers one file. A built-in id can only be replaced by another built-in
     * (a user/imported file may not shadow it). Registers nothing on failure.
     *
     * @param source   file name or other label, used only in error messages
     * @param builtIn  true for the app's own bundled animations
     */
    public static ClipAnimation register(String json, String source, boolean builtIn) throws FormatException {
        return register(json, source, builtIn, null);
    }

    /**
     * Like {@link #register(String, String, boolean)}, and when {@code expectedDirection} isn't null
     * the file must declare exactly that direction (the asset / pack folder it was found in:
     * animations/in/ holds "in" animations, animations/out/ holds "out" ones).
     */
    public static ClipAnimation register(String json, String source, boolean builtIn,
                                         ClipAnimation.Direction expectedDirection) throws FormatException {
        ClipAnimation a = parse(json, source, null, expectedDirection);
        registerParsed(a, builtIn, source);
        return a;
    }

    /** Registers an already-parsed animation. A built-in id can only be replaced by another built-in. */
    static void registerParsed(ClipAnimation a, boolean builtIn, String source) throws FormatException {
        if (!builtIn && BUILT_IN_IDS.contains(a.getId())) {
            throw new FormatException(prefix(source) + "id '" + a.getId() + "' is a built-in animation and can't be replaced");
        }
        REGISTRY.put(a.getId(), a);
        if (builtIn) BUILT_IN_IDS.add(a.getId());
    }

    /** True if id belongs to one of the app's own bundled animations. */
    public static boolean isBuiltIn(String id) {
        return id != null && BUILT_IN_IDS.contains(id);
    }

    /** Removes an installed (non-built-in) animation from the registry. Returns false if it wasn't there or is built-in. */
    public static boolean unregister(String id) {
        if (id == null || BUILT_IN_IDS.contains(id)) return false;
        return REGISTRY.remove(id) != null;
    }

    /** Test hook: forget everything. */
    static void clearForTests() {
        REGISTRY.clear();
        BUILT_IN_IDS.clear();
    }

    // ---- parse + validate --------------------------------------------------------------

    private static String prefix(String source) {
        return (source == null || source.isEmpty()) ? "" : source + ": ";
    }

    private static final Set<String> TOP_KEYS = new HashSet<>(Arrays.asList(
            "schema", "id", "name", "direction", "defaultDuration", "referenceFrames",
            "mirrorOf", "channels", "description", "author"));

    /** Parses and validates without registering. A "mirrorOf" file needs its base already registered. */
    public static ClipAnimation parse(String json, String source) throws FormatException {
        return parse(json, source, null, null);
    }

    /**
     * Parses and validates without registering.
     *
     * @param extraBases        animations a "mirrorOf" may point at in addition to the registry (a pack being
     *                          validated before anything is registered); may be null
     * @param expectedDirection the direction the file's folder implies, or null for any
     */
    public static ClipAnimation parse(String json, String source, Map<String, ClipAnimation> extraBases,
                                      ClipAnimation.Direction expectedDirection) throws FormatException {
        final String pre = prefix(source);
        try {
            return parseInternal(json, extraBases, expectedDirection);
        } catch (FormatException e) {
            if (pre.isEmpty()) throw e;
            throw new FormatException(pre + e.getMessage(), e.missingMirrorBase);
        }
    }

    private static ClipAnimation parseInternal(String json, Map<String, ClipAnimation> extraBases,
                                               ClipAnimation.Direction expectedDirection) throws FormatException {
        if (json == null) throw new FormatException("no data");
        if (json.length() > MAX_JSON_CHARS) {
            throw new FormatException("file is larger than " + MAX_JSON_CHARS + " characters");
        }
        Object root = new Json(json).parseDocument();
        Map<String, Object> top = asObject(root, "$");
        for (String k : top.keySet()) {
            if (!TOP_KEYS.contains(k)) throw new FormatException("$." + k + ": unknown key");
        }

        double schema = reqNumber(top, "schema", "$");
        if (schema != SUPPORTED_SCHEMA) {
            throw new FormatException("$.schema: unsupported schema " + fmt(schema) + " (this app reads " + SUPPORTED_SCHEMA + ")");
        }

        String id = reqString(top, "id", "$", 64);
        if (!id.matches("[a-z0-9_-]{1,64}") || "none".equals(id)) {
            throw new FormatException("$.id: must be 1-64 characters of a-z 0-9 _ - and not 'none'");
        }
        String name = optString(top, "name", "$", MAX_NAME_CHARS);
        if (name == null || name.isEmpty()) name = id;
        optString(top, "description", "$", MAX_NOTE_CHARS);
        optString(top, "author", "$", MAX_NOTE_CHARS);

        String dirStr = reqString(top, "direction", "$", 8);
        ClipAnimation.Direction direction = ClipAnimation.Direction.fromJson(dirStr);
        if (direction == null) throw new FormatException("$.direction: must be \"in\" or \"out\"");
        if (expectedDirection != null && direction != expectedDirection) {
            throw new FormatException("$.direction: this file is in the \"" + expectedDirection.json
                    + "\" folder but declares direction \"" + direction.json + "\"");
        }

        double duration = DEFAULT_DURATION_FALLBACK;
        if (top.containsKey("defaultDuration")) {
            duration = reqNumber(top, "defaultDuration", "$");
            if (!(duration > 0.0 && duration <= MAX_DEFAULT_DURATION)) {
                throw new FormatException("$.defaultDuration: must be > 0 and <= " + (int) MAX_DEFAULT_DURATION + " seconds");
            }
        }

        boolean hasMirror = top.containsKey("mirrorOf");
        boolean hasChannels = top.containsKey("channels");
        if (hasMirror == hasChannels) {
            throw new FormatException("exactly one of \"mirrorOf\" or \"channels\" is required");
        }

        if (hasMirror) {
            String baseId = reqString(top, "mirrorOf", "$", 64);
            ClipAnimation base = extraBases != null && extraBases.containsKey(baseId) ? extraBases.get(baseId) : REGISTRY.get(baseId);
            if (base == null) {
                throw new FormatException("$.mirrorOf: '" + baseId + "' is not registered (load it first)", true);
            }
            if (baseId.equals(id)) throw new FormatException("$.mirrorOf: can't mirror itself");
            return ClipAnimation.mirror(base, id, name, direction, (float) duration);
        }

        int referenceFrames = 0;
        if (top.containsKey("referenceFrames")) {
            double rf = reqNumber(top, "referenceFrames", "$");
            if (rf != Math.rint(rf) || rf < 1 || rf > 10000) {
                throw new FormatException("$.referenceFrames: must be a whole number 1-10000");
            }
            referenceFrames = (int) rf;
        }

        Map<String, Object> chans = asObject(top.get("channels"), "$.channels");
        if (chans.isEmpty()) throw new FormatException("$.channels: at least one channel is required");
        EnumMap<ClipAnimation.Channel, ClipAnimation.Curve> curves = new EnumMap<>(ClipAnimation.Channel.class);
        for (Map.Entry<String, Object> e : chans.entrySet()) {
            ClipAnimation.Channel ch = ClipAnimation.Channel.fromJson(e.getKey());
            String path = "$.channels." + e.getKey();
            if (ch == null) throw new FormatException(path + ": unknown channel");
            curves.put(ch, parseCurve(asObject(e.getValue(), path), ch, referenceFrames, path));
        }
        return new ClipAnimation(id, name, direction, (float) duration, curves, false, referenceFrames);
    }

    private static ClipAnimation.Curve parseCurve(Map<String, Object> m, ClipAnimation.Channel ch,
                                                  int referenceFrames, String path) throws FormatException {
        String kind = reqString(m, "kind", path, 16);
        if ("constant".equals(kind)) {
            allowKeys(m, path, "kind", "value");
            double v = reqNumber(m, "value", path);
            checkRange(v, ch, path + ".value");
            return new ClipAnimation.ConstantCurve(v);
        }
        if ("gaussian".equals(kind)) {
            allowKeys(m, path, "kind", "base", "peak", "tau");
            double base = m.containsKey("base") ? reqNumber(m, "base", path) : ch.neutral;
            double peak = reqNumber(m, "peak", path);
            double tau = reqNumber(m, "tau", path);
            if (!(tau >= 0.01 && tau <= 10.0)) throw new FormatException(path + ".tau: must be between 0.01 and 10");
            checkRange(base, ch, path + ".base");
            checkRange(base + peak, ch, path + " (base + peak)");
            return new ClipAnimation.GaussianCurve(base, peak, tau);
        }
        if ("knots".equals(kind)) {
            allowKeys(m, path, "kind", "points", "frames", "interp");
            boolean hasPoints = m.containsKey("points");
            boolean hasFrames = m.containsKey("frames");
            if (hasPoints == hasFrames) throw new FormatException(path + ": exactly one of \"points\" or \"frames\" is required");
            boolean smooth = false;
            if (m.containsKey("interp")) {
                String interp = reqString(m, "interp", path, 16);
                if ("smooth".equals(interp)) smooth = true;
                else if (!"linear".equals(interp)) throw new FormatException(path + ".interp: must be \"linear\" or \"smooth\"");
            }
            String arrKey = hasPoints ? "points" : "frames";
            List<Object> arr = asArray(m.get(arrKey), path + "." + arrKey);
            if (arr.isEmpty()) throw new FormatException(path + "." + arrKey + ": at least one knot is required");
            if (arr.size() > MAX_KNOTS_PER_CHANNEL) {
                throw new FormatException(path + "." + arrKey + ": more than " + MAX_KNOTS_PER_CHANNEL + " knots");
            }
            if (hasFrames && referenceFrames == 0) {
                throw new FormatException(path + ".frames: needs a top-level \"referenceFrames\"");
            }
            double[] ps = new double[arr.size()];
            double[] vs = new double[arr.size()];
            for (int i = 0; i < ps.length; i++) {
                String kp = path + "." + arrKey + "[" + i + "]";
                List<Object> pair = asArray(arr.get(i), kp);
                if (pair.size() != 2 || !(pair.get(0) instanceof Double) || !(pair.get(1) instanceof Double)) {
                    throw new FormatException(kp + ": must be [time, value]");
                }
                double t = (Double) pair.get(0);
                double p = hasFrames ? t / referenceFrames : t;
                if (!(p >= 0.0 && p <= 1.0)) {
                    throw new FormatException(kp + ": time must be within 0..1" + (hasFrames ? " (frame / referenceFrames)" : ""));
                }
                if (i > 0 && !(p > ps[i - 1])) throw new FormatException(kp + ": times must be strictly increasing");
                double v = (Double) pair.get(1);
                checkRange(v, ch, kp);
                ps[i] = p;
                vs[i] = v;
            }
            return new ClipAnimation.KnotsCurve(ps, vs, smooth);
        }
        throw new FormatException(path + ".kind: must be \"constant\", \"knots\" or \"gaussian\"");
    }

    private static void checkRange(double v, ClipAnimation.Channel ch, String path) throws FormatException {
        if (!(v >= ch.min && v <= ch.max)) {
            throw new FormatException(path + ": " + fmt(v) + " is outside the allowed range of '" + ch.json
                    + "' (" + fmt(ch.min) + " .. " + fmt(ch.max) + ")");
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString((long) d) : Double.toString(d);
    }

    // ---- typed accessors ---------------------------------------------------------------

    /** Strictly parses a JSON document whose root must be an object (shared with the pack manifest reader). */
    static Map<String, Object> parseObject(String json) throws FormatException {
        if (json == null) throw new FormatException("no data");
        if (json.length() > MAX_JSON_CHARS) throw new FormatException("file is larger than " + MAX_JSON_CHARS + " characters");
        return asObject(new Json(json).parseDocument(), "$");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object o, String path) throws FormatException {
        if (!(o instanceof Map)) throw new FormatException(path + ": must be an object");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object o, String path) throws FormatException {
        if (!(o instanceof List)) throw new FormatException(path + ": must be an array");
        return (List<Object>) o;
    }

    static String reqString(Map<String, Object> m, String key, String path, int maxChars) throws FormatException {
        Object o = m.get(key);
        if (!(o instanceof String)) throw new FormatException(path + "." + key + ": required string");
        String s = (String) o;
        if (s.length() > maxChars) throw new FormatException(path + "." + key + ": longer than " + maxChars + " characters");
        return s;
    }

    static String optString(Map<String, Object> m, String key, String path, int maxChars) throws FormatException {
        if (!m.containsKey(key)) return null;
        return reqString(m, key, path, maxChars);
    }

    static double reqNumber(Map<String, Object> m, String key, String path) throws FormatException {
        Object o = m.get(key);
        if (!(o instanceof Double)) throw new FormatException(path + "." + key + ": required number");
        return (Double) o;
    }

    static void allowKeys(Map<String, Object> m, String path, String... allowed) throws FormatException {
        Set<String> ok = new HashSet<>(Arrays.asList(allowed));
        for (String k : m.keySet()) if (!ok.contains(k)) throw new FormatException(path + "." + k + ": unknown key");
    }

    // ---- minimal strict JSON (RFC 8259) --------------------------------------------------

    private static final class Json {
        private final String s;
        private int i = 0;

        Json(String s) { this.s = s; }

        Object parseDocument() throws FormatException {
            skipWs();
            Object v = value(0);
            skipWs();
            if (i != s.length()) throw err("unexpected data after the JSON document");
            return v;
        }

        private FormatException err(String msg) {
            int line = 1, col = 1;
            for (int k = 0; k < i && k < s.length(); k++) {
                if (s.charAt(k) == '\n') { line++; col = 1; } else col++;
            }
            return new FormatException("JSON error at line " + line + ", column " + col + ": " + msg);
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        private Object value(int depth) throws FormatException {
            if (depth > MAX_DEPTH) throw err("nested deeper than " + MAX_DEPTH + " levels");
            skipWs();
            if (i >= s.length()) throw err("unexpected end of data");
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("unexpected character '" + c + "'");
        }

        private Map<String, Object> object(int depth) throws FormatException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != '"') throw err("expected a string key");
                String key = string();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
                i++;
                Object v = value(depth + 1);
                if (m.containsKey(key)) throw err("duplicate key \"" + key + "\"");
                m.put(key, v);
                skipWs();
                if (i >= s.length()) throw err("unexpected end of data");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') { i--; throw err("expected ',' or '}'"); }
            }
        }

        private List<Object> array(int depth) throws FormatException {
            List<Object> a = new ArrayList<>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                if (a.size() >= MAX_ARRAY_ELEMENTS) throw err("array longer than " + MAX_ARRAY_ELEMENTS + " elements");
                a.add(value(depth + 1));
                skipWs();
                if (i >= s.length()) throw err("unexpected end of data");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') { i--; throw err("expected ',' or ']'"); }
            }
        }

        private String string() throws FormatException {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) { i--; throw err("control character in string"); }
                if (c == '\\') {
                    if (i >= s.length()) throw err("unterminated string");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("bad \\u escape");
                            int cp = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = Character.digit(s.charAt(i + k), 16);
                                if (d < 0) throw err("bad \\u escape");
                                cp = cp * 16 + d;
                            }
                            i += 4;
                            sb.append((char) cp);
                            break;
                        default: i--; throw err("bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
                if (sb.length() > MAX_STRING_CHARS) throw err("string longer than " + MAX_STRING_CHARS + " characters");
            }
        }

        private Double number() throws FormatException {
            int start = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw err("bad number");
            if (s.charAt(i) == '0') {
                i++;
            } else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') {
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            } else {
                throw err("bad number");
            }
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int d = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == d) throw err("bad number");
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int d = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == d) throw err("bad number");
            }
            double v;
            try {
                v = Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException ex) {
                throw err("bad number");
            }
            if (Double.isNaN(v) || Double.isInfinite(v)) throw err("number out of range");
            return v;
        }
    }
}
