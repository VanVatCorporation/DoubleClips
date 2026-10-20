package com.vanvatcorporation.drivekit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Blocking client for the Van Vat Drive API. Call it from a worker thread.
 * Auth is `Authorization: Bearer <jwt>` (no cookies), the server renews the JWT through X-Refreshed-Token.
 */
public class DriveClient {
    public interface Auth {
        String token();
        void onRefreshed(String token);
        void onUnauthorized();
    }

    public interface Progress { void onProgress(long done, long total); }

    public static class ChunkResult {
        public final boolean mismatch;
        public final long offset;
        ChunkResult(boolean mismatch, long offset) { this.mismatch = mismatch; this.offset = offset; }
    }

    public static final String DEFAULT_BASE = "https://drive.vanvatcorp.com";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType BINARY = MediaType.parse("application/octet-stream");

    private final OkHttpClient http;
    private final String base;
    private final Auth auth;
    private final Gson gson = new Gson();

    public DriveClient(String base, Auth auth) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.auth = auth;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private Request.Builder req(String path, boolean authed) {
        Request.Builder b = new Request.Builder().url(base + "/api/v2/drive" + path);
        if (authed) {
            String h = authHeader();
            if (h != null) b.header("Authorization", h);
        }
        return b;
    }

    public String authHeader() {
        String t = auth == null ? null : auth.token();
        return t == null || t.isEmpty() ? null : "Bearer " + t;
    }

    private RequestBody json(Object body) {
        return RequestBody.create(body == null ? "{}" : gson.toJson(body), JSON);
    }

    private DriveException error(int status, String body) {
        String message = "Request failed (" + status + ")";
        String code = null;
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("message") && !o.get("message").isJsonNull()) message = o.get("message").getAsString();
            if (o.has("code") && !o.get("code").isJsonNull()) code = o.get("code").getAsString();
        } catch (Exception ignored) { /* not JSON */ }
        if (status == 401 && auth != null) auth.onUnauthorized();
        return new DriveException(status, code, message);
    }

    private String exec(Request.Builder rb) throws DriveException {
        try (Response r = http.newCall(rb.build()).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            String fresh = r.header("X-Refreshed-Token");
            if (fresh != null && !fresh.isEmpty() && auth != null) auth.onRefreshed(fresh);
            if (!r.isSuccessful()) throw error(r.code(), body);
            return body;
        } catch (DriveException e) {
            throw e;
        } catch (IOException e) {
            throw new DriveException("Network error: " + e.getMessage(), e);
        }
    }

    private String get(String path) throws DriveException { return exec(req(path, true).get()); }
    private String send(String method, String path, Object body) throws DriveException {
        return exec(req(path, true).method(method, json(body)));
    }

    private JsonObject obj(String body) { return JsonParser.parseString(body).getAsJsonObject(); }
    private Models.Node node(String body) { return gson.fromJson(obj(body).get("node"), Models.Node.class); }
    private <T> List<T> items(String body, Type listType) {
        JsonElement e = obj(body).get("items");
        return gson.fromJson(e, listType);
    }
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── account ─────────────────────────────────────────────────────────────

    public Models.Me me() throws DriveException { return gson.fromJson(get("/me"), Models.Me.class); }

    /** The protected OpenEditz folder at the top of the user's space (created on first call). */
    public Models.Node ensureOpenEditz() throws DriveException { return node(send("POST", "/system/openeditz", null)); }

    // ── browse ──────────────────────────────────────────────────────────────

    /** parentId null = my root (things I own). */
    public Models.Listing list(String parentId) throws DriveException {
        return gson.fromJson(get("/nodes?limit=500" + (parentId == null ? "" : "&parentId=" + parentId)), Models.Listing.class);
    }

    public Models.Node nodeById(String id) throws DriveException { return node(get("/nodes/" + id)); }

    public List<Models.SharedItem> shared() throws DriveException {
        Type t = new TypeToken<List<Models.SharedItem>>() {}.getType();
        return items(get("/shared"), t);
    }

    public List<Models.Node> trash() throws DriveException {
        Type t = new TypeToken<List<Models.Node>>() {}.getType();
        return items(get("/trash"), t);
    }

    // ── change ──────────────────────────────────────────────────────────────

    public Models.Node createFolder(String parentId, String name) throws DriveException {
        return node(send("POST", "/folders", map("parentId", parentId, "name", name)));
    }

    public Models.Node rename(String id, String name) throws DriveException {
        return node(send("PATCH", "/nodes/" + id, map("name", name)));
    }

    /** destinationId null = the root (owner only). */
    public Models.Node move(String id, String destinationId) throws DriveException {
        return node(send("PATCH", "/nodes/" + id, destinationId == null ? map("moveToRoot", true) : map("parentId", destinationId)));
    }

    public void trash(String id) throws DriveException { send("DELETE", "/nodes/" + id, null); }
    public void restore(String id) throws DriveException { send("POST", "/nodes/" + id + "/restore", null); }
    public void purge(String id) throws DriveException { send("DELETE", "/trash/" + id, null); }
    public void emptyTrash() throws DriveException { send("DELETE", "/trash", null); }

    // ── sharing ─────────────────────────────────────────────────────────────

    public Models.AccessInfo access(String nodeId) throws DriveException {
        return gson.fromJson(get("/nodes/" + nodeId + "/access"), Models.AccessInfo.class);
    }

    /** identifier = email or username of a Van Vat account. */
    public void grant(String nodeId, String identifier, String role) throws DriveException {
        send("POST", "/nodes/" + nodeId + "/permissions", map("identifier", identifier, "role", role));
    }

    public void changeRole(String permissionId, String role) throws DriveException {
        send("PATCH", "/permissions/" + permissionId, map("role", role));
    }

    /** Also how someone removes a folder from their own "Shared with me". */
    public void removeAccess(String permissionId) throws DriveException { send("DELETE", "/permissions/" + permissionId, null); }

    public Models.LinkInfo createLink(String nodeId, Integer expiresInHours, boolean rawServing) throws DriveException {
        String body = send("POST", "/nodes/" + nodeId + "/shares", map("expiresInHours", expiresInHours, "rawServing", rawServing));
        return gson.fromJson(obj(body).get("share"), Models.LinkInfo.class);
    }

    public Models.LinkInfo setRawServing(String linkId, boolean on) throws DriveException {
        return gson.fromJson(obj(send("PATCH", "/shares/" + linkId, map("rawServing", on))).get("share"), Models.LinkInfo.class);
    }

    public void revokeLink(String linkId) throws DriveException { send("DELETE", "/shares/" + linkId, null); }

    // ── downloads ───────────────────────────────────────────────────────────

    public String contentUrl(String id) { return base + "/api/v2/drive/files/" + id + "/content"; }

    /** ZIP of files/folders, built on the fly by the server. Send the Authorization header (DownloadManager can). */
    public String zipUrl(List<String> ids) { return base + "/api/v2/drive/download/zip?ids=" + join(ids); }

    public Models.ZipPlan zipPlan(List<String> ids) throws DriveException {
        return gson.fromJson(get("/download/zip/plan?ids=" + join(ids)), Models.ZipPlan.class);
    }

    public String publicZipUrl(String token) { return base + "/api/v2/drive/public/s/" + token + "/zip"; }
    public String publicContentUrl(String token, String id) { return base + "/api/v2/drive/public/s/" + token + "/files/" + id + "/content"; }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) { if (sb.length() > 0) sb.append(','); sb.append(id); }
        return sb.toString();
    }

    /** Downloads a file into dir/<name> (extension kept, so a viewer can open it) and returns it. */
    public File download(Models.Node node, File dir, Progress progress) throws DriveException {
        return downloadTo(req("/files/" + node.id + "/content", true), new File(dir, safeName(node.name)), progress);
    }

    public File publicDownload(String token, Models.PublicNode node, File dir, Progress progress) throws DriveException {
        return downloadTo(req("/public/s/" + token + "/files/" + node.id + "/content", false), new File(dir, safeName(node.name)), progress);
    }

    private File downloadTo(Request.Builder rb, File dest, Progress progress) throws DriveException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new DriveException("Couldn't create the download folder.", null);
        File tmp = new File(dest.getPath() + ".part");
        try (Response r = http.newCall(rb.build()).execute()) {
            String fresh = r.header("X-Refreshed-Token");
            if (fresh != null && !fresh.isEmpty() && auth != null) auth.onRefreshed(fresh);
            if (!r.isSuccessful()) throw error(r.code(), r.body() == null ? "" : r.body().string());
            long total = r.body() == null ? -1 : r.body().contentLength();
            try (InputStream in = r.body().byteStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total);
                }
            }
            if (dest.exists() && !dest.delete()) throw new DriveException("Couldn't replace the old copy.", null);
            if (!tmp.renameTo(dest)) throw new DriveException("Couldn't save the file.", null);
            return dest;
        } catch (DriveException e) {
            tmp.delete();
            throw e;
        } catch (IOException e) {
            tmp.delete();
            throw new DriveException("Network error: " + e.getMessage(), e);
        }
    }

    static String safeName(String name) {
        String s = name == null ? "file" : name.replace('/', '_').replace('\\', '_').trim();
        return s.isEmpty() ? "file" : s;
    }

    // ── public links (no login) ─────────────────────────────────────────────

    public Models.PublicMeta publicMeta(String token) throws DriveException {
        return gson.fromJson(exec(req("/public/s/" + token, false).get()), Models.PublicMeta.class);
    }

    public Models.PublicListing publicList(String token, String parentId) throws DriveException {
        return gson.fromJson(exec(req("/public/s/" + token + "/nodes" + (parentId == null ? "" : "?parentId=" + parentId), false).get()), Models.PublicListing.class);
    }

    // ── resumable upload primitives (used by ResumableUpload) ───────────────

    Models.UploadSession createUpload(String parentId, String name, long size, String mime) throws DriveException {
        return gson.fromJson(send("POST", "/uploads", map("parentId", parentId, "name", name, "size", size, "mimeType", mime)), Models.UploadSession.class);
    }

    long uploadReceived(String uploadId) throws DriveException {
        return obj(get("/uploads/" + uploadId)).get("received").getAsLong();
    }

    Models.Node completeUpload(String uploadId) throws DriveException { return node(send("POST", "/uploads/" + uploadId + "/complete", null)); }

    public void cancelUpload(String uploadId) {
        try { send("DELETE", "/uploads/" + uploadId, null); } catch (Exception ignored) { /* best effort */ }
    }

    /** Sends file[offset, offset+length) as one chunk. */
    ChunkResult putChunk(String uploadId, File file, long offset, long length, Progress progress, ResumableUpload.Cancel cancel) throws IOException {
        RequestBody body = new RangeBody(file, offset, length, progress, cancel);
        Request.Builder rb = req("/uploads/" + uploadId, true).header("Upload-Offset", String.valueOf(offset)).put(body);
        try (Response r = http.newCall(rb.build()).execute()) {
            String text = r.body() == null ? "" : r.body().string();
            String fresh = r.header("X-Refreshed-Token");
            if (fresh != null && !fresh.isEmpty() && auth != null) auth.onRefreshed(fresh);
            if (r.code() == 409) {
                try {
                    JsonObject o = JsonParser.parseString(text).getAsJsonObject();
                    if (o.has("code") && "offset_mismatch".equals(o.get("code").getAsString())) {
                        return new ChunkResult(true, o.get("received").getAsLong());   // the server knows better
                    }
                } catch (Exception ignored) { /* fall through to a normal error */ }
            }
            if (!r.isSuccessful()) throw error(r.code(), text);
            String h = r.header("Upload-Offset");
            return new ChunkResult(false, h == null ? offset + length : Long.parseLong(h));
        }
    }

    private static final class RangeBody extends RequestBody {
        private final File file;
        private final long offset, length;
        private final Progress progress;
        private final ResumableUpload.Cancel cancel;

        RangeBody(File file, long offset, long length, Progress progress, ResumableUpload.Cancel cancel) {
            this.file = file; this.offset = offset; this.length = length; this.progress = progress; this.cancel = cancel;
        }

        @Override public MediaType contentType() { return BINARY; }
        @Override public long contentLength() { return length; }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                byte[] buf = new byte[32 * 1024];
                long sent = 0;
                while (sent < length) {
                    if (cancel != null && cancel.isCancelled()) throw new ResumableUpload.CancelledException();
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, length - sent));
                    if (n < 0) throw new IOException("Unexpected end of file.");
                    sink.write(buf, 0, n);
                    sent += n;
                    if (progress != null) progress.onProgress(sent, length);
                }
            }
        }
    }
}
