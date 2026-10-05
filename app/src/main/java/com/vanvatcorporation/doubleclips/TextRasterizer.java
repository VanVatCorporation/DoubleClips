package com.vanvatcorporation.doubleclips;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns a TEXT clip into pixels: one bitmap of the whole text block at the output canvas's own
 * resolution (font size is in canvas pixels, like FFmpeg drawtext's fontsize), with the outline
 * baked in. Used by both the live preview and the OpenGL export, so they draw identical text.
 * <p>
 * {@link #measure} is cheap and cached: the frame maths asks for the size every frame to place
 * the quad. {@link #render} is the expensive one and runs only when a new texture is needed.
 */
public final class TextRasterizer {

    /** Beyond this the text is skipped rather than allocating a huge bitmap. */
    private static final int MAX_DIMENSION = 4096;
    private static final String DEFAULT_FONT_FILE = "/system/fonts/DroidSans.ttf";

    private static final LruCache<String, int[]> SIZES = new LruCache<>(64);
    private static final Map<String, Typeface> TYPEFACES = new HashMap<>();

    private TextRasterizer() { }

    private static String textOf(EditingActivity.Clip clip) {
        return clip.textContent == null ? "" : clip.textContent;
    }

    private static TextStyle styleOf(EditingActivity.Clip clip) {
        return clip.textStyle != null ? clip.textStyle : TextStyle.DEFAULT;
    }

    /** Everything that changes the pixels of this clip's text. */
    public static String key(EditingActivity.Clip clip) {
        return clip.fontSize + "|" + styleOf(clip).cacheKey() + "|" + textOf(clip);
    }

    private static synchronized Typeface typeface(String fontPath) {
        String k = fontPath == null ? "" : fontPath;
        Typeface cached = TYPEFACES.get(k);
        if (cached != null) return cached;
        Typeface tf = null;
        try {
            if (fontPath != null && new File(fontPath).isFile()) tf = Typeface.createFromFile(fontPath);
            else if (new File(DEFAULT_FONT_FILE).isFile()) tf = Typeface.createFromFile(DEFAULT_FONT_FILE);
        } catch (RuntimeException ignored) {
            // unreadable font: fall back below
        }
        if (tf == null) tf = Typeface.DEFAULT;
        TYPEFACES.put(k, tf);
        return tf;
    }

    private static TextPaint paint(EditingActivity.Clip clip, TextStyle style) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(Math.max(1f, Math.min(1024f, clip.fontSize)));
        p.setTypeface(typeface(style.fontPath));
        return p;
    }

    private static int blockWidth(String text, TextPaint paint) {
        float widest = 1f;
        for (String line : text.split("\n", -1)) {
            widest = Math.max(widest, Layout.getDesiredWidth(line, paint));
        }
        return (int) Math.ceil(widest);
    }

    private static StaticLayout layout(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();
    }

    private static int padding(TextStyle style) {
        return (int) Math.ceil(Math.max(0f, style.outlineWidth)) + 2;
    }

    /** {width, height} in canvas pixels of this clip's text bitmap, or null when there is nothing to draw. */
    public static int[] measure(EditingActivity.Clip clip) {
        String text = textOf(clip);
        if (text.isEmpty() || clip.fontSize <= 0f) return null;
        String key = key(clip);
        synchronized (SIZES) {
            int[] hit = SIZES.get(key);
            if (hit != null) return hit.length == 0 ? null : hit;
        }
        TextStyle style = styleOf(clip);
        TextPaint p = paint(clip, style);
        int width = blockWidth(text, p);
        int height = layout(text, p, Math.max(1, width)).getHeight();
        int pad = padding(style);
        int[] size = {width + 2 * pad, height + 2 * pad};
        if (size[0] > MAX_DIMENSION || size[1] > MAX_DIMENSION || size[0] <= 0 || size[1] <= 0) size = new int[0];
        synchronized (SIZES) { SIZES.put(key, size); }
        return size.length == 0 ? null : size;
    }

    /** The text as an ARGB_8888 bitmap (outline underneath, fill on top), or null when there is nothing to draw. */
    public static Bitmap render(EditingActivity.Clip clip) {
        int[] size = measure(clip);
        if (size == null) return null;
        String text = textOf(clip);
        TextStyle style = styleOf(clip);
        int pad = padding(style);
        int blockW = size[0] - 2 * pad;

        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(pad, pad);

        if (style.outlineWidth > 0f) {
            TextPaint stroke = paint(clip, style);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(style.outlineWidth * 2f); // half of the stroke is hidden under the fill
            stroke.setColor(style.outlineColorArgb);
            layout(text, stroke, blockW).draw(canvas);
        }
        TextPaint fill = paint(clip, style);
        fill.setColor(style.colorArgb);
        layout(text, fill, blockW).draw(canvas);
        return bitmap;
    }
}
