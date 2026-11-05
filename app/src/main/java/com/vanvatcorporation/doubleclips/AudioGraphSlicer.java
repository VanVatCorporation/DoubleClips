package com.vanvatcorporation.doubleclips;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts the AUDIO half out of the export's filter graph (the OpenGL export draws the picture itself and only asks FFmpeg for the sound).
 * <p>
 * A statement is {@code [in]...filters...[out];}. The audio half is everything the final {@code [aout]} depends on: start at the
 * statement that makes {@code [aout]} and keep every statement that makes one of the labels it reads, and so on back to the
 * {@code [N:a]} input streams. That also keeps the intermediate steps of a clip at another speed ({@code [2:a]...[asp0];} then
 * {@code [asp0]...[audio-1];}, or the asplit / concat pieces), which the old "keep the statements that start with [N:a]" rule dropped and
 * so left {@code [audio-1]} undefined ("Stream specifier 'audio-1' ... matches no streams").
 * <p>
 * Plain Java, so it can be checked on its own.
 */
public final class AudioGraphSlicer {

    private AudioGraphSlicer() {}

    private static final Pattern LEADING_LABELS = Pattern.compile("^((?:\\[[^\\]]+\\])+)");
    private static final Pattern TRAILING_LABELS = Pattern.compile("((?:\\[[^\\]]+\\])+)$");
    private static final Pattern LABEL = Pattern.compile("\\[[^\\]]+\\]");

    /**
     * @param graph       the whole filter graph, statements separated by {@code ;\n}
     * @param finalLabel  the label the audio ends in, e.g. {@code [aout]}
     * @return the statements the final label needs, in their original order, each ending {@code ;\n}; empty if none makes it
     */
    public static String slice(String graph, String finalLabel) {
        String[] raw = graph.split(";\n");
        List<String> statements = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
            if (!t.isEmpty()) statements.add(t);
        }

        List<List<String>> inputs = new ArrayList<>();
        Map<String, Integer> producerOf = new HashMap<>();
        for (int i = 0; i < statements.size(); i++) {
            String st = statements.get(i);
            Matcher in = LEADING_LABELS.matcher(st);
            inputs.add(in.find() ? labelsOf(in.group(1)) : new ArrayList<>());
            Matcher out = TRAILING_LABELS.matcher(st);
            if (out.find()) for (String label : labelsOf(out.group(1))) producerOf.put(label, i);
        }

        Integer last = producerOf.get(finalLabel);
        if (last == null) return "";
        Set<Integer> keep = new HashSet<>();
        Deque<Integer> todo = new ArrayDeque<>();
        todo.push(last);
        while (!todo.isEmpty()) {
            int i = todo.pop();
            if (!keep.add(i)) continue;
            for (String label : inputs.get(i)) {
                Integer producer = producerOf.get(label); // an input stream like [2:a] has none: it is the graph's own input
                if (producer != null && producer != i) todo.push(producer);
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < statements.size(); i++) {
            if (keep.contains(i)) out.append(statements.get(i)).append(";\n");
        }
        return out.toString();
    }

    private static List<String> labelsOf(String run) {
        List<String> labels = new ArrayList<>();
        Matcher m = LABEL.matcher(run);
        while (m.find()) labels.add(m.group());
        return labels;
    }
}
