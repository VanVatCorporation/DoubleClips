package com.vanvatcorporation.doubleclips.assets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.vanvatcorporation.drivekit.AssetKind;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * The small picture sent with a post (desktop and iOS: AssetPreviewMaker): a JPEG of at most 200 KB that stands for the asset in the
 * Assets tab. The image itself, a frame of a video, or a sample line in a font. Audio, effects and packs have no picture (the tab then
 * shows the kind's icon). Every method does file work: call it off the main thread. They return null instead of throwing.
 */
public final class AssetPreviewMaker {
    private AssetPreviewMaker() { }

    /** The server refuses a larger preview. */
    public static final int MAX_BYTES = 200_000;
    private static final int EDGE = 640;

    /** The picture for a file of this kind, or null when the kind has none or the file can't give one. */
    public static byte[] jpegFor(File file, AssetKind kind) {
        try {
            switch (kind) {
                case IMAGE: return encode(decodeFile(file));
                case VIDEO: return encode(videoFrame(file));
                case FONT: return encode(fontSample(file));
                default: return null;
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A picture the user chose for it (any image the phone can read), shrunk to fit. */
    public static byte[] jpegFromPicture(Context context, Uri picture) {
        try (InputStream in = context.getContentResolver().openInputStream(picture)) {
            if (in == null) return null;
            // read once into memory (a picture, not a video): the bounds pass and the decode pass both need the bytes
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight);
            return encode(BitmapFactory.decodeByteArray(data, 0, data.length, options));
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- sources -----------------------------------------------------------------------------------------

    private static int sampleFor(int w, int h) {
        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= EDGE) sample *= 2;
        return sample;
    }

    private static Bitmap decodeFile(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    /** A frame about a second in (the very first frame is often black), or the first frame of a clip shorter than that. */
    private static Bitmap videoFrame(File video) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return frame != null ? frame : retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) { /* nothing more to free */ }
        }
    }

    /** "Aa Bb / 0123456789 ABC xyz" in the font, on a plain card. */
    private static Bitmap fontSample(File fontFile) {
        Typeface face;
        try {
            face = Typeface.createFromFile(fontFile);
        } catch (RuntimeException e) {
            return null;
        }
        Bitmap img = Bitmap.createBitmap(EDGE, 360, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(img);
        canvas.drawColor(Color.rgb(247, 247, 247));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(face);
        paint.setColor(Color.BLACK);
        paint.setTextSize(120f);
        canvas.drawText("Aa Bb", 36, 170, paint);
        paint.setColor(Color.DKGRAY);
        paint.setTextSize(44f);
        canvas.drawText("0123456789 ABC xyz", 36, 290, paint);
        return img;
    }

    // ---- JPEG under the cap ------------------------------------------------------------------------------

    /** Shrunk to 640 px on the long side, then JPEG: the quality steps down, then the picture gets smaller, until it fits 200 KB. */
    private static byte[] encode(Bitmap source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return null;
        Bitmap img = scaled(source, Math.min(1.0, (double) EDGE / Math.max(source.getWidth(), source.getHeight())));
        for (int round = 0; round < 4; round++) {
            for (int quality : new int[]{82, 65, 50, 35}) {
                byte[] out = jpeg(img, quality);
                if (out != null && out.length <= MAX_BYTES) return out;
            }
            img = scaled(img, 0.75);
        }
        return null;
    }

    /** An opaque copy at this scale (JPEG has no transparency: a transparent PNG gets a white background). */
    private static Bitmap scaled(Bitmap src, double scale) {
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(src, new android.graphics.Rect(0, 0, src.getWidth(), src.getHeight()), new android.graphics.Rect(0, 0, w, h), paint);
        return out;
    }

    private static byte[] jpeg(Bitmap img, int quality) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        return img.compress(Bitmap.CompressFormat.JPEG, quality, bytes) ? bytes.toByteArray() : null;
    }
}
