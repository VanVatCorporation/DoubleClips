package com.vanvatcorporation.drivekit;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/** An asset of mine: the public fields plus the state of the link behind it (GET /assets/mine). */
public final class MyAsset {
    /** Listed in the Assets tab: an active "anyone with the link" share covers the file. */
    public static final String STATE_PUBLIC = "public";
    /** No active link: not listed. Restricting the file in Drive or OpenEditz moves it here by itself. */
    public static final String STATE_RESTRICTED = "restricted";
    /** Hidden after reports until it is reviewed. */
    public static final String STATE_REVIEW = "review";
    /** The file is in the Drive trash. */
    public static final String STATE_DELETED = "deleted";

    public final String id;
    public final String nodeId;
    public final AssetKind kind;
    public final String title;
    public final String description;
    public final List<String> tags;
    public final String fileName;
    public final long size;
    public final String previewUrl;
    public final long downloads;
    public final int reportCount;
    /** Epoch milliseconds (0 when the server sent no date). */
    public final long createdAt;
    /** One of the STATE_ values (an unknown word counts as deleted, the same fallback as iOS and desktop). */
    public final String state;
    /** "link" (the file's own link) or "folder" (a link on a folder above it); null when not public. */
    public final String via;
    /** The folder whose link covers the file when {@link #via} is "folder". */
    public final String folderName;

    private MyAsset(DriveClient drive, JsonObject o) {
        id = DriveJson.str(o, "id");
        nodeId = DriveJson.str(o, "nodeId");
        kind = AssetKind.ofApiName(DriveJson.str(o, "kind"));
        String t = DriveJson.str(o, "title");
        title = t == null || t.trim().isEmpty() ? "Untitled" : t.trim();
        description = DriveJson.str(o, "description");
        tags = Collections.unmodifiableList(DriveJson.strings(o, "tags"));
        fileName = DriveJson.str(o, "fileName");
        size = DriveJson.lng(o, "size", 0);
        previewUrl = drive.absoluteUrl(DriveJson.str(o, "previewUrl"));
        downloads = DriveJson.lng(o, "downloads", 0);
        reportCount = (int) DriveJson.lng(o, "reportCount", 0);
        createdAt = Models.parseDate(DriveJson.str(o, "createdAt"));
        String s = DriveJson.str(o, "state");
        state = STATE_PUBLIC.equals(s) || STATE_RESTRICTED.equals(s) || STATE_REVIEW.equals(s) ? s : STATE_DELETED;
        via = DriveJson.str(o, "via");
        folderName = DriveJson.str(o, "folderName");
    }

    static MyAsset fromJson(DriveClient drive, JsonObject o) throws DriveException {
        MyAsset a = new MyAsset(drive, o);
        if (a.id == null || a.id.isEmpty()) throw new DriveException(0, "bad_answer", "The server's answer has no asset id.");
        return a;
    }

    /** The switch in My assets can flip the link only while the asset is listed or restricted. */
    public boolean canToggleLink() { return STATE_PUBLIC.equals(state) || STATE_RESTRICTED.equals(state); }

    @Override public boolean equals(Object other) { return other instanceof MyAsset && ((MyAsset) other).id.equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return title; }
}
