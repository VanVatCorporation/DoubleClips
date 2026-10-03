package com.vanvatcorporation.doubleclips;

import java.util.HashMap;
import java.util.Map;

/**
 * A tiny evaluator for the subset of FFmpeg's expression language that ClipAnimationFFmpeg emits
 * (+ - * /, unary minus, parentheses, numbers, variables, and if/lt/lte/gt/gte/eq/between/min/max/
 * clip/exp/pow/abs/sin/cos/floor). Test-only: it lets the checks evaluate generated expressions
 * numerically without FFmpeg, and rejects anything outside that subset (so a typo in the generator
 * fails loudly instead of being "valid" by accident).
 */
final class FFmpegExprEval {
    private final String s;
    private final Map<String, Double> vars;
    private int i = 0;

    private FFmpegExprEval(String s, Map<String, Double> vars) {
        this.s = s;
        this.vars = vars;
    }

    static double eval(String expr, Map<String, Double> vars) {
        FFmpegExprEval e = new FFmpegExprEval(expr, vars);
        double v = e.expr();
        if (e.i != expr.length()) throw new IllegalArgumentException("trailing text at " + e.i + " in: " + expr);
        return v;
    }

    static Map<String, Double> vars(Object... kv) {
        Map<String, Double> m = new HashMap<>();
        for (int k = 0; k < kv.length; k += 2) m.put((String) kv[k], ((Number) kv[k + 1]).doubleValue());
        return m;
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private double expr() {
        double v = term();
        while (peek() == '+' || peek() == '-') {
            char op = s.charAt(i++);
            double r = term();
            v = op == '+' ? v + r : v - r;
        }
        return v;
    }

    private double term() {
        double v = factor();
        while (peek() == '*' || peek() == '/') {
            char op = s.charAt(i++);
            double r = factor();
            v = op == '*' ? v * r : v / r;
        }
        return v;
    }

    private double factor() {
        if (peek() == '-') { i++; return -factor(); }
        if (peek() == '+') { i++; return factor(); }
        return primary();
    }

    private double primary() {
        char c = peek();
        if (c == '(') {
            i++;
            double v = expr();
            expect(')');
            return v;
        }
        if (Character.isDigit(c) || c == '.') {
            int st = i;
            while (Character.isDigit(peek()) || peek() == '.') i++;
            return Double.parseDouble(s.substring(st, i));
        }
        if (Character.isLetter(c)) {
            int st = i;
            while (Character.isLetterOrDigit(peek()) || peek() == '_') i++;
            String name = s.substring(st, i);
            if (peek() != '(') {
                Double v = vars.get(name);
                if (v == null) throw new IllegalArgumentException("unknown variable '" + name + "'");
                return v;
            }
            i++;
            double[] a = new double[4];
            int n = 0;
            if (peek() != ')') {
                do {
                    if (n > 0) i++; // the comma
                    if (n == 4) throw new IllegalArgumentException("too many arguments to " + name);
                    a[n++] = expr();
                } while (peek() == ',');
            }
            expect(')');
            return call(name, a, n);
        }
        throw new IllegalArgumentException("unexpected '" + c + "' at " + i + " in: " + s);
    }

    private void expect(char c) {
        if (peek() != c) throw new IllegalArgumentException("expected '" + c + "' at " + i + " in: " + s);
        i++;
    }

    private static double call(String f, double[] a, int n) {
        switch (f) {
            case "if": need(f, n, 3); return a[0] != 0 ? a[1] : a[2];
            case "lt": need(f, n, 2); return a[0] < a[1] ? 1 : 0;
            case "lte": need(f, n, 2); return a[0] <= a[1] ? 1 : 0;
            case "gt": need(f, n, 2); return a[0] > a[1] ? 1 : 0;
            case "gte": need(f, n, 2); return a[0] >= a[1] ? 1 : 0;
            case "eq": need(f, n, 2); return a[0] == a[1] ? 1 : 0;
            case "between": need(f, n, 3); return (a[0] >= a[1] && a[0] <= a[2]) ? 1 : 0;
            case "min": need(f, n, 2); return Math.min(a[0], a[1]);
            case "max": need(f, n, 2); return Math.max(a[0], a[1]);
            case "clip": need(f, n, 3); return Math.max(a[1], Math.min(a[2], a[0]));
            case "exp": need(f, n, 1); return Math.exp(a[0]);
            case "pow": need(f, n, 2); return Math.pow(a[0], a[1]);
            case "abs": need(f, n, 1); return Math.abs(a[0]);
            case "sin": need(f, n, 1); return Math.sin(a[0]);
            case "cos": need(f, n, 1); return Math.cos(a[0]);
            case "floor": need(f, n, 1); return Math.floor(a[0]);
            default: throw new IllegalArgumentException("function not in the supported subset: " + f);
        }
    }

    private static void need(String f, int n, int want) {
        if (n != want) throw new IllegalArgumentException(f + " takes " + want + " arguments, got " + n);
    }
}
