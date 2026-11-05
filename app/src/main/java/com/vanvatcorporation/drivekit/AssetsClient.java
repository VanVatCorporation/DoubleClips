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

    private static String safeSegment(String id) {
        return id == null ? "asset" : id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
