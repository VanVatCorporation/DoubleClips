package com.vanvatcorporation.doubleclips.constants;

import android.content.Context;

import com.vanvatcorporation.doubleclips.helper.IOHelper;

public class Constants {
    public static final String DEFAULT_PROJECT_PROPERTIES_FILENAME = "project.properties";
    public static final String DEFAULT_TIMELINE_FILENAME = "project.timeline";
    public static final String DEFAULT_PREVIEW_CLIP_FILENAME = "preview.mp4";
    public static final String DEFAULT_EXPORT_CLIP_FILENAME = "export.mp4";
    public static final String DEFAULT_LOGGING_DIRECTORY = "Logging";
    public static final String DEFAULT_CLIP_DIRECTORY = "Clips";
    public static final String DEFAULT_CLIP_TEMP_DIRECTORY = "Clips/Temp";
    public static final int SAMPLE_SIZE_PREVIEW_CLIP = 16;
    public static final int DEFAULT_DEBUG_LOGGING_SIZE = 1048576;
    public static float TRACK_CLIPS_SNAP_THRESHOLD_PIXEL = 30f; // pixels;
    public static float TRACK_CLIPS_SNAP_THRESHOLD_SECONDS = 0.3f; // seconds;

    public static String DEFAULT_PROJECT_DIRECTORY(Context context) {
        return IOHelper.CombinePath(IOHelper.getPersistentDataPath(context), "projects");
    }
}
