package com.vanvatcorporation.doubleclips;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GL textures for rasterised text, shared by the preview engine and the OpenGL export. GL thread
 * only. Keyed by what changes the pixels (text, size, style), so a text clip costs one rasterise +
 * upload per distinct look, not per frame.
 */
public final class TextTextureCache {

    private static final int MAX_TEXTURES = 24;

    private final LinkedHashMap<String, Integer> textures = new LinkedHashMap<>(16, 0.75f, true);

    /** Texture holding the clip's text (straight alpha), or 0 when there is nothing to draw. */
    public int texture(EditingActivity.Clip clip) {
        String key = TextRasterizer.key(clip);
        Integer hit = textures.get(key);
        if (hit != null) return hit;

        Bitmap bitmap = TextRasterizer.render(clip);
        if (bitmap == null) return 0;
        int texture;
        try {
            texture = upload(bitmap);
        } finally {
            bitmap.recycle();
        }
        textures.put(key, texture);

        Iterator<Map.Entry<String, Integer>> it = textures.entrySet().iterator();
        while (textures.size() > MAX_TEXTURES && it.hasNext()) {
            Map.Entry<String, Integer> eldest = it.next();
            if (eldest.getValue() == texture) break;
            GLES20.glDeleteTextures(1, new int[]{eldest.getValue()}, 0);
            it.remove();
        }
        return texture;
    }

    /**
     * Uploads with STRAIGHT alpha. The shaders blend with SRC_ALPHA / ONE_MINUS_SRC_ALPHA, so
     * premultiplied data would darken the soft edge of the glyphs. Bitmap.getPixels returns
     * un-premultiplied colours, which is exactly what is needed here.
     */
    private static int upload(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] argb = new int[w * h];
        bitmap.getPixels(argb, 0, w, 0, 0, w, h);
        ByteBuffer rgba = ByteBuffer.allocateDirect(w * h * 4);
        for (int c : argb) {
            rgba.put((byte) ((c >> 16) & 0xFF));
            rgba.put((byte) ((c >> 8) & 0xFF));
            rgba.put((byte) (c & 0xFF));
            rgba.put((byte) ((c >>> 24) & 0xFF));
        }
        rgba.rewind();

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    public void closeAll() {
        for (int texture : textures.values()) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        textures.clear();
    }
}
