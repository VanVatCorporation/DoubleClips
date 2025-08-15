package com.vanvatcorporation.doubleclips.utils;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

public class TimelineUtils {
    public static int findVideoTrackIndex(MediaExtractor extractor) throws Exception {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                return i;
            }
            if (mime.startsWith("image/")) {
                return i;
            }
            if (mime.startsWith("audio/")) {
                return i;
            }
        }
        throw new Exception("No video track found!");
    }
}
