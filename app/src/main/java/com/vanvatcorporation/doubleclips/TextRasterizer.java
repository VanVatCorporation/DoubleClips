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
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns text into pixels: one bitmap of the whole text block at the output canvas's own
 * resolution (font size is in canvas pixels, like FFmpeg drawtext's fontsize), with the outline
 * baked in. Used by the live preview, the OpenGL export and the style browser's thumbnails, so
 * they all draw identical text.
 * <p>
 * {@link #measure} is cheap and cached: the frame maths asks for the size every frame to place
 * the quad. {@link #renderText} is the expensive one and runs only when a new texture is needed.
 */
public final class TextRasterizer {

    /** Beyond this the text is skipped rather than allocating a huge bitmap. */
    private static final int MAX_DIMENSION = 4096;
    public static final String DEFAULT_FONT_FILE = "/system/fonts/DroidSans.ttf";

    private static final LruCache<String, int[]> SIZES = new LruCache<>(64);
    private static final LruCache<String, List<OpenGLEdit.TextUnit>> UNITS = new LruCache<>(32);
    private static final Map<String, Typeface> TYPEFACES = new HashMap<>();
    /** Project folder that relative font paths ("Fonts/X.ttf") are resolved against. Set by the editor / exporter. */
    private static volatile String fontRoot;

    private TextRasterizer() { }

    public static void setFontRoot(String projectPath) {
        fontRoot = projectPath;
    }

    /**
     * The file a style's font refers to: an absolute path, or one relative to the project folder.
     * Null when the style uses the default font or the file is missing (callers fall back to the default).
     */
    public static String resolveFont(TextStyle style) {
        if (style == null || style.fontPath == null || style.fontPath.isEmpty()) return null;
        File f = new File(style.fontPath);
        if (f.isAbsolute()) return f.isFile() ? f.getAbsolutePath() : null;
        String root = fontRoot;
        if (root == null) return null;
        File resolved = new File(root, style.fontPath);
        return resolved.isFile() ? resolved.getAbsolutePath() : null;
    }

    private static String textOf(EditingActivity.Clip clip) {
        return clip.textContent == null ? "" : clip.textContent;
    }

    private static TextStyle styleOf(EditingActivity.Clip clip) {
        return clip.textStyle != null ? clip.textStyle : TextStyle.DEFAULT;
    }

    /** Everything that changes the pixels of this text. */
    private static String key(String text, float fontSize, TextStyle style) {
        String font = resolveFont(style);
        return fontSize + "|" + (font == null ? "" : font) + "|" + style.colorArgb + "|" + style.outlineColorArgb + "|"
                + style.outlineWidth + "|" + text;
    }

    /** Everything that changes the pixels of one unit of text. */
    public static String unitKey(String unitText, float fontSize, TextStyle style) {
        return key(unitText, fontSize, style);
    }

    /** Everything that changes the pixels of this clip's text. */
    public static String key(EditingActivity.Clip clip) {
        return key(textOf(clip), clip.fontSize, styleOf(clip));
    }

    private static synchronized Typeface typeface(String resolvedFontFile) {
        String k = resolvedFontFile == null ? "" : resolvedFontFile;
        Typeface cached = TYPEFACES.get(k);
        if (cached != null) return cached;
        Typeface tf = null;
        try {
            if (resolvedFontFile != null) tf = Typeface.createFromFile(resolvedFontFile);
            else if (new File(DEFAULT_FONT_FILE).isFile()) tf = Typeface.createFromFile(DEFAULT_FONT_FILE);
        } catch (RuntimeException ignored) {
            // unreadable font: fall back below
        }
        if (tf == null) tf = Typeface.DEFAULT;
        TYPEFACES.put(k, tf);
        return tf;
    }

    private static TextPaint paint(float fontSize, TextStyle style) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(Math.max(1f, Math.min(1024f, fontSize)));
        p.setTypeface(typeface(resolveFont(style)));
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
        return measure(textOf(clip), clip.fontSize, styleOf(clip));
    }

    public static int[] measure(String text, float fontSize, TextStyle style) {
        if (text == null || text.isEmpty() || fontSize <= 0f) return null;
        String key = key(text, fontSize, style);
        synchronized (SIZES) {
            int[] hit = SIZES.get(key);
            if (hit != null) return hit.length == 0 ? null : hit;
        }
        TextPaint p = paint(fontSize, style);
        int width = blockWidth(text, p);
        int height = layout(text, p, Math.max(1, width)).getHeight();
        int pad = padding(style);
        int[] size = {width + 2 * pad, height + 2 * pad};
        if (size[0] > MAX_DIMENSION || size[1] > MAX_DIMENSION || size[0] <= 0 || size[1] <= 0) size = new int[0];
        synchronized (SIZES) { SIZES.put(key, size); }
        return size.length == 0 ? null : size;
    }

    // ======================================================================
    //  Units (character / word / line): each one its own small bitmap, positioned from the block's layout
    // ======================================================================

    /** Extra room left and right of a unit's advance so slanted or overhanging glyphs aren't cut off. */
    private static int unitPadX(float fontSize, TextStyle style) {
        return padding(style) + (int) Math.ceil(fontSize * 0.12f);
    }

    /** {width, height, baseline-from-top} of one unit's bitmap. Shared by layout and rendering so they always agree. */
    private static int[] unitSize(String text, float fontSize, TextStyle style) {
        TextPaint p = paint(fontSize, style);
        int advance = (int) Math.ceil(Math.max(1f, p.measureText(text)));
        StaticLayout one = layout(text, p, advance);
        int pad = padding(style);
        return new int[]{advance + 2 * unitPadX(fontSize, style), one.getHeight() + 2 * pad, pad + one.getLineBaseline(0)};
    }

    /** One unit's text as an ARGB_8888 bitmap, drawn the way {@link #renderText} draws a block. */
    public static Bitmap renderUnit(String text, float fontSize, TextStyle style) {
        if (text == null || text.isEmpty() || fontSize <= 0f) return null;
        int[] size = unitSize(text, fontSize, style);
        if (size[0] > MAX_DIMENSION || size[1] > MAX_DIMENSION) return null;
        int pad = padding(style);
        int padX = unitPadX(fontSize, style);
        TextPaint measure = paint(fontSize, style);
        int advance = (int) Math.ceil(Math.max(1f, measure.measureText(text)));

        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(padX, pad);
        if (style.outlineWidth > 0f) {
            TextPaint stroke = paint(fontSize, style);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(style.outlineWidth * 2f);
            stroke.setColor(style.outlineColorArgb);
            layout(text, stroke, advance).draw(canvas);
        }
        TextPaint fill = paint(fontSize, style);
        fill.setColor(style.colorArgb);
        layout(text, fill, advance).draw(canvas);
        return bitmap;
    }

    /**
     * The clip's text split for per-unit animation, in reading order, each with its position inside the
     * text block. Spaces and line breaks are not units. Left-to-right text only for now.
     */
    public static List<OpenGLEdit.TextUnit> units(EditingActivity.Clip clip, String mode) {
        String text = textOf(clip);
        if (text.isEmpty() || clip.fontSize <= 0f || mode == null || "NONE".equals(mode)) return Collections.emptyList();
        TextStyle style = styleOf(clip);
        String cacheKey = key(clip) + "|" + mode;
        synchronized (UNITS) {
            List<OpenGLEdit.TextUnit> hit = UNITS.get(cacheKey);
            if (hit != null) return hit;
        }

        TextPaint p = paint(clip.fontSize, style);
        int width = blockWidth(text, p);
        StaticLayout block = layout(text, p, Math.max(1, width));
        int pad = padding(style);
        int padX = unitPadX(clip.fontSize, style);
        List<OpenGLEdit.TextUnit> out = new ArrayList<>();

        // Ranges of [start, end) character offsets, one per unit.
        List<int[]> ranges = new ArrayList<>();
        if ("LINE".equals(mode)) {
            for (int line = 0; line < block.getLineCount(); line++) {
                int s = block.getLineStart(line), e = block.getLineEnd(line);
                while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
                while (s < e && Character.isWhitespace(text.charAt(s))) s++;
                if (e > s) ranges.add(new int[]{s, e});
            }
        } else if ("WORD".equals(mode)) {
            int i = 0;
            while (i < text.length()) {
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
                int s = i;
                while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
                if (i > s) ranges.add(new int[]{s, i});
            }
        } else { // CHARACTER: grapheme clusters, so a letter and its accents (or an emoji sequence) stay together
            BreakIterator it = BreakIterator.getCharacterInstance();
            it.setText(text);
            int s = it.first();
            for (int e = it.next(); e != BreakIterator.DONE; s = e, e = it.next()) {
                if (!text.substring(s, e).trim().isEmpty()) ranges.add(new int[]{s, e});
            }
        }

        for (int[] r : ranges) {
            String unit = text.substring(r[0], r[1]);
            // A word / line can wrap across layout lines only if the block were narrower than its widest line,
            // which it never is (the block is sized to its widest line), so a range stays on one line.
            int line = block.getLineForOffset(r[0]);
            int[] size = unitSize(unit, clip.fontSize, style);
            float x = pad + block.getPrimaryHorizontal(r[0]) - padX;
            float y = block.getLineTop(line); // = (pad + lineTop) - pad: the unit bitmap starts one pad above the text
            if (size[0] > MAX_DIMENSION || size[1] > MAX_DIMENSION) continue;
            out.add(new OpenGLEdit.TextUnit(unit, x, y, size[0], size[1]));
        }
        List<OpenGLEdit.TextUnit> result = Collections.unmodifiableList(out);
        synchronized (UNITS) { UNITS.put(cacheKey, result); }
        return result;
    }

    /** The clip's text as an ARGB_8888 bitmap (outline underneath, fill on top), or null when there is nothing to draw. */
    public static Bitmap render(EditingActivity.Clip clip) {
        return renderText(textOf(clip), clip.fontSize, styleOf(clip));
    }

    public static Bitmap renderText(String text, float fontSize, TextStyle style) {
        int[] size = measure(text, fontSize, style);
        if (size == null) return null;
        int pad = padding(style);
        int blockW = size[0] - 2 * pad;

        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(pad, pad);

        if (style.outlineWidth > 0f) {
            TextPaint stroke = paint(fontSize, style);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(style.outlineWidth * 2f); // half of the stroke is hidden under the fill
            stroke.setColor(style.outlineColorArgb);
            layout(text, stroke, blockW).draw(canvas);
        }
        TextPaint fill = paint(fontSize, style);
        fill.setColor(style.colorArgb);
        layout(text, fill, blockW).draw(canvas);
        return bitmap;
    }
}
