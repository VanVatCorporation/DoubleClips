package com.vanvatcorporation.doubleclips.utils;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

public class TimelineUtils {
    public static int findMediaTrackIndex(MediaExtractor extractor, EditingActivity.ClipType clipType) throws Exception {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/") && clipType == EditingActivity.ClipType.VIDEO) {
                return i;
            }
            if (mime.startsWith("image/") && clipType == EditingActivity.ClipType.IMAGE) {
                return i;
            }
            if (mime.startsWith("audio/") && clipType == EditingActivity.ClipType.AUDIO) {
                return i;
            }
        }
        throw new Exception("No audio track found!");
    }
}
