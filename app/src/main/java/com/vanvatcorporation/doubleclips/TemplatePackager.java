package com.vanvatcorporation.doubleclips;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Packages a project as a TIMELINE template for posting (iOS: TemplatePackager in TemplatePosting.swift).
 * <p>
 * What goes up: the timeline wrapped as {"format":1,"settings":{canvas},"timeline":{...}} and the template's resources,
 * i.e. the media of LOCKED clips and of audio clips, plus project fonts used by text clips (flat, server-safe names).
 * The media of REPLACEABLE clips stays on the device. When a file had to be renamed for the upload, the timeline is
 * pointed at the new name (on a copy: the project's own timeline is never touched).
 * <p>
 * It works on the timeline's JSON (the project.timeline file) so everything the project holds, including keys another
 * platform wrote, is posted as it is. Roles follow {@code TemplateTimelineInfo.roleOf}: a VIDEO or IMAGE clip is LOCKED
 * when {@code isLockedForTemplate}, else REPLACEABLE. Plain Java.
 */
public final class TemplatePackager {

    private TemplatePackager() { }

    /** One file of the upload: where it is on disk and the name it is posted under. */
    public static final class PostFile {
        public final File file;
        public final String name;

        public PostFile(File file, String name) {
            this.file = file;
            this.name = name;
        }

        public String mimeType() {
            return mimeTypeOf(name);
        }
    }

    public static final class Draft {
        /** What is posted as templateTimelineJson (the wrapper). */
        public final String timelineJson;
        /** The template's resources, under their posted names. */
        public final List<PostFile> contentFiles;
        /** Things the author should know: a missing file that was left out, a file that had to be renamed. */
        public final List<String> warnings;
        /** Clips the user replaces when using the template (the slots). */
        public final int replaceableCount;
        public final int lockedCount;

        Draft(String timelineJson, List<PostFile> contentFiles, List<String> warnings, int replaceableCount, int lockedCount) {
            this.timelineJson = timelineJson;
            this.contentFiles = contentFiles;
            this.warnings = warnings;
            this.replaceableCount = replaceableCount;
            this.lockedCount = lockedCount;
        }
    }

    public static final class PackageException extends Exception {
        public PackageException(String message) {
            super(message);
        }
    }

    /**
     * @param projectPath    the project folder (fonts are "Fonts/..." relative to it)
     * @param clipsDirectory the folder holding the clips' media files (named by clipName)
     * @param timelineJson   the project's timeline JSON ({"tracks":[...]})
     */
    public static Draft make(String projectPath, String clipsDirectory, String timelineJson,
                             int videoWidth, int videoHeight, int frameRate, boolean isStretchToFull) throws PackageException {
        JsonObject timeline;
        try {
            JsonElement root = JsonParser.parseString(timelineJson == null ? "" : timelineJson);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("tracks") || !root.getAsJsonObject().get("tracks").isJsonArray()) {
                throw new PackageException("The timeline is empty.");
            }
            timeline = root.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new PackageException("The timeline couldn't be read.");
        }

        List<JsonObject> clips = clipsOf(timeline);
        int replaceable = 0, locked = 0;
        for (JsonObject clip : clips) {
            String type = stringOf(clip, "type");
            if (isPicture(type)) {
                if (boolOf(clip, "isLockedForTemplate")) locked++;
                else replaceable++;
            }
        }
        if (replaceable == 0) {
            throw new PackageException("Nothing in this project can be replaced. Turn off \"Lock media for template\" on at least one video or image clip.");
        }

        List<PostFile> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> usedNames = TemplateTimelineJson.newNameSet();
        Map<String, String> postedBySource = new HashMap<>();   // absolute source path -> posted name (or "" for a missing file)
        Map<String, String> renamedClips = new HashMap<>();     // clipName -> posted name
        Map<String, String> renamedFonts = new HashMap<>();     // "Fonts/X.ttf" -> "Fonts/Y.ttf"

        for (JsonObject clip : clips) {
            String type = stringOf(clip, "type");
            String clipName = stringOf(clip, "clipName");
            boolean resource = "AUDIO".equals(type) || (isPicture(type) && boolOf(clip, "isLockedForTemplate"));
            if (resource && !clipName.isEmpty()) {
                String posted = add(new File(clipsDirectory, clipName), clipName, files, warnings, usedNames, postedBySource);
                if (posted != null && !posted.equals(clipName)) renamedClips.put(clipName, posted);
            } else if ("TEXT".equals(type)) {
                String font = fontOf(clip);
                if (!font.isEmpty()) {
                    String original = new File(font).getName();
                    String posted = add(new File(projectPath, font), original, files, warnings, usedNames, postedBySource);
                    if (posted != null && !posted.equals(original)) {
                        int slash = font.lastIndexOf('/');
                        renamedFonts.put(font, (slash >= 0 ? font.substring(0, slash + 1) : "") + posted);
                    }
                }
            }
        }

        // The timeline that goes up, pointed at the posted names (a copy: the project's own file isn't touched).
        JsonObject exported = timeline;
        if (!renamedClips.isEmpty() || !renamedFonts.isEmpty()) {
            exported = timeline.deepCopy();
            for (JsonObject clip : clipsOf(exported)) {
                String type = stringOf(clip, "type");
                String clipName = stringOf(clip, "clipName");
                if (!"TEXT".equals(type) && renamedClips.containsKey(clipName)) clip.addProperty("clipName", renamedClips.get(clipName));
                JsonElement style = clip.get("textStyle");
                if (style != null && style.isJsonObject()) {
                    JsonObject s = style.getAsJsonObject();
                    for (String key : new String[]{"fontFile", "fontPath"}) {
                        String value = stringOf(s, key);
                        if (renamedFonts.containsKey(value)) s.addProperty(key, renamedFonts.get(value));
                    }
                }
            }
        }
        String wrapped = TemplateTimelineJson.wrap(exported.toString(), videoWidth, videoHeight, frameRate, isStretchToFull);
        return new Draft(wrapped, files, warnings, replaceable, locked);
    }

    /** Adds a resource under a server-safe, unique name; returns that name (the existing one for a file added before), or null when the file is missing. */
    private static String add(File source, String originalName, List<PostFile> files, List<String> warnings,
                              Set<String> usedNames, Map<String, String> postedBySource) {
        String key = source.getAbsolutePath();
        if (postedBySource.containsKey(key)) {
            String posted = postedBySource.get(key);
            return posted.isEmpty() ? null : posted;
        }
        if (!source.isFile()) {
            warnings.add(originalName + " is missing, so it was left out.");
            postedBySource.put(key, "");
            return null;
        }
        String posted = TemplateTimelineJson.uniqueSafeName(originalName, usedNames);
        if (!posted.equals(originalName)) warnings.add(originalName + " was renamed to " + posted + " for the upload.");
        files.add(new PostFile(source, posted));
        postedBySource.put(key, posted);
        return posted;
    }

    // ---- JSON helpers ------------------------------------------------------------------------------

    private static boolean isPicture(String type) {
        return "VIDEO".equals(type) || "IMAGE".equals(type);
    }

    private static List<JsonObject> clipsOf(JsonObject timeline) {
        List<JsonObject> clips = new ArrayList<>();
        for (JsonElement t : timeline.getAsJsonArray("tracks")) {
            if (!t.isJsonObject() || !t.getAsJsonObject().has("clips") || !t.getAsJsonObject().get("clips").isJsonArray()) continue;
            JsonArray list = t.getAsJsonObject().getAsJsonArray("clips");
            for (JsonElement c : list) if (c.isJsonObject()) clips.add(c.getAsJsonObject());
        }
        return clips;
    }

    private static String stringOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean boolOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** A text clip's project font: iOS's "fontFile", or this build's "fontPath" when that is project-relative. */
    private static String fontOf(JsonObject clip) {
        JsonElement style = clip.get("textStyle");
        if (style == null || !style.isJsonObject()) return "";
        JsonObject s = style.getAsJsonObject();
        String file = stringOf(s, "fontFile");
        if (!file.isEmpty()) return file;
        String path = stringOf(s, "fontPath");
        return !path.isEmpty() && !new File(path).isAbsolute() ? path : "";
    }

    /** The MIME type a posted file is sent with, by its extension. */
    public static String mimeTypeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        switch (dot < 0 ? "" : n.substring(dot + 1)) {
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "m4a": return "audio/mp4";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "aac": return "audio/aac";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "ttc": case "otc": return "font/collection";
            default: return "application/octet-stream";
        }
    }
}
