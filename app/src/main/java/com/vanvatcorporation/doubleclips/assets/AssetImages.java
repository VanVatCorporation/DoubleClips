package com.vanvatcorporation.doubleclips.assets;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Loads the poster's small preview JPEGs (at most 200 KB each) into cards: a few worker threads, a memory cache, and a check that
 * the row still wants the picture when it arrives (rows are recycled while the download runs). Browsing never touches the poster's
 * file or allowance: the preview is a separate small picture.
 */
public final class AssetImages {
    private AssetImages() { }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    /**
     * Shows the picture at {@code url} in {@code view} (the view's tag marks which url it is waiting for). {@code maxWidth} bounds
     * the decoded size. A failed download simply leaves the placeholder.
     */
    public static void load(ImageView view, String url, int maxWidth) {
        view.setTag(url);
        view.setImageDrawable(null);
        if (url == null) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        POOL.execute(() -> {
            Bitmap bitmap = fetch(url, maxWidth);
            if (bitmap == null) return;
            CACHE.put(url, bitmap);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap fetch(String url, int maxWidth) {
        try (Response r = HTTP.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            byte[] bytes = r.body().bytes();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
