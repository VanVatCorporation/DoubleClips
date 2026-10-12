package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the preview tiles of the style pickers (iOS TransitionTileRenderer / the effect tiles): every tile shows its
 * style drawn live by the REAL renderers (the same OpenGLEdit frame builder + the shaders the preview and the export
 * use) on stand-in pictures, frozen halfway; the selected tile loops 0 -> 1 so the style can be seen moving.
 *
 * <p>Transitions use two generated pictures, A (blue / teal) and B (orange / pink), exactly like iOS; effects use one
 * generated "scene" with sky, sun, hills and stripes, so distortions and colour looks have something to act on. The
 * pictures are drawn in code (never taken from the user's clips: a clip frame may be black, which would leave the
 * tile empty).
 *
 * <p><b>GIF override.</b> A style can show its own animation instead of the live tile: put a square GIF at
 * {@code assets/previews/transitions/<style-id>.gif} or {@code assets/previews/effects/<style-id>.gif} (the style's id,
 * e.g. {@code glitchblur.gif}, {@code camera-shake.gif}). The still tile is its middle frame, the selected tile plays
 * it. A missing or unreadable file just means the live tile is used.
 *
 * <p>Everything runs on one background thread that owns a small offscreen GL context (a pbuffer the size of a tile).
 * Callbacks arrive on the main thread. A style that can't be drawn (unknown id, GL failure) reports null and the tile
 * keeps its placeholder.
 */
public final class StyleTileRenderer {

    private static final String TAG = "StyleTileRenderer";

    public enum Kind { TRANSITION, EFFECT }

    public interface Callback<T> { void onReady(T value); }

    /** Pixels per tile side (the grid scales it). */
    public static final int TILE_PX = 192;
    /** The selected tile's loop: length, frames per second, and the rest on A / B at both ends (iOS: 15 % each). */
    public static final float LOOP_SECONDS = 2.4f;
    public static final int LOOP_FPS = 12;
    private static final float LOOP_REST = 0.15f;

    private static final float TRANSITION_SECONDS = 1.0f; // length of the blend drawn in a tile
    private static final float EFFECT_SECONDS = 2.0f;     // length of the effect clip drawn in a tile

    private static StyleTileRenderer instance;

    public static synchronized StyleTileRenderer get(Context context) {
        if (instance == null) instance = new StyleTileRenderer(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> staticCache = new HashMap<>();

    private StyleTileRenderer(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("StyleTiles");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    // ---- public --------------------------------------------------------------------------------

    /** The style halfway through (cached). */
    public void staticTile(Kind kind, String styleId, Callback<Bitmap> callback) {
        final String key = kind + ":" + styleId;
        Bitmap cached;
        synchronized (staticCache) { cached = staticCache.get(key); }
        if (cached != null) {
            callback.onReady(cached);
            return;
        }
        worker.post(() -> {
            Bitmap made = null;
            try {
                List<Bitmap> gif = gifFrames(kind, styleId);
                if (gif != null) made = gif.get(gif.size() / 2);
                else if (ensureGl()) made = renderAt(kind, styleId, 0.5f);
            } catch (RuntimeException e) {
                Log.w(TAG, "Tile failed for " + key + ": " + e.getMessage());
            }
            final Bitmap result = made;
            if (result != null) synchronized (staticCache) { staticCache.put(key, result); }
            main.post(() -> callback.onReady(result));
        });
    }

    /** The frames of the selected tile's loop ({@link #LOOP_FPS} fps, {@link #LOOP_SECONDS} s), or null. */
    public void loopFrames(Kind kind, String styleId, Callback<List<Bitmap>> callback) {
        worker.post(() -> {
            List<Bitmap> frames = null;
            try {
                frames = gifFrames(kind, styleId);
                if (frames == null && ensureGl()) {
                    int count = Math.round(LOOP_SECONDS * LOOP_FPS);
                    frames = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        float phase = i / (float) count;
                        float p = phase < LOOP_REST ? 0f : (phase > 1f - LOOP_REST ? 1f : (phase - LOOP_REST) / (1f - 2f * LOOP_REST));
                        Bitmap f = renderAt(kind, styleId, p);
                        if (f == null) { frames = null; break; }
                        frames.add(f);
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Loop failed for " + styleId + ": " + e.getMessage());
                frames = null;
            }
            final List<Bitmap> result = frames;
            main.post(() -> callback.onReady(result));
        });
    }

    // ---- optional GIFs (worker thread only) ---------------------------------------------------------

    private final Map<String, List<Bitmap>> gifCache = new HashMap<>(); // an empty list = there is no GIF for this style

    /** The frames of the style's GIF override, resampled to {@link #LOOP_FPS} and fitted to a tile, or null when it has none. */
    @SuppressWarnings("deprecation")
    private List<Bitmap> gifFrames(Kind kind, String styleId) {
        String key = kind + ":" + styleId;
        List<Bitmap> cached = gifCache.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        List<Bitmap> frames = new ArrayList<>();
        String path = "previews/" + (kind == Kind.TRANSITION ? "transitions" : "effects") + "/" + styleId + ".gif";
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            byte[] data = bytes.toByteArray();
            Movie movie = Movie.decodeByteArray(data, 0, data.length);
            if (movie != null && movie.width() > 0 && movie.height() > 0) {
                int durationMs = Math.max(movie.duration(), 1);
                int count = Math.min(Math.max(Math.round(durationMs / 1000f * LOOP_FPS), 1), 60);
                float scale = TILE_PX / (float) Math.max(movie.width(), movie.height());
                for (int i = 0; i < count; i++) {
                    Bitmap frame = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(frame);
                    canvas.drawColor(0xFF000000);
                    canvas.scale(scale, scale);
                    movie.setTime(count == 1 ? 0 : (int) ((long) durationMs * i / count));
                    movie.draw(canvas, 0, 0);
                    frames.add(frame);
                }
            }
        } catch (IOException | RuntimeException e) {
            // no GIF for this style (the normal case), or an unreadable one: the live tile is used
            frames.clear();
        }
        gifCache.put(key, frames);
        return frames.isEmpty() ? null : frames;
    }

    // ---- GL state (worker thread only) -----------------------------------------------------------

    private static final class Fbo {
        final int framebuffer, texture;

        Fbo(int size) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            texture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            int[] fb = new int[1];
            GLES20.glGenFramebuffers(1, fb, 0);
            framebuffer = fb[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        void bindCleared() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glViewport(0, 0, TILE_PX, TILE_PX);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
    }

    private boolean glReady, glBroken;
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    private OpenGLEditNative.ImageTransformShader imageShader;
    private OpenGLEditNative.PostFxShader postShader;
    private OpenGLEditNative.GaussianBlurShader blurShader;
    private OpenGLEditNative.TransitionBlendShader blendShader;
    private TransitionMaskRenderer maskRenderer;
    private EffectLayerRenderer effectRenderer;
    private Fbo layerA, layerB, scratch;
    private int texA, texB, texScene;
    private final OpenGLEdit edit = new OpenGLEdit();
    private final ByteBuffer pixels = ByteBuffer.allocateDirect(TILE_PX * TILE_PX * 4).order(ByteOrder.nativeOrder());

    private boolean ensureGl() {
        if (glReady) return true;
        if (glBroken) return false;
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new RuntimeException("eglInitialize failed");
            int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0);
            if (num[0] == 0) throw new RuntimeException("no EGL config");
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
                    new int[]{EGL14.EGL_WIDTH, TILE_PX, EGL14.EGL_HEIGHT, TILE_PX, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw new RuntimeException("eglMakeCurrent failed");

            ClipAnimationAssets.loadAll(context); // also registers the transition / effect styles; no-op after the first call
            imageShader = new OpenGLEditNative.ImageTransformShader();
            imageShader.init();
            postShader = new OpenGLEditNative.PostFxShader();
            postShader.init();
            blurShader = new OpenGLEditNative.GaussianBlurShader();
            blurShader.init();
            blendShader = new OpenGLEditNative.TransitionBlendShader();
            blendShader.init();
            maskRenderer = new TransitionMaskRenderer();
            effectRenderer = new EffectLayerRenderer();
            layerA = new Fbo(TILE_PX);
            layerB = new Fbo(TILE_PX);
            scratch = new Fbo(TILE_PX);
            texA = upload(pictureAB("A", 0xFF2E6BFF, 0xFF00C2A8));
            texB = upload(pictureAB("B", 0xFFFF8C1A, 0xFFF23A8C));
            texScene = upload(pictureScene());
            glReady = true;
        } catch (RuntimeException e) {
            glBroken = true;
            Log.w(TAG, "Tiles are switched off: " + e.getMessage());
        }
        return glReady;
    }

    // ---- the stand-in pictures -----------------------------------------------------------------------

    private static Bitmap pictureAB(String letter, int colorFrom, int colorTo) {
        Bitmap bmp = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, TILE_PX, TILE_PX, colorFrom, colorTo, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, TILE_PX, TILE_PX, p);
        p.setShader(null);
        p.setColor(0xEBFFFFFF);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(TILE_PX * 0.6f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(letter, TILE_PX / 2f, TILE_PX / 2f - (fm.ascent + fm.descent) / 2f, p);
        return bmp;
    }

    /** Sky, sun, two hills and a few thin stripes: colour, edges and shapes for filters and distortions to show on. */
    private static Bitmap pictureScene() {
        Bitmap bmp = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float s = TILE_PX;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, s, 0xFF2D7DD2, 0xFFFFB45A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        p.setShader(null);
        p.setColor(0xFFFFF3B0);
        c.drawCircle(s * 0.68f, s * 0.36f, s * 0.14f, p);
        Path hill = new Path();
        hill.moveTo(0, s * 0.78f);
        hill.quadTo(s * 0.3f, s * 0.48f, s * 0.62f, s * 0.78f);
        hill.lineTo(s * 0.62f, s);
        hill.lineTo(0, s);
        hill.close();
        p.setColor(0xFF1F5F3A);
        c.drawPath(hill, p);
        Path hill2 = new Path();
        hill2.moveTo(s * 0.3f, s * 0.85f);
        hill2.quadTo(s * 0.7f, s * 0.55f, s, s * 0.8f);
        hill2.lineTo(s, s);
        hill2.lineTo(s * 0.3f, s);
        hill2.close();
        p.setColor(0xFF143D27);
        c.drawPath(hill2, p);
        p.setColor(0xCCFFFFFF);
        for (int i = 1; i <= 3; i++) c.drawRect(s * 0.1f * i, s * 0.08f, s * 0.1f * i + s * 0.012f, s * 0.3f, p);
        return bmp;
    }

    private static int upload(Bitmap bmp) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        return tex[0];
    }

    // ---- one frame -----------------------------------------------------------------------------------

    private EditingActivity.Clip clipA, clipB, clipScene;

    private EditingActivity.Clip image(String name, float start, float duration, int track) {
        return new EditingActivity.Clip(name, start, duration, track, EditingActivity.ClipType.IMAGE, false, TILE_PX, TILE_PX);
    }

    /** The style at progress p (0..1) as a bitmap, or null when the id is unknown. */
    private Bitmap renderAt(Kind kind, String styleId, float p) {
        EditingActivity.Timeline timeline = new EditingActivity.Timeline();
        float t;
        if (kind == Kind.TRANSITION) {
            clipA = image("A", 0f, 2f, 0);
            clipB = image("B", 2f, 2f, 0);
            EditingActivity.Track track = new EditingActivity.Track();
            track.clips.add(clipA);
            track.clips.add(clipB);
            timeline.addTrack(track);
            timeline.duration = 4f;
            String id = styleId;
            boolean known = "none".equals(id) || TransitionStyleLoader.isDataDriven(id) || OpenGLEdit.SUPPORTED_TRANSITION_STYLES.contains(id);
            if (!known) id = "fade"; // styles the GPU can't draw play as a cross fade, like iOS shows them
            if (!"none".equals(id)) {
                EditingActivity.TransitionClip tc = new EditingActivity.TransitionClip(clipA, clipB, TRANSITION_SECONDS);
                tc.effect.style = id;
                tc.effect.duration = TRANSITION_SECONDS;
                tc.duration = TRANSITION_SECONDS;
                tc.mode = EditingActivity.TransitionClip.TransitionMode.OVERLAP;
                clipA.endTransition = tc;
                clipA.setEndTransitionEnabled(true);
            }
            // The OVERLAP window is [cut - d, cut): p = 0 is the start of A's last second, p = 1 is B alone just after the cut.
            t = p >= 0.999f ? 2.02f : (2f - TRANSITION_SECONDS) + p * TRANSITION_SECONDS;
        } else {
            if (EffectStyleLoader.get(styleId) == null) return null;
            clipScene = image("S", 0f, EFFECT_SECONDS + 1f, 0);
            EditingActivity.Clip fx = new EditingActivity.Clip("fx", 0f, EFFECT_SECONDS, 1, EditingActivity.ClipType.EFFECT, false, TILE_PX, TILE_PX);
            fx.effect = new EditingActivity.EffectTemplate(styleId, EFFECT_SECONDS, 0);
            EditingActivity.Track base = new EditingActivity.Track();
            base.clips.add(clipScene);
            EditingActivity.Track over = new EditingActivity.Track();
            over.clips.add(fx);
            timeline.addTrack(base);
            timeline.addTrack(over);
            timeline.duration = EFFECT_SECONDS + 1f;
            t = Math.min(p * EFFECT_SECONDS, EFFECT_SECONDS - 0.001f);
        }

        List<OpenGLEdit.FrameLayer> layers = edit.computeFrameForTimestamp(timeline, t, TILE_PX, TILE_PX, true);
        drawLayers(layers);
        return readBack();
    }

    private void bindScreen() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, TILE_PX, TILE_PX);
    }

    private int textureOf(EditingActivity.Clip clip) {
        if (clip == clipA) return texA;
        if (clip == clipB) return texB;
        return texScene;
    }

    private void drawLayers(List<OpenGLEdit.FrameLayer> layers) {
        bindScreen();
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        for (OpenGLEdit.FrameLayer layer : layers) {
            if (layer.effect != null) {
                effectRenderer.apply(layer.effect, TILE_PX, TILE_PX);
            } else if (layer.simpleDraw != null) {
                drawSimple(layer.simpleDraw);
            } else if (layer.transition != null) {
                OpenGLEdit.TransitionCommand t = layer.transition;
                layerA.bindCleared();
                if (t.clipACommand != null) drawClip(t.clipACommand);
                layerB.bindCleared();
                if (t.clipBCommand != null) drawClip(t.clipBCommand);
                bindScreen();
                if (t.mask != null) {
                    maskRenderer.draw(layerA.texture, layerB.texture, t.mask, TILE_PX, TILE_PX);
                } else {
                    blendShader.draw(layerA.texture, layerB.texture, t.progress, OpenGLEditNative.TransitionBlendShader.styleToId(t.style));
                }
            }
        }
    }

    private void drawClip(OpenGLEdit.DrawCommand c) {
        imageShader.drawClip(textureOf(c.clip), c.mvpMatrix, c.opacity, c.hueDegrees, c.saturation, c.brightness, c.temperatureKelvin,
                c.unfoldTopWidth, c.unfoldBottomWidth, c.unfoldHeight, c.contrast);
    }

    /** One picture; when it has a post pass / blur it goes through a layer first (the same order the preview uses). */
    private void drawSimple(OpenGLEdit.DrawCommand c) {
        boolean blur = c.blurSigmaPixels > 0f;
        if (!blur && c.post == null) {
            drawClip(c);
            return;
        }
        // layerB is free here (no transition is being drawn): draw the clip into it, then post / blur onto the screen.
        layerB.bindCleared();
        drawClip(c);
        int cur = layerB.texture;
        if (c.post != null) {
            scratch.bindCleared();
            GLES20.glDisable(GLES20.GL_BLEND);
            postShader.draw(cur, c.post, TILE_PX, TILE_PX);
            GLES20.glEnable(GLES20.GL_BLEND);
            cur = scratch.texture;
        }
        if (blur) {
            Fbo other = cur == layerB.texture ? scratch : layerB;
            other.bindCleared();
            GLES20.glDisable(GLES20.GL_BLEND);
            blurShader.draw(cur, 1f, 0f, TILE_PX, TILE_PX, c.blurSigmaPixels);
            GLES20.glEnable(GLES20.GL_BLEND);
            bindScreen();
            blurShader.draw(other.texture, 0f, 1f, TILE_PX, TILE_PX, c.blurSigmaPixels);
        } else {
            bindScreen();
            postShader.draw(cur, null, TILE_PX, TILE_PX);
        }
    }

    private Bitmap readBack() {
        bindScreen();
        pixels.clear();
        GLES20.glReadPixels(0, 0, TILE_PX, TILE_PX, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        int[] argb = new int[TILE_PX * TILE_PX];
        pixels.position(0);
        for (int y = 0; y < TILE_PX; y++) {
            int row = (TILE_PX - 1 - y) * TILE_PX; // GL rows run bottom-up
            for (int x = 0; x < TILE_PX; x++) {
                int r = pixels.get() & 0xFF, g = pixels.get() & 0xFF, b = pixels.get() & 0xFF;
                pixels.get(); // alpha: the tile is opaque
                argb[row + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, TILE_PX, 0, 0, TILE_PX, TILE_PX);
        return bmp;
    }
}
