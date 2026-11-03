package com.vanvatcorporation.doubleclips.helper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The plain-Java rules of template search, apart from the network and the screens (shared with Desktop and iOS): reading what the server sends for
 * "trending" and "suggest" (tolerantly, like the iOS app), and the instant on-device suggestions. Works on what Gson
 * makes of JSON when asked for an Object (Map / List / String / Double / Boolean).
 */
public final class TemplateSearchParse {

    private TemplateSearchParse() {}

    /** {@code [...]}, {@code {items|data|results|keywords: [...]}}; anything else is no rows. */
    @SuppressWarnings("unchecked")
    public static List<Object> rows(Object json) {
        if (json instanceof List) return (List<Object>) json;
        if (json instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) json;
            for (String key : new String[]{"items", "data", "results", "keywords"}) {
                Object v = map.get(key);
                if (v instanceof List) return (List<Object>) v;
            }
        }
        return new ArrayList<>();
    }

    /** A row is a string, or an object with the text under query / text / keyword / title. Trimmed; null when empty. */
    @SuppressWarnings("unchecked")
    public static String textOf(Object row) {
        String value = null;
        if (row instanceof String) {
            value = (String) row;
        } else if (row instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) row;
            for (String key : new String[]{"query", "text", "keyword", "title"}) {
                Object v = map.get(key);
                if (v != null) {
                    value = v instanceof String ? (String) v : null;
                    break;
                }
            }
        }
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static Integer intOf(Object any) {
        if (any instanceof Number) return ((Number) any).intValue();
        if (any instanceof String) {
            try {
                return Integer.parseInt(((String) any).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** The hot searches, best first, each text once (case-insensitive). */
    @SuppressWarnings("unchecked")
    public static List<TrendingSearch> trending(Object json) {
        List<TrendingSearch> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object row : rows(json)) {
            String text = textOf(row);
            if (text == null || !seen.add(text.toLowerCase(Locale.ROOT))) continue;
            Map<String, Object> map = row instanceof Map ? (Map<String, Object>) row : null;
            Integer count = null;
            boolean hot = false;
            if (map != null) {
                Object c = map.get("count");
                if (c == null) c = map.get("searchCount");
                if (c == null) c = map.get("score");
                count = intOf(c);
                hot = Boolean.TRUE.equals(map.get("hot"));
            }
            out.add(new TrendingSearch(text, count, hot));
        }
        return out;
    }

    /** Suggested searches, in the server's order, each once (case-insensitive). */
    public static List<String> suggestions(Object json) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object row : rows(json)) {
            String text = textOf(row);
            if (text != null && seen.add(text.toLowerCase(Locale.ROOT))) out.add(text);
        }
        return out;
    }

    /** 1234 -> "1.2K", 2500000 -> "2.5M", 87 -> "87". */
    public static String compactCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    /** Lower case without accents (and d for đ), so "tet" finds "Tết" and "dep" finds "Đẹp". */
    public static String fold(String s) {
        String lower = s.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /**
     * Matches that need no network, so typing answers at once: the history, then the hot searches, then the given titles
     * (the templates the app already shows), those that start with what was typed first. At most 10, each once.
     */
    public static List<String> localSuggestions(String typed, List<String> history, List<TrendingSearch> trending, List<String> titles) {
        String needle = fold(typed.trim());
        List<String> out = new ArrayList<>();
        if (needle.isEmpty()) return out;
        List<String> pool = new ArrayList<>(history);
        for (TrendingSearch t : trending) pool.add(t.text);
        pool.addAll(titles);
        List<String> prefixHits = new ArrayList<>(), otherHits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String candidate : pool) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            String folded = fold(candidate);
            if (!folded.contains(needle) || !seen.add(candidate.toLowerCase(Locale.ROOT))) continue;
            (folded.startsWith(needle) ? prefixHits : otherHits).add(candidate);
        }
        out.addAll(prefixHits);
        out.addAll(otherHits);
        return out.size() > 10 ? new ArrayList<>(out.subList(0, 10)) : out;
    }

    /** How much of {@code suggestion} the user already typed (counted in characters), or 0 when it doesn't start with it. */
    public static int typedPrefixLength(String suggestion, String typed) {
        String t = typed.trim();
        if (t.isEmpty() || suggestion.length() < t.length()) return 0;
        return suggestion.regionMatches(true, 0, t, 0, t.length()) ? t.length() : 0;
    }
}
