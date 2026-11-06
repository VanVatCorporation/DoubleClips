package com.vanvatcorporation.drivekit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Assets API (docs/vanvat-drive-assets-api.md; desktop and iOS: AssetsClient): community assets on top of Van Vat Drive.
 * Browsing is public (no login). Downloading and reporting need the account; a download is charged to the DOWNLOADER's
 * daily allowance, never the poster's. Every call blocks: run it on a worker thread.
 */
public final class AssetsClient {
    public static final String SORT_NEW = "new";
    public static final String SORT_POPULAR = "popular";
    public static final int PAGE_SIZE = 24;

    /** The reasons the server accepts for a report. */
    public static final String[] REPORT_REASONS = { "spam", "copyright", "inappropriate", "malware", "other" };

    /** One page of the catalog; {@code next} is the cursor for the page after it, or null at the end. */
    public static final class Page {
        public final List<CommunityAsset> items;
        public final String next;
        Page(List<CommunityAsset> items, String next) { this.items = items; this.next = next; }
    }

    private final DriveClient drive;
    private final File cacheRoot;

    /** @param cacheRoot where downloaded assets are kept (Cache/Assets/{id}/{file name} inside it) */
    public AssetsClient(DriveClient drive, File cacheRoot) {
        this.drive = drive;
        this.cacheRoot = cacheRoot;
    }

    public DriveClient drive() { return drive; }

    // ---- browse (public) ---------------------------------------------------------------------------------

    /** What is shown now: {@code kind} null = every kind, {@code query} matches title, tags and description. */
    public Page list(AssetKind kind, String query, String sort, String cursor) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        if (kind != null) q.put("kind", kind.apiName);
        String text = query == null ? "" : query.trim();
        if (!text.isEmpty()) q.put("q", text);
        q.put("sort", SORT_POPULAR.equals(sort) ? SORT_POPULAR : SORT_NEW);
        if (cursor != null && !cursor.isEmpty()) q.put("cursor", cursor);
        q.put("limit", String.valueOf(PAGE_SIZE));
        JsonObject o = drive.json("GET", "/public/assets", q, null, false);

        List<CommunityAsset> items = new ArrayList<>();
        JsonElement array = o.get("items");
        if (array != null && array.isJsonArray()) {
            for (JsonElement e : (JsonArray) array) {
                if (!e.isJsonObject()) continue;
                try {
                    items.add(CommunityAsset.fromJson(drive, e.getAsJsonObject()));
                } catch (DriveException bad) {
                    // one unreadable row must not hide the other 23
                }
            }
        }
        String next = DriveJson.str(o, "nextCursor");
        return new Page(Collections.unmodifiableList(items), next == null || next.isEmpty() ? null : next);
    }

    // ---- get (signed in) ---------------------------------------------------------------------------------

    /** Where a downloaded asset is kept: Assets/{id}/{file name} inside the cache folder. */
    public File cacheFileOf(CommunityAsset asset) {
        String name = asset.fileName != null && !asset.fileName.trim().isEmpty() ? asset.fileName : asset.title;
        File dir = new File(new File(cacheRoot, "Assets"), safeSegment(asset.id));
        return new File(dir, DriveClient.safeName(name));
    }

    /**
     * Downloads the file (signed in; counted against the downloader's daily allowance: 429 when it is used up) and returns it.
     * A copy already in the cache is reused, so getting the same asset twice costs nothing the second time.
     */
    public File download(CommunityAsset asset, DriveClient.Progress progress, AtomicBoolean cancel) throws IOException {
        File dest = cacheFileOf(asset);
        if (dest.isFile() && dest.length() > 0 && (asset.size <= 0 || dest.length() == asset.size)) return dest;
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new DriveException("Couldn't create the download folder.", null);
        drive.downloadPath("/assets/" + asset.id + "/content", dest, progress, cancel);
        return dest;
    }

    // ---- report (signed in) ------------------------------------------------------------------------------

    /** Reports an asset (idempotent; not for your own). 3 different reporters hide it until it is reviewed. */
    public void report(CommunityAsset asset, String reason, String note) throws IOException {
        StringBuilder body = new StringBuilder("{\"reason\":").append(DriveJson.quote(reason));
        if (note != null && !note.trim().isEmpty()) body.append(",\"note\":").append(DriveJson.quote(note.trim()));
        body.append('}');
        drive.json("POST", "/assets/" + asset.id + "/report", null, body.toString(), true);
    }

    // ---- mine (signed in) --------------------------------------------------------------------------------

    /** Everything I posted, with the state of the link behind each (newest first, as the server sends it). */
    public List<MyAsset> mine() throws IOException {
        JsonObject o = drive.json("GET", "/assets/mine", null, null, true);
        List<MyAsset> items = new ArrayList<>();
        JsonElement array = o.get("items");
        if (array != null && array.isJsonArray()) {
            for (JsonElement e : array.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                try {
                    items.add(MyAsset.fromJson(drive, e.getAsJsonObject()));
                } catch (DriveException bad) {
                    // one unreadable row must not hide the others
                }
            }
        }
        return items;
    }

    /**
     * Posts one of my files (inside OpenEditz). The server shares it as "anyone with the link" (view only) when no link covers it
     * yet. 409 {@code already_posted} / {@code asset_limit} (200 per person); the file extension must fit the kind.
     */
    public MyAsset publish(Models.Node node, AssetKind kind, String title, String description, List<String> tags) throws IOException {
        StringBuilder body = new StringBuilder("{\"nodeId\":").append(DriveJson.quote(node.id))
                .append(",\"kind\":").append(DriveJson.quote(kind.apiName))
                .append(",\"title\":").append(DriveJson.quote(title))
                .append(",\"description\":").append(DriveJson.quote(description == null ? "" : description))
                .append(",\"tags\":").append(tagsJson(tags))
                .append(",\"rights\":true}");
        return mineShape(drive.json("POST", "/assets", null, body.toString(), true));
    }

    /** Edits the title, description and tags. */
    public MyAsset update(MyAsset asset, String title, String description, List<String> tags) throws IOException {
        String body = "{\"title\":" + DriveJson.quote(title)
                + ",\"description\":" + DriveJson.quote(description == null ? "" : description)
                + ",\"tags\":" + tagsJson(tags) + "}";
        return mineShape(drive.json("PATCH", "/assets/" + asset.id, null, body, true));
    }

    /** The small picture that stands for the asset in the tab: a JPEG of at most 200 KB. */
    public void uploadPreview(String assetId, byte[] jpeg) throws IOException {
        drive.putBinary("/assets/" + assetId + "/preview", jpeg, "image/jpeg");
    }

    /** Takes it off the tab; the file stays in Drive. {@code revokeLink} also removes the file's own link. */
    public void remove(MyAsset asset, boolean revokeLink) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        if (revokeLink) q.put("revokeLink", "1");
        drive.json("DELETE", "/assets/" + asset.id, q, null, true);
    }

    /** "Restricted": revokes the file's own link. 409 {@code inherited_link} when a folder's link still covers it (the message names the folder). */
    public MyAsset restrict(MyAsset asset) throws IOException {
        return mineShape(drive.json("POST", "/assets/" + asset.id + "/restrict", null, null, true));
    }

    /** Back to "anyone with the link" (creates the link if needed). */
    public MyAsset share(MyAsset asset) throws IOException {
        return mineShape(drive.json("POST", "/assets/" + asset.id + "/share", null, null, true));
    }

    private MyAsset mineShape(JsonObject answer) throws DriveException {
        JsonObject o = DriveJson.obj(answer, "asset");
        if (o == null) throw new DriveException(0, "bad_answer", "The server's answer is not what was expected.");
        return MyAsset.fromJson(drive, o);
    }

    private static String tagsJson(List<String> tags) {
        StringBuilder b = new StringBuilder("[");
        if (tags != null) {
            boolean first = true;
            for (String tag : tags) {
                if (tag == null || tag.trim().isEmpty()) continue;
                if (!first) b.append(',');
                b.append(DriveJson.quote(tag.trim()));
                first = false;
            }
        }
        return b.append(']').toString();
    }

    /** "beat, smooth, 4k" (commas or line breaks) to a clean list. */
    public static List<String> splitTags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,\\n]")) {
            String t = part.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String safeSegment(String id) {
        return id == null ? "asset" : id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
