package com.vanvatcorporation.drivekit;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/** One asset in the public catalog: { id, kind, title, description, tags[], ownerName, fileName, size, mimeType, previewUrl, downloads, createdAt }. */
public final class CommunityAsset {
    public final String id;
    public final AssetKind kind;
    public final String title;
    public final String description;
    public final List<String> tags;
    public final String ownerName;
    public final String fileName;
    public final long size;
    public final String mimeType;
    /** An absolute link to the poster's small JPEG, or null. */
    public final String previewUrl;
    public final long downloads;
    /** Epoch milliseconds (0 when the server sent no date). */
    public final long createdAt;

    private CommunityAsset(DriveClient drive, JsonObject o) {
        id = DriveJson.str(o, "id");
        kind = AssetKind.ofApiName(DriveJson.str(o, "kind"));
        String t = DriveJson.str(o, "title");
        title = t == null || t.trim().isEmpty() ? "Untitled" : t.trim();
        description = DriveJson.str(o, "description");
        tags = Collections.unmodifiableList(DriveJson.strings(o, "tags"));
        ownerName = DriveJson.str(o, "ownerName");
        fileName = DriveJson.str(o, "fileName");
        size = DriveJson.lng(o, "size", 0);
        mimeType = DriveJson.str(o, "mimeType");
        previewUrl = drive.absoluteUrl(DriveJson.str(o, "previewUrl"));
        downloads = DriveJson.lng(o, "downloads", 0);
        createdAt = Models.parseDate(DriveJson.str(o, "createdAt"));
    }

    static CommunityAsset fromJson(DriveClient drive, JsonObject o) throws DriveException {
        CommunityAsset a = new CommunityAsset(drive, o);
        if (a.id == null || a.id.isEmpty()) throw new DriveException(0, "bad_answer", "The server's answer has no asset id.");
        return a;
    }

    /**
     * The asset as JSON, for a screen that receives it in a Bundle (the detail sheet). Same field names as the API, so
     * {@link #fromStoredJson} can read it back.
     */
    public String toStoredJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", kind.apiName);
        o.addProperty("title", title);
        o.addProperty("description", description);
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (String tag : tags) array.add(tag);
        o.add("tags", array);
        o.addProperty("ownerName", ownerName);
        o.addProperty("fileName", fileName);
        o.addProperty("size", size);
        o.addProperty("mimeType", mimeType);
        o.addProperty("previewUrl", previewUrl);
        o.addProperty("downloads", downloads);
        return o.toString();
    }

    /** Reads what {@link #toStoredJson} wrote; null when it is not an asset. */
    public static CommunityAsset fromStoredJson(DriveClient drive, String json) {
        try {
            JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            return fromJson(drive, o);
        } catch (RuntimeException | DriveException e) {
            return null;
        }
    }

    @Override public boolean equals(Object other) { return other instanceof CommunityAsset && ((CommunityAsset) other).id.equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return title; }
}
