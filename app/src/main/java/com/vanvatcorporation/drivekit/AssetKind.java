package com.vanvatcorporation.drivekit;

import java.util.Locale;

/** The six kinds of community asset (iOS: AssetKind). The server decides what fits a kind by the file extension. */
public enum AssetKind {
    VIDEO("video", "Videos", "Video", "mp4", "mov", "m4v", "webm"),
    IMAGE("image", "Images", "Image", "jpg", "jpeg", "png", "webp", "heic", "gif"),
    AUDIO("audio", "Audio", "Audio", "mp3", "m4a", "aac", "wav", "ogg", "flac"),
    FONT("font", "Fonts", "Font", "ttf", "otf", "ttc", "otc"),
    EFFECT("effect", "Effects", "Effect", "json"),
    PACK("pack", "Packs", "Animation pack", "zip");

    /** The name the API uses (?kind=video). */
    public final String apiName;
    /** The filter chip's text (English; the screens look it up through {@link com.vanvatcorporation.doubleclips.assets.AssetUi#chipTitle}). */
    public final String chipTitle;
    /** One asset of this kind (English). */
    public final String singular;
    private final String[] extensions;

    AssetKind(String apiName, String chipTitle, String singular, String... extensions) {
        this.apiName = apiName;
        this.chipTitle = chipTitle;
        this.singular = singular;
        this.extensions = extensions;
    }

    public boolean accepts(String fileName) {
        String ext = extensionOf(fileName);
        for (String e : extensions) if (e.equals(ext)) return true;
        return false;
    }

    /** Effects and animation packs are installed in the app; everything else is a file for a project. */
    public boolean isInstalledInApp() { return this == EFFECT || this == PACK; }

    /** The kind the server named; unknown names count as a pack (the same fallback as iOS and desktop). */
    public static AssetKind ofApiName(String name) {
        if (name != null) {
            for (AssetKind k : values()) if (k.apiName.equalsIgnoreCase(name.trim())) return k;
        }
        return PACK;
    }

    /** The kind a file of this name would be posted as, or null. */
    public static AssetKind detect(String fileName) {
        for (AssetKind k : values()) if (k.accepts(fileName)) return k;
        return null;
    }

    static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
