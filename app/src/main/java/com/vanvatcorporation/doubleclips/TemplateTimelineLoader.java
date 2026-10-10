package com.vanvatcorporation.doubleclips;

import android.content.Context;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Loads a template's timeline (iOS: TemplateTimelineLoader). Blocking: call it off the UI thread.
 * A cached copy is keyed by the template's id AND timestamp, so an updated template downloads again.
 */
public final class TemplateTimelineLoader {
    private TemplateTimelineLoader() {}

    private static final String CACHE_FOLDER = "TemplateTimelines";
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    public static class LoadException extends IOException {
        public LoadException(String message) { super(message); }
    }

    private static File cacheFile(Context context, TemplateAreaScreen.TemplateData template) {
        String id = template.getTemplateId() == null ? "" : template.getTemplateId();
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '-' ? c : '_');
        }
        File dir = new File(context.getCacheDir(), CACHE_FOLDER);
        return new File(dir, safe + "-" + template.getTemplateTimestamp() + ".json");
    }

    /** Parses timeline text into the strip/slot model. A FRESH parse each call: rendering edits the clips it gets. */
    public static TemplateTimelineInfo parse(String raw) throws LoadException {
        TemplateTimelineJson.Parsed parsed;
        try { parsed = TemplateTimelineJson.parse(raw); }
        catch (TemplateTimelineJson.NotATimelineException e) {
            throw new LoadException("The server's answer isn't a timeline: " + e.getMessage());
        }
        EditingActivity.Timeline timeline;
        try { timeline = ProjectGson.forLoad().fromJson(parsed.timelineJson, EditingActivity.Timeline.class); }
        catch (RuntimeException e) { throw new LoadException("The template's timeline file isn't readable."); }
        if (timeline == null || timeline.tracks == null) throw new LoadException("The template's timeline file isn't readable.");
        TemplateTimelineInfo info = TemplateTimelineInfo.make(timeline);
        info.canvas = parsed.canvas;
        return info;
    }

    /** The template's timeline: from the cache when present, else downloaded (and cached once it parses). */
    public static TemplateTimelineInfo load(Context context, TemplateAreaScreen.TemplateData template) throws IOException {
        String link = template.getTemplateTimelineLink();
        if (link.isEmpty()) throw new LoadException("This template has no timeline.");

        File cached = cacheFile(context, template);
        if (cached.isFile()) {
            try { return parse(new String(readAll(new java.io.FileInputStream(cached)), StandardCharsets.UTF_8)); }
            catch (IOException | RuntimeException ignored) { /* damaged cache: download again */ }
        }

        String raw = download(link);
        TemplateTimelineInfo info = parse(raw); // only a readable file is kept
        File dir = cached.getParentFile();
        if (dir != null) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(cached)) { out.write(raw.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { /* cache is best-effort */ }
        return info;
    }

    private static String download(String link) throws IOException {
        URL url;
        try { url = new URL(link); } catch (java.net.MalformedURLException e) { throw new LoadException("The template's timeline link isn't valid."); }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code > 299) throw new LoadException("The template's timeline couldn't be downloaded (" + code + ").");
            return new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new LoadException("The template's timeline file is too large.");
            }
            return out.toByteArray();
        }
    }
}
