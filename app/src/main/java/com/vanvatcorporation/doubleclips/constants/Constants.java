package com.vanvatcorporation.doubleclips.constants;

import android.content.Context;

import com.vanvatcorporation.doubleclips.helper.IOHelper;

public class Constants {
    public static final String DEFAULT_PROJECT_PROPERTIES_FILENAME = "project.properties";
    public static final String DEFAULT_TIMELINE_FILENAME = "project.timeline";
    public static final String DEFAULT_VIDEO_SETTINGS_FILENAME = "project.settings";
    public static final String DEFAULT_PREVIEW_CLIP_FILENAME = "preview.mp4";
    public static final String DEFAULT_EXPORT_CLIP_FILENAME = "export.mp4";
    public static final String DEFAULT_LOGGING_DIRECTORY = "Logging";
    public static final String DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY = "TemplatesClipTemp";
    public static final String DEFAULT_CLIP_DIRECTORY = "Clips";
    public static final String DEFAULT_PREVIEW_CLIP_DIRECTORY = "PreviewClips";
    public static final String DEFAULT_CUTOUT_DIRECTORY = "Cutouts";
    public static final String DEFAULT_CLIP_TEMP_DIRECTORY = "Clips/Temp";
    public static final String DEFAULT_MULTI_FFMPEG_COMMAND_REGEX = "<Ffmpeg Command Splitter hehe lmao skibidi tung tung tung sahur>";
    public static final int SAMPLE_SIZE_PREVIEW_CLIP = 16;
    public static final int DEFAULT_LOGGING_LIMIT_CHARACTERS = 10000;
    public static final int DEFAULT_DEBUG_LOGGING_SIZE = 1048576;
    public static final float CANVAS_ROTATE_SNAP_THRESHOLD_DEGREE = 3f; // degrees
    public static final float CANVAS_ROTATE_SNAP_DEGREE = 90f;
    public static float TRACK_CLIPS_SNAP_THRESHOLD_PIXEL = 30f; // pixels;
    public static float TRACK_CLIPS_SNAP_THRESHOLD_SECONDS = 0.1f; // seconds;
    public static float TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS = 0.01f; // seconds;
    public static float TRACK_CLIPS_SHRINK_LIMIT_PIXEL = 20f; // pixels;



    public static String DEFAULT_TEMPLATE_CLIP_EXPORT_MARK = "<output.mp4>";
    public static String DEFAULT_TEMPLATE_CLIP_SCALE_WIDTH_MARK = "<scale-width>";
    public static String DEFAULT_TEMPLATE_CLIP_SCALE_HEIGHT_MARK = "<scale-height>";
    public static String DEFAULT_TEMPLATE_CLIP_STATIC_MARK(String resourceName) {
        return "<static-" + resourceName + ">";
    }

    public static String DEFAULT_TEMPLATE_CLIP_MARK(int index) {
        return "<editable-video-" + index + ">";
    }
    public static String DEFAULT_TEMPLATE_TRIM_MARK(int index) {
        return "<editable-video-trim-" + index + ">";
    }

    public static String DEFAULT_PROJECT_DIRECTORY(Context context) {
        return IOHelper.CombinePath(IOHelper.getPersistentDataPath(context), "projects");
    }
}
