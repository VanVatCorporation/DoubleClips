package com.vanvatcorporation.doubleclips;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mask pictures of clip masks, as small alpha-only GL textures (GL thread, context current). One instance per
 * renderer (the preview, one export), released with it.
 * <p>
 * A mask is drawn the way iOS does it: the hard-edged, anti-aliased shape painted on an 8-bit picture of the clip's box,
 * then blurred by the feather (see {@link ClipMaskPlan} for sizes and {@link MaskBlur}). The picture only depends on the
 * shape and the box's size, never on where the clip is, so a mask that doesn't change (or a clip that only moves) costs
 * one lookup per frame. A mask that animates makes a new picture for each frame it changes in; the least recently used
 * ones are dropped.
 * <p>
 * Sampled by the clip shaders at the position inside the box (see OpenGLEditNative.MASK_FRAGMENT_DECLS): row 0 of the
 * picture is the TOP of the box.
 */
public final class ClipMaskTextures {

    private static final String TAG = "ClipMaskTextures";

    /** Longest side of a mask picture: the preview is small anyway, the export keeps hard edges crisp. */
    public static final int PREVIEW_CAP = 1024, EXPORT_CAP = 2048;

    private static final int MAX_ENTRIES = 8;

    private final LinkedHashMap<String, Integer> cache = new LinkedHashMap<String, Integer>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            if (size() <= MAX_ENTRIES) return false;
            GLES20.glDeleteTextures(1, new int[]{eldest.getValue()}, 0);
            return true;
        }
    };

    private boolean broken;

    /**
     * The mask picture for this frame.
     *
     * @param renderScale output pixels per canvas pixel (the preview's width / the canvas width, 1 for the export)
     * @return a GL texture id, or 0 when the mask can't be made (the clip is then drawn without it)
     */
    public int textureFor(ClipMaskFrame frame, float renderScale, int cap) {
        if (broken || frame == null) return 0;
        float boxW = frame.boxW * renderScale, boxH = frame.boxH * renderScale;
        float feather = 0f;
        for (ClipMaskResolver.State s : frame.states) feather = Math.max(feather, s.feather);
        // One size for the whole picture, chosen for the softest mask, so none of them needs more blur than a picture this size allows.
        ClipMaskPlan plan = ClipMaskPlan.of(boxW, boxH, feather, cap);
        String key = frame.states.size() == 1 ? key(frame.state, plan) : key(frame, plan);
        Integer hit = cache.get(key);
        if (hit != null) return hit;
        int id = frame.states.size() == 1 ? build(frame.state, plan) : buildCombined(frame, plan, boxW, boxH);
        if (id != 0) cache.put(key, id);
        return id;
    }

    /** Frees every texture. Safe to call twice. */
    public void release() {
        for (int id : cache.values()) GLES20.glDeleteTextures(1, new int[]{id}, 0);
        cache.clear();
    }

    private static String key(ClipMaskResolver.State s, ClipMaskPlan plan) {
        // Invert is not part of it: the shader flips the same picture.
        return s.shape + '|' + q(s.centerX) + '|' + q(s.centerY) + '|' + q(s.width) + '|' + q(s.height) + '|'
                + q(s.rotation) + '|' + q(s.feather) + '|' + q(s.roundness) + '|' + plan.texW + 'x' + plan.texH;
    }

    /** Several masks: every one's shape AND invert AND mode, because the picture has them all baked in. */
    private static String key(ClipMaskFrame frame, ClipMaskPlan plan) {
        StringBuilder sb = new StringBuilder("M");
        for (int i = 0; i < frame.states.size(); i++) {
            ClipMaskResolver.State s = frame.states.get(i);
            sb.append('#').append(s.shape).append('|').append(q(s.centerX)).append('|').append(q(s.centerY)).append('|')
                    .append(q(s.width)).append('|').append(q(s.height)).append('|').append(q(s.rotation)).append('|')
                    .append(q(s.feather)).append('|').append(q(s.roundness)).append('|').append(s.invert ? 1 : 0)
                    .append('|').append(i == 0 ? ClipMask.ADD : frame.modes.get(i));
        }
        return sb.append('|').append(plan.texW).append('x').append(plan.texH).toString();
    }

    private static long q(float v) {
        return Math.round(v * 10000f);
    }

    /** One mask's picture as bytes (w x h, 255 = shape), hard edge anti-aliased, then blurred by {@code sigma} texture pixels. */
    private static byte[] pixelsOf(ClipMaskResolver.State state, int w, int h, float sigma) {
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            ClipMaskGeometry.draw(state, w, h, new CanvasMaskPainter(new Canvas(bitmap), paint));

            int rowBytes = bitmap.getRowBytes();
            ByteBuffer raw = ByteBuffer.allocate(rowBytes * h);
            bitmap.copyPixelsToBuffer(raw);
            byte[] pixels = raw.array();
            if (rowBytes != w) {
                byte[] compact = new byte[w * h];
                for (int y = 0; y < h; y++) System.arraycopy(pixels, y * rowBytes, compact, y * w, w);
                pixels = compact;
            }
            if (sigma > 0f) MaskBlur.blur(pixels, w, h, sigma);
            return pixels;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private int build(ClipMaskResolver.State state, ClipMaskPlan plan) {
        try {
            return upload(pixelsOf(state, plan.texW, plan.texH, plan.sigma), plan.texW, plan.texH);
        } catch (RuntimeException | OutOfMemoryError e) {
            return giveUp(e);
        }
    }

    /** Several masks as one picture: each is drawn, blurred by its own feather and flipped if inverted, then folded in by its mode. */
    private int buildCombined(ClipMaskFrame frame, ClipMaskPlan plan, float boxW, float boxH) {
        try {
            byte[] combined = null;
            for (int i = 0; i < frame.states.size(); i++) {
                ClipMaskResolver.State s = frame.states.get(i);
                float sigma = ClipMaskPlan.sigmaInBoxPixels(s.feather, boxW, boxH) * plan.scale;
                if (sigma <= ClipMaskPlan.MIN_SIGMA) sigma = 0f;
                byte[] picture = pixelsOf(s, plan.texW, plan.texH, Math.min(sigma, ClipMaskPlan.MAX_BLUR_SIGMA));
                if (s.invert) ClipMaskCombiner.invert(picture);
                if (combined == null) combined = picture;
                else ClipMaskCombiner.combine(combined, picture, frame.modes.get(i));
            }
            return combined == null ? 0 : upload(combined, plan.texW, plan.texH);
        } catch (RuntimeException | OutOfMemoryError e) {
            return giveUp(e);
        }
    }

    private int upload(byte[] pixels, int w, int h) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer upload = ByteBuffer.allocateDirect(pixels.length);
        upload.put(pixels).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, w, h, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, upload);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4); // GL's default again, for whoever uploads next
        return tex[0];
    }

    private int giveUp(Throwable e) {
        // Out of memory or no GL: stop trying, the clips are drawn without their masks rather than failing the frame.
        broken = true;
        Log.w(TAG, "Clip masks are switched off for this session: " + e);
        return 0;
    }
}
