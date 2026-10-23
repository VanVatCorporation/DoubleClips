package com.vanvatcorporation.drivekit;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Image thumbnails for the file lists, like the web Drive: the server has no thumbnail endpoint, so a thumbnail is the
 * image itself, loaded inline. Every first load therefore spends the daily download allowance, so each thumbnail is shrunk
 * and kept on disk: it is only ever downloaded once. The user can turn them off (Settings, previewThumbnails).
 */
public final class Thumbs {
    private Thumbs() {}

    /** Bigger originals show their icon instead: loading them just for a thumbnail would eat the allowance. */
    public static final long MAX_SOURCE_BYTES = 8L * 1024 * 1024;
    private static final int EDGE_PX = 256;
    private static final Set<String> EXT = new HashSet<>(java.util.Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"));

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 10)) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount() / 1024; }
    };

    /** On unless the account turned it off (default on, same as the web). */
    public static boolean enabled(Models.Me me) {
        return me == null || me.settings == null || me.settings.previewThumbnails == null || me.settings.previewThumbnails;
    }

    public static boolean isImage(Models.Node n) {
        if (n == null || n.isFolder || n.size <= 0 || n.size > MAX_SOURCE_BYTES || n.name == null) return false;
        int dot = n.name.lastIndexOf('.');
        return dot >= 0 && EXT.contains(n.name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Shows the thumbnail of {@code n} in {@code view}, or {@code placeholderRes} (the type icon) while it loads, when it is
     * not an image, or when thumbnails are off. Safe to call from RecyclerView binds: a recycled view is never given a
     * thumbnail that belongs to another row.
     */
    public static void bind(ImageView view, DriveClient client, Models.Node n, boolean enabled, int placeholderRes) {
        bind(view, client, n, enabled, placeholderRes, null, -1);
    }

    /**
     * Same, for icon views that tint and pad their placeholder icon: the tint and padding apply to the placeholder only,
     * and a thumbnail is shown untinted and edge to edge. {@code placeholderPaddingPx} &lt; 0 leaves the padding alone.
     */
    public static void bind(ImageView view, DriveClient client, Models.Node n, boolean enabled, int placeholderRes,
                            ColorStateList placeholderTint, int placeholderPaddingPx) {
        roundCorners(view);
        view.setTag(n.id);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImageTintList(placeholderTint);
        if (placeholderPaddingPx >= 0) view.setPadding(placeholderPaddingPx, placeholderPaddingPx, placeholderPaddingPx, placeholderPaddingPx);
        view.setImageResource(placeholderRes);
        if (!enabled || client == null || !isImage(n) || FAILED.contains(n.id)) return;

        Bitmap cached = MEMORY.get(n.id);
        if (cached != null) { show(view, n.id, cached, placeholderPaddingPx >= 0); return; }

        final Context app = view.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap b = load(app, client, n.id);
            if (b == null) { FAILED.add(n.id); return; }
            MEMORY.put(n.id, b);
            MAIN.post(() -> show(view, n.id, b, placeholderPaddingPx >= 0));
        });
    }

    /** Forget every cached thumbnail (sign-out). */
    public static void clear(Context c) {
        MEMORY.evictAll();
        FAILED.clear();
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private static void show(ImageView view, String id, Bitmap b, boolean clearPadding) {
        if (!id.equals(view.getTag())) return;               // the row was recycled for another item
        view.setImageTintList(null);
        if (clearPadding) view.setPadding(0, 0, 0, 0);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(b);
    }

    private static Bitmap load(Context app, DriveClient client, String id) {
        File disk = new File(dir(app), id + ".jpg");
        if (disk.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(disk.getAbsolutePath());
            if (b != null) return b;
            disk.delete();
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(client.inlineContentUrl(id)).openConnection();
            c.setRequestProperty("Authorization", client.authHeader());
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return null;     // 429 = today's allowance is used up: keep the icon
            byte[] data = readAll(c.getInputStream(), MAX_SOURCE_BYTES);
            if (data == null) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= EDGE_PX && bounds.outHeight / (sample * 2) >= EDGE_PX) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;

            try (FileOutputStream out = new FileOutputStream(disk)) { b.compress(Bitmap.CompressFormat.JPEG, 82, out); } catch (Exception ignored) { /* disk cache is best effort */ }
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, long max) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            long total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > max) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static void roundCorners(ImageView v) {
        if (v.getClipToOutline()) return;
        final float r = 8 * v.getResources().getDisplayMetrics().density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        v.setClipToOutline(true);
    }
}
