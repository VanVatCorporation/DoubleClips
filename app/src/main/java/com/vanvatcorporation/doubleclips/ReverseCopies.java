package com.vanvatcorporation.doubleclips;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The VIDEO length of a file (frames, not the container), which {@link ReverseMap#copyTime} needs for a reversed copy. Reading the frame
 * times walks the file's index once (no decoding), so the answer is remembered by file + modified time + size. Thread-safe; shared by the
 * live preview and the export.
 */
public final class ReverseCopies {

    private ReverseCopies() {}

    private static final ConcurrentMap<String, Double> LENGTHS = new ConcurrentHashMap<>();

    /** Seconds of video in the file, or -1 when it can't be measured (unreadable, or no frames). */
    public static double videoLength(String path) {
        File file = new File(path);
        if (!file.isFile()) return -1;
        String key = file.getPath() + "|" + file.lastModified() + "|" + file.length();
        Double known = LENGTHS.get(key);
        if (known != null) return known;
        double length = -1;
        try {
            ReverseProcessor.FrameScan scan = ReverseProcessor.scanVideo(path);
            length = scan.lengthSeconds();
        } catch (Exception | OutOfMemoryError e) {
            length = -1;
        }
        LENGTHS.put(key, length);
        return length;
    }
}
