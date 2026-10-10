package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Live preview that renders through the SAME OpenGL code as the export
 * ({@link OpenGLEdit#computeFrameForTimestamp} for what to draw, the shaders in
 * {@link OpenGLEditNative} for how), straight into a TextureView surface. Port of the desktop
 * PreviewWorker / PreviewFramePool design:
 * <ul>
 *   <li><b>One GL thread owns everything.</b> Decoders, textures and the output surface all live
 *       there. Nothing about a decoder's lifetime depends on a View, so a TextureView
 *       recreating its surface can't strand a clip (the failure of the per-clip-view design).</li>
 *   <li><b>Newest request wins.</b> {@link #render} replaces any request that has not started
 *       yet, so scrubbing can never build a backlog. The "what to draw" maths runs on the
 *       caller's thread and the immutable result is handed over, so the GL thread never reads the
 *       live timeline while the UI mutates it.</li>
 *   <li><b>Decoders are pooled per source FILE</b>, not per clip. A request is served by an open
 *       decoder that is at, or a little behind, the wanted frame (plain playback = one steady
 *       stream per clip); otherwise an idle decoder of the same file is flushed and re-seeked, or
 *       a new one is opened (max {@link #MAX_VIDEO_STREAMS}, least recently used evicted).</li>
 * </ul>
 * Video + image clips only. Text / 3D / audio stay with the legacy ClipRenderer views.
 */
public final class PreviewEngine {

    private static final String TAG = "PreviewEngine";

    /** Long edge of the GL output buffer. The TextureView scales it up to the canvas on screen. */
    public static final int PREVIEW_MAX_EDGE = 1280;
    static final int MAX_VIDEO_STREAMS = 4;
    static final int MAX_IMAGES = 12;
    static final long FORWARD_WINDOW_US = 1_500_000L;
    static final long IDLE_CLOSE_NANOS = 4_000_000_000L;
    private static final float PREFETCH_SECONDS = 1.5f;
    /** Time the GL thread may spend per frame, after drawing, decoding toward a clip that hasn't started yet. */
    private static final long PREROLL_BUDGET_NANOS = 5_000_000L;
    /** While playing, the prefetch list is recomputed at most this often (it looks 1.5 s ahead, so it barely changes). */
    private static final long PREFETCH_REFRESH_NANOS = 250_000_000L;
    /** While playing, a request this many frames BEHIND the shown frame holds it instead of flushing the decoder. */
    static final int PLAYING_BACKWARD_TOLERANCE_FRAMES = 3;

    /** Called on the GL thread when the engine can't continue (the caller should fall back to the legacy preview). */
    public interface FailureListener {
        void onFailure(String message);
    }

    private static final class Request {
        final List<OpenGLEdit.FrameLayer> layers;
        final List<OpenGLEdit.DrawCommand> prefetch;
        final float timeSeconds;

        Request(List<OpenGLEdit.FrameLayer> layers, List<OpenGLEdit.DrawCommand> prefetch, float timeSeconds) {
            this.layers = layers;
            this.prefetch = prefetch;
            this.timeSeconds = timeSeconds;
        }
    }

    private final Context context;
    private final MainAreaScreen.ProjectData project;
    private final int canvasW, canvasH, previewW, previewH;
    private final boolean stretch;
    private final OpenGLEdit edit = new OpenGLEdit();

    // ---- diagnostics (logcat tag PreviewEngine, filter "preview"): where does playback time go, and why do decoders restart?
    private final FrameProfiler profiler = new FrameProfiler(120, System::nanoTime, this::onProfilerLine, "decode", "swap")
            .idleReset(500_000_000L);
    private final java.util.concurrent.atomic.AtomicInteger supersededRequests = new java.util.concurrent.atomic.AtomicInteger();
    private int restartsBackward, restartsAhead, restartsFresh, restartLogged;
    private final java.util.concurrent.atomic.AtomicLong uiComputeNanos = new java.util.concurrent.atomic.AtomicLong(); // UI thread: time spent computing a frame (render())
    private final java.util.concurrent.atomic.AtomicInteger uiComputeCount = new java.util.concurrent.atomic.AtomicInteger();
    private List<OpenGLEdit.DrawCommand> cachedPrefetch; private long cachedPrefetchAtNanos;

    private void onProfilerLine(String line) {
        Log.i(TAG, "preview " + line);
        long uiNanos = uiComputeNanos.getAndSet(0);
        int uiCount = uiComputeCount.getAndSet(0);
        Log.i(TAG, "preview since last line: " + supersededRequests.getAndSet(0) + " requests skipped (a newer one arrived first), "
                + "decoder restarts: " + restartsBackward + " backwards, " + restartsAhead + " far ahead, " + restartsFresh + " not positioned yet"
                + (uiCount > 0 ? ", UI thread frame computation " + String.format(java.util.Locale.US, "%.2f", uiNanos / 1e6 / uiCount) + " ms avg" : ""));
        restartsBackward = restartsAhead = restartsFresh = 0;
    }
    private final FailureListener failureListener;

    private volatile boolean useProxy;
    private volatile boolean closed;

    private HandlerThread glThread, callbackThread;
    private Handler glHandler, callbackHandler;

    // ---- GL-thread-only state ----
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface windowSurface = EGL14.EGL_NO_SURFACE;
    private Surface outputSurface;
    private OpenGLEditNative.TransformShader shader;
    private OpenGLEditNative.ImageTransformShader imageShader;
    private OpenGLEditNative.TransitionBlendShader blendShader;
    private OpenGLEditNative.GaussianBlurShader blurShader;
    private Offscreen layerA, layerB, blurA, blurB;
    private Pool pool;
    private Request lastRequest;

    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
    private boolean computeFailureLogged;

    public PreviewEngine(Context context, MainAreaScreen.ProjectData project, int canvasWidth, int canvasHeight,
                         boolean stretchToFull, boolean useProxy, FailureListener failureListener) {
        this.context = context.getApplicationContext();
        this.project = project;
        this.canvasW = Math.max(2, canvasWidth);
        this.canvasH = Math.max(2, canvasHeight);
        double k = Math.min(1.0, PREVIEW_MAX_EDGE / (double) Math.max(canvasW, canvasH));
        this.previewW = Math.max(2, (int) Math.round(canvasW * k));
        this.previewH = Math.max(2, (int) Math.round(canvasH * k));
        this.stretch = stretchToFull;
        this.useProxy = useProxy;
        this.failureListener = failureListener;
        OpenGLEdit.textMeasurer = TextRasterizer::measure;
        OpenGLEdit.textUnitProvider = TextRasterizer::units;

        glThread = new HandlerThread("Preview-GL");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        // Decoder frame-available callbacks need their own thread: the GL thread blocks waiting for
        // them, so delivering them on it would deadlock (same reason as OpenGLEditNative).
        callbackThread = new HandlerThread("Preview-FrameCallback");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());

        try {
            runOnGlThreadAndWait(() -> {
                initEgl();
                // Idempotent; has to be loaded before computeFrameForTimestamp looks animations up.
                ClipAnimationAssets.loadAll(this.context);
                shader = new OpenGLEditNative.TransformShader();
                shader.init();
                imageShader = new OpenGLEditNative.ImageTransformShader();
                imageShader.init();
                pool = new Pool();
            });
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public int getPreviewWidth() { return previewW; }
    public int getPreviewHeight() { return previewH; }

    // ======================================================================
    //  Public API (any thread; in practice the UI thread)
    // ======================================================================

    /** Hooks the engine to a TextureView. Safe to call before the view has a surface. */
    public void attach(TextureView view) {
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
                st.setDefaultBufferSize(previewW, previewH);
                final Surface surface = new Surface(st);
                post(() -> {
                    destroyWindowSurface();
                    outputSurface = surface;
                    int[] attribs = {EGL14.EGL_NONE};
                    windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0);
                    if (windowSurface == EGL14.EGL_NO_SURFACE) {
                        fail("Could not create the preview surface (EGL 0x" + Integer.toHexString(EGL14.eglGetError()) + ")");
                        return;
                    }
                    if (lastRequest != null) renderRequest(lastRequest); // repaint what was on screen
                });
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {
                // The buffer size is fixed; the TextureView scales it.
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                // The GL thread must stop using the surface before we return true (the view
                // releases the SurfaceTexture then).
                if (!closed) runOnGlThreadAndWaitQuietly(PreviewEngine.this::destroyWindowSurface);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) { }
        });
    }

    /**
     * Asks for the frame at {@code timeSeconds}. Replaces any earlier request that hasn't started.
     * {@code playing} also warms the decoders the playhead is about to need.
     */
    public void render(EditingActivity.Timeline timeline, float timeSeconds, boolean playing) {
        if (closed) return;
        List<OpenGLEdit.FrameLayer> layers = Collections.emptyList();
        List<OpenGLEdit.DrawCommand> prefetch = null;
        long computeStart = System.nanoTime();
        try {
            synchronized (edit) {
                layers = edit.computeFrameForTimestamp(timeline, timeSeconds, canvasW, canvasH, stretch);
                if (playing) {
                    // Looking 1.5 s ahead barely changes from one frame to the next: recompute it a few times a
                    // second, not 60 (this runs on the UI thread, and the whole frame used to be computed twice per tick).
                    if (cachedPrefetch == null || computeStart - cachedPrefetchAtNanos > PREFETCH_REFRESH_NANOS) {
                        List<OpenGLEdit.DrawCommand> fresh = new ArrayList<>();
                        for (OpenGLEdit.FrameLayer layer : edit.computeFrameForTimestamp(timeline, timeSeconds + PREFETCH_SECONDS, canvasW, canvasH, stretch)) {
                            if (layer.simpleDraw != null) fresh.add(layer.simpleDraw);
                            else {
                                fresh.add(layer.transition.clipACommand);
                                fresh.add(layer.transition.clipBCommand);
                            }
                        }
                        cachedPrefetch = fresh;
                        cachedPrefetchAtNanos = computeStart;
                    }
                    prefetch = cachedPrefetch;
                } else {
                    cachedPrefetch = null; // scrubbing / paused: the next playback starts with a fresh look-ahead
                }
            }
        } catch (RuntimeException e) {
            if (!computeFailureLogged) {
                computeFailureLogged = true;
                Log.w(TAG, "Could not compute the frame at t=" + timeSeconds, e);
            }
        }
        uiComputeNanos.addAndGet(System.nanoTime() - computeStart);
        uiComputeCount.incrementAndGet();
        if (pending.getAndSet(new Request(layers, prefetch, timeSeconds)) != null) supersededRequests.incrementAndGet();
        if (drainScheduled.compareAndSet(false, true)) post(this::drain);
    }

    /** Switches the decode source between original files and proxies; takes effect on the next frame. */
    public void setUseProxy(boolean useProxy) {
        if (this.useProxy == useProxy) return;
        this.useProxy = useProxy;
        post(() -> {
            if (pool != null) pool.closeAll();
            if (lastRequest != null) renderRequest(lastRequest);
        });
    }

    public void close() {
        if (closed) return;
        closed = true;
        pending.set(null);
        if (glHandler != null) {
            runOnGlThreadAndWaitQuietly(() -> {
                try {
                    if (pool != null) pool.closeAll();
                    if (layerA != null) layerA.release();
                    if (layerB != null) layerB.release();
                    if (blurA != null) blurA.release();
                    if (blurB != null) blurB.release();
                } catch (RuntimeException e) {
                    Log.w(TAG, "close: " + e);
                }
                destroyWindowSurface();
                releaseEgl();
            });
        }
        if (glThread != null) { glThread.quitSafely(); glThread = null; }
        if (callbackThread != null) { callbackThread.quitSafely(); callbackThread = null; }
    }

    // ======================================================================
    //  GL thread: scheduling
    // ======================================================================

    private void post(Runnable r) {
        Handler h = glHandler;
        if (h != null && !closed) h.post(() -> {
            if (!closed) {
                try { r.run(); } catch (RuntimeException e) { fail("Preview error: " + e); }
            }
        });
    }

    private void drain() {
        Request r = pending.getAndSet(null);
        drainScheduled.set(false); // a request arriving from here on schedules its own drain
        if (r == null) return;
        renderRequest(r);
    }

    private boolean hasNewerRequest() {
        return pending.get() != null;
    }

    private void fail(String message) {
        Log.e(TAG, message);
        if (failureListener != null) failureListener.onFailure(message);
    }

    private void runOnGlThreadAndWait(Runnable r) {
        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        glHandler.post(() -> {
            try { r.run(); } catch (Throwable t) { error[0] = t; } finally { latch.countDown(); }
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (error[0] != null) throw new RuntimeException(error[0]);
    }

    private void runOnGlThreadAndWaitQuietly(Runnable r) {
        try { runOnGlThreadAndWait(r); } catch (RuntimeException e) { Log.w(TAG, "GL task failed: " + e); }
    }

    // ======================================================================
    //  GL thread: EGL
    // ======================================================================

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("Unable to get EGL14 display");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new RuntimeException("Unable to initialize EGL14");

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0) || num[0] < 1) {
            throw new RuntimeException("Unable to find a suitable EGLConfig");
        }
        eglConfig = configs[0];
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("Unable to create EGL context");
        // Current whenever there's no window surface, so GL resources can always be created/deleted.
        pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (pbuffer == EGL14.EGL_NO_SURFACE) throw new RuntimeException("Unable to create pbuffer surface");
        if (!EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) throw new RuntimeException("Unable to make EGL current");
    }

    private void destroyWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext);
            EGL14.eglDestroySurface(eglDisplay, windowSurface);
            windowSurface = EGL14.EGL_NO_SURFACE;
        }
        if (outputSurface != null) {
            outputSurface.release();
            outputSurface = null;
        }
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        pbuffer = EGL14.EGL_NO_SURFACE;
    }

    // ======================================================================
    //  GL thread: drawing (mirrors OpenGLEditNative.exportTimeline's per-frame section)
    // ======================================================================

    private void renderRequest(Request r) {
        lastRequest = r;
        if (windowSurface == EGL14.EGL_NO_SURFACE || closed) return;
        if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)) {
            destroyWindowSurface();
            return;
        }

        profiler.frameStart();
        pool.beginFrame(r.prefetch != null);
        shader.beginFrame(previewW, previewH); // viewport + clear + blending
        float blurScale = previewW / (float) canvasW;

        for (OpenGLEdit.FrameLayer layer : r.layers) {
            if (layer.simpleDraw != null) {
                if (layer.simpleDraw.blurSigmaPixels > 0f) {
                    ensureBlur();
                    blurA.bind();
                    blurA.clearTransparent();
                    drawCommand(layer.simpleDraw, r.timeSeconds);
                    float sigma = layer.simpleDraw.blurSigmaPixels * blurScale;
                    blurB.bind();
                    blurB.clearTransparent();
                    blurShader.draw(blurA.texture, 1f, 0f, previewW, previewH, sigma);
                    bindScreen();
                    blurShader.draw(blurB.texture, 0f, 1f, previewW, previewH, sigma);
                } else {
                    drawCommand(layer.simpleDraw, r.timeSeconds);
                }
                continue;
            }
            ensureTransition();
            OpenGLEdit.TransitionCommand t = layer.transition;
            layerA.bind();
            layerA.clearTransparent();
            drawCommand(t.clipACommand, r.timeSeconds);
            layerB.bind();
            layerB.clearTransparent();
            drawCommand(t.clipBCommand, r.timeSeconds);
            bindScreen();
            blendShader.draw(layerA.texture, layerB.texture, t.progress, OpenGLEditNative.TransitionBlendShader.styleToId(t.style));
        }

        profiler.begin(FrameProfiler.ENCODE); // "swap" in the preview's report
        if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
            int error = EGL14.eglGetError();
            if (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) destroyWindowSurface();
        }
        profiler.end(FrameProfiler.ENCODE);

        // After the frame is on its way: warm what the playhead is about to need, drop what it left.
        if (r.prefetch != null) {
            long deadline = System.nanoTime() + PREROLL_BUDGET_NANOS; // shared by every upcoming clip this frame
            for (OpenGLEdit.DrawCommand cmd : r.prefetch) pool.prefetch(cmd, r.timeSeconds, deadline);
        }
        pool.trimIdle();
        profiler.frameEnd();
    }

    private void bindScreen() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, previewW, previewH);
    }

    private void ensureTransition() {
        if (layerA != null) return;
        layerA = new Offscreen(previewW, previewH);
        layerB = new Offscreen(previewW, previewH);
        blendShader = new OpenGLEditNative.TransitionBlendShader();
        blendShader.init();
    }

    private void ensureBlur() {
        if (blurA != null) return;
        blurA = new Offscreen(previewW, previewH);
        blurB = new Offscreen(previewW, previewH);
        blurShader = new OpenGLEditNative.GaussianBlurShader();
        blurShader.init();
    }

    private boolean drawCommand(OpenGLEdit.DrawCommand cmd, float outputTimeSeconds) {
        if (cmd.clip.type == EditingActivity.ClipType.TEXT) {
            int texture = cmd.textUnit == null ? pool.text.texture(cmd.clip) : pool.text.unitTexture(cmd.clip, cmd.textUnit);
            if (texture == 0) return false;
            imageShader.drawClip(texture, cmd.mvpMatrix, cmd.opacity,
                    cmd.hueDegrees, cmd.saturation, cmd.brightness, cmd.temperatureKelvin,
                    cmd.unfoldTopWidth, cmd.unfoldBottomWidth, cmd.unfoldHeight, cmd.contrast);
            return true;
        }
        if (cmd.clip.type == EditingActivity.ClipType.IMAGE) {
            ImageEntry image = pool.image(cmd.clip);
            if (image == null) return false;
            imageShader.drawClip(image.texture, cmd.mvpMatrix, cmd.opacity,
                    cmd.hueDegrees, cmd.saturation, cmd.brightness, cmd.temperatureKelvin,
                    cmd.unfoldTopWidth, cmd.unfoldBottomWidth, cmd.unfoldHeight, cmd.contrast);
            return true;
        }
        if (cmd.clip.type != EditingActivity.ClipType.VIDEO) return false;

        PreviewDecoder decoder = pool.video(cmd.clip, cmd, true);
        if (decoder == null) return false;
        shader.drawClip(decoder.textureId, decoder.texMatrix(), cmd.mvpMatrix, cmd.opacity,
                cmd.hueDegrees, cmd.saturation, cmd.brightness, cmd.temperatureKelvin,
                cmd.unfoldTopWidth, cmd.unfoldBottomWidth, cmd.unfoldHeight, cmd.contrast);
        return true;
    }

    // ======================================================================
    //  Offscreen layer (transitions / blur scratch)
    // ======================================================================

    private static final class Offscreen {
        final int framebuffer, texture, width, height;

        Offscreen(int width, int height) {
            this.width = width;
            this.height = height;
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            texture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            int[] fb = new int[1];
            GLES20.glGenFramebuffers(1, fb, 0);
            framebuffer = fb[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Offscreen framebuffer incomplete: 0x" + Integer.toHexString(status));
            }
        }

        void bind() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glViewport(0, 0, width, height);
        }

        void clearTransparent() {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        void release() {
            GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        }
    }

    // ======================================================================
    //  Image entries
    // ======================================================================

    private static final class ImageEntry {
        final int texture;
        ImageEntry(int texture) { this.texture = texture; }
    }

    // ======================================================================
    //  Pool: decoders per source file + cached images. GL thread only.
    // ======================================================================

    private final class Pool {
        private final List<Stream> streams = new ArrayList<>();
        private final Map<String, ImageEntry> images = new LinkedHashMap<>(16, 0.75f, true);
        private final Set<Object> reported = new HashSet<>();
        final TextTextureCache text = new TextTextureCache();
        private long frameCounter = 0;

        private final class Stream {
            final String path;
            final PreviewDecoder decoder;
            long lastUsedFrame = -1;
            long lastUsedNanos = System.nanoTime();

            Stream(String path, PreviewDecoder decoder) {
                this.path = path;
                this.decoder = decoder;
            }
        }

        private boolean playing;

        void beginFrame(boolean playing) {
            frameCounter++;
            this.playing = playing;
        }

        PreviewDecoder video(EditingActivity.Clip clip, OpenGLEdit.DrawCommand cmd, boolean forDraw) {
            String path = videoPath(clip);
            long wantedUs = Math.max(0L, Math.round(sourceTimeSeconds(clip, cmd) * 1_000_000.0));
            Stream stream;
            try {
                stream = acquire(path, wantedUs);
            } catch (IOException | RuntimeException e) {
                if (reported.add(clip)) Log.w(TAG, "Could not open a clip for preview: " + e.getMessage());
                return null;
            }
            if (!forDraw) return stream.decoder;

            stream.lastUsedFrame = frameCounter;
            stream.lastUsedNanos = System.nanoTime();
            boolean ok;
            profiler.begin(FrameProfiler.DECODE);
            try {
                // A frame before the clip's in-point belongs to another part of the file (a keyframe the decoder started
                // from): never show it. iOS gets this from its composition segments; here it is the floor below.
                long inPointFloorUs = clip.isReverse() ? Long.MIN_VALUE
                        : Math.round(clip.startClipTrim * 1_000_000.0) - stream.decoder.frameDurationUs();
                ok = stream.decoder.advanceToTime(wantedUs, PreviewEngine.this::hasNewerRequest, inPointFloorUs);
            } catch (RuntimeException e) {
                ok = false;
                if (reported.add(clip)) Log.w(TAG, "Decoder failed in preview: " + e);
            }
            profiler.end(FrameProfiler.DECODE);
            if (!ok) {
                if (reported.add("noframe:" + clip.hashCode())) Log.w(TAG, "A clip produced no frames in preview: " + path);
                return null;
            }
            return stream.decoder;
        }

        /**
         * Gets the stream a clip will need shortly ready. A clip that has not started yet is positioned at its FIRST
         * frame (not at where the look-ahead time falls inside it: that is later, and the real first request would then
         * be "behind the start position" and flush the decoder a second time), and the decoder is run toward that frame
         * in small slices, so the keyframe-to-first-frame decode is paid before the clip arrives, not when it does.
         * A clip that is already playing is only kept open.
         */
        void prefetch(OpenGLEdit.DrawCommand cmd, float nowSeconds, long deadlineNanos) {
            if (cmd.clip.type != EditingActivity.ClipType.VIDEO) return;
            try {
                String path = videoPath(cmd.clip);
                double sourceSeconds = sourceTimeSeconds(cmd.clip, cmd);
                boolean notStarted = cmd.clip.startTime > nowSeconds && !cmd.clip.isReverse();
                if (notStarted) sourceSeconds = Math.min(sourceSeconds, cmd.clip.startClipTrim);
                long wantedUs = Math.max(0L, Math.round(sourceSeconds * 1_000_000.0));
                Stream stream = acquire(path, wantedUs);
                if (notStarted && System.nanoTime() < deadlineNanos) {
                    stream.decoder.preRoll(wantedUs, deadlineNanos);
                }
            } catch (IOException | RuntimeException ignored) {
                // reported properly if the clip is actually drawn
            }
        }

        /**
         * A reversed clip's DrawCommand time refers to the pre-reversed intermediate the export builds,
         * which the preview doesn't have: reversed clips play forward here (export is correct).
         */
        private double sourceTimeSeconds(EditingActivity.Clip clip, OpenGLEdit.DrawCommand cmd) {
            double t = cmd.localSourceTimeSeconds;
            if (clip.isReverse()) {
                if (reported.add("reverse:" + clip.getClipName())) {
                    Log.i(TAG, "Reverse isn't previewed - '" + clip.getClipName() + "' plays forward in preview");
                }
                t += clip.startClipTrim;
            }
            return Math.max(0.0, t);
        }

        private String videoPath(EditingActivity.Clip clip) {
            if (useProxy) {
                String proxy = clip.getAbsolutePreviewPath(project, com.vanvatcorporation.doubleclips.constants.Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION);
                if (new File(proxy).isFile()) return proxy;
            }
            return clip.getAbsolutePath(project);
        }

        private Stream acquire(String path, long wantedUs) throws IOException {
            // 1. An open stream of this file that is at the wanted frame or a little behind it.
            Stream best = null;
            long bestDistance = Long.MAX_VALUE;
            for (Stream s : streams) {
                if (!s.path.equals(path) || !s.decoder.canServe(wantedUs, playing)) continue;
                long distance = wantedUs - s.decoder.positionUs();
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = s;
                }
            }
            if (best != null) return best;

            // 2. Re-seek an idle stream of the same file (the scrub / jump case): flush, no new codec.
            Stream idle = null;
            for (Stream s : streams) {
                if (!s.path.equals(path) || s.lastUsedFrame == frameCounter) continue;
                if (idle == null || s.lastUsedNanos < idle.lastUsedNanos) idle = s;
            }
            if (idle != null) {
                noteRestart(idle, wantedUs);
                idle.decoder.restartAt(wantedUs);
                idle.lastUsedNanos = System.nanoTime();
                return idle;
            }

            // 3. Open a new one, making room first.
            while (streams.size() >= MAX_VIDEO_STREAMS) {
                Stream lru = null;
                for (Stream s : streams) {
                    if (s.lastUsedFrame == frameCounter) continue;
                    if (lru == null || s.lastUsedNanos < lru.lastUsedNanos) lru = s;
                }
                if (lru == null) break; // everything is in use this very frame: allow the overshoot
                lru.decoder.release();
                streams.remove(lru);
            }
            PreviewDecoder decoder = new PreviewDecoder(path);
            try {
                decoder.open(wantedUs);
            } catch (IOException | RuntimeException e) {
                decoder.release();
                throw e;
            }
            Stream created = new Stream(path, decoder);
            streams.add(created);
            return created;
        }

        /** Counts why a decoder is about to be flushed, and logs the first few with the numbers (rate-limited). */
        private void noteRestart(Stream s, long wantedUs) {
            String why = s.decoder.refusalReason(wantedUs, playing);
            if (why.startsWith("behind")) restartsBackward++;
            else if (why.startsWith("ahead")) restartsAhead++;
            else restartsFresh++;
            restartLogged++;
            if (restartLogged <= 12 || restartLogged % 50 == 0) {
                Log.i(TAG, "preview restart #" + restartLogged + " (flushes the decoder): " + new File(s.path).getName()
                        + " wanted " + wantedUs / 1000 + " ms, " + why + ", " + (playing ? "playing" : "scrubbing / paused"));
            }
        }

        void trimIdle() {
            long now = System.nanoTime();
            Iterator<Stream> it = streams.iterator();
            while (it.hasNext()) {
                Stream s = it.next();
                if (s.lastUsedFrame != frameCounter && now - s.lastUsedNanos > IDLE_CLOSE_NANOS) {
                    s.decoder.release();
                    it.remove();
                }
            }
        }

        ImageEntry image(EditingActivity.Clip clip) {
            String path = clip.getAbsolutePath(project);
            if (clip.removeBackground) {
                String cutout = clip.getCutoutPath(project);
                if (new File(cutout).isFile()) path = cutout;
            }
            ImageEntry entry = images.get(path);
            if (entry != null) return entry;
            try {
                entry = new ImageEntry(uploadImage(path));
            } catch (IOException | RuntimeException e) {
                if (reported.add(clip)) Log.w(TAG, "Could not open an image for preview: " + e.getMessage());
                return null;
            }
            images.put(path, entry);
            Iterator<Map.Entry<String, ImageEntry>> it = images.entrySet().iterator();
            while (images.size() > MAX_IMAGES && it.hasNext()) {
                Map.Entry<String, ImageEntry> eldest = it.next();
                if (eldest.getValue() == entry) break;
                GLES20.glDeleteTextures(1, new int[]{eldest.getValue().texture}, 0);
                it.remove();
            }
            return entry;
        }

        void closeAll() {
            for (Stream s : streams) s.decoder.release();
            streams.clear();
            for (ImageEntry e : images.values()) GLES20.glDeleteTextures(1, new int[]{e.texture}, 0);
            images.clear();
            text.closeAll();
        }
    }

    private static int uploadImage(String path) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Could not read image: " + path);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_MAX_EDGE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
        if (bitmap == null) throw new IOException("Could not decode image: " + path);
        try {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            return tex[0];
        } finally {
            bitmap.recycle();
        }
    }

    // ======================================================================
    //  PreviewDecoder: a SEEKABLE version of OpenGLEditNative.ClipFrameSource
    //  (same "greatest pts <= target, hold when the next frame is in the future" rule, so a frame
    //  is picked exactly as the export would pick it). GL thread only.
    // ======================================================================

    private final class PreviewDecoder {
        private static final long NONE = Long.MIN_VALUE;
        private static final long STEP_TIMEOUT_US = 30_000L;
        private static final int MAX_IDLE_SPINS = 66; // ~2s of nothing => treat as end of stream

        private final String path;
        private final MediaExtractor extractor = new MediaExtractor();
        private MediaCodec decoder;
        private int textureId = -1;
        private SurfaceTexture surfaceTexture;
        private Surface decoderSurface;

        private final Object frameLock = new Object();
        private boolean frameAvailable;

        private boolean sawInputEos, sawOutputEos;
        private int pendingIndex = -1;
        private long pendingPtsUs;
        private boolean hasLatched;
        private boolean acceptFirst = true;
        private long anchorUs = 0;          // where the stream was last (re)positioned; used until a frame is consumed
        private long latchedPtsUs = NONE;   // pts of the frame currently in the texture
        private long consumedPtsUs = NONE;  // pts of the last frame taken from the decoder
        private long frameDurationUs = 33_333L;
        private final float[] matrix = new float[16];

        PreviewDecoder(String path) { this.path = path; }

        void open(long startUs) throws IOException {
            extractor.setDataSource(path);
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { track = i; break; }
            }
            if (track < 0) throw new IOException("No video track found in " + path);
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    float fps = format.getFloat(MediaFormat.KEY_FRAME_RATE);
                    if (fps > 1f) frameDurationUs = (long) (1_000_000f / fps);
                } catch (ClassCastException e) {
                    int fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    if (fps > 1) frameDurationUs = 1_000_000L / fps;
                }
            }

            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            textureId = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(st -> {
                synchronized (frameLock) {
                    frameAvailable = true;
                    frameLock.notifyAll();
                }
            }, callbackHandler);
            decoderSurface = new Surface(surfaceTexture);

            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, decoderSurface, null, 0);
            decoder.start();

            if (startUs > FORWARD_WINDOW_US) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                anchorUs = startUs;
            }
        }

        /** Position used to rank streams: the later of the shown frame and the last one consumed. */
        long positionUs() {
            long p = Math.max(latchedPtsUs, consumedPtsUs);
            return p == NONE ? anchorUs : p;
        }

        /** True when advancing forward from the current position reaches {@code wantedUs} cheaply. */
        boolean canServe(long wantedUs, boolean playing) {
            return refusalReason(wantedUs, playing) == null;
        }

        /**
         * Why this stream can't serve {@code wantedUs} cheaply (so it would have to flush and seek), or null when it can.
         * While playing, a request a few frames BEHIND the shown frame (timing jitter) is served by holding that frame:
         * a flush throws away every decoded frame in flight and costs far more than a briefly repeated picture.
         * Scrubbing and paused seeks stay frame-exact.
         */
        String refusalReason(long wantedUs, boolean playing) {
            if (latchedPtsUs == NONE && consumedPtsUs == NONE) {
                // positioned (opened / restarted) but nothing consumed yet
                if (wantedUs < anchorUs - frameDurationUs) return "behind the start position by " + (anchorUs - wantedUs) / 1000 + " ms";
                if (wantedUs - anchorUs > FORWARD_WINDOW_US) return "ahead of the start position by " + (wantedUs - anchorUs) / 1000 + " ms";
                return null;
            }
            long tolerance = playing ? PLAYING_BACKWARD_TOLERANCE_FRAMES * frameDurationUs : frameDurationUs / 2;
            if (latchedPtsUs != NONE && wantedUs < latchedPtsUs - tolerance) {
                return "behind the shown frame by " + (latchedPtsUs - wantedUs) / 1000 + " ms";
            }
            if (wantedUs - positionUs() > FORWARD_WINDOW_US) return "ahead of the decoder by " + (wantedUs - positionUs()) / 1000 + " ms";
            return null;
        }

        /** Flush and jump to the keyframe at/before {@code wantedUs}; the next advanceToTime decodes up to it. */
        void restartAt(long wantedUs) {
            if (pendingIndex >= 0) {
                try { decoder.releaseOutputBuffer(pendingIndex, false); } catch (RuntimeException ignored) {}
                pendingIndex = -1;
            }
            decoder.flush();
            extractor.seekTo(wantedUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            sawInputEos = false;
            sawOutputEos = false;
            acceptFirst = true;
            consumedPtsUs = NONE;
            latchedPtsUs = NONE; // the texture still holds the old picture (hasLatched) until a new one lands
            anchorUs = wantedUs;
        }

        /**
         * Makes the texture hold the frame that belongs on screen at targetUs: the greatest
         * pts <= targetUs. A next frame still in the future keeps the current one. If the stream
         * ends first the last frame is held. {@code abort} lets a newer request cut a long
         * decode-through-the-GOP short (the frame found so far is still shown).
         * Returns false only if no frame has ever been decoded.
         */
        long frameDurationUs() { return frameDurationUs; }

        boolean advanceToTime(long targetUs, BooleanSupplier abort) { return advanceToTime(targetUs, abort, Long.MIN_VALUE); }

        /** {@code floorUs}: a candidate frame with a pts below it is dropped instead of shown (see the caller). */
        boolean advanceToTime(long targetUs, BooleanSupplier abort, long floorUs) {
            feedInput(MAX_FEED_PER_CALL); // keep the decoder's pipeline full before we wait on it
            int candidateIndex = -1;
            long candidatePts = NONE;

            while (true) {
                if (candidateIndex >= 0 && abort != null && abort.getAsBoolean()) break;
                if (pendingIndex < 0) {
                    fillPending();
                    if (pendingIndex < 0) break; // stream ended (or stalled)
                }
                if (pendingPtsUs <= targetUs || (acceptFirst && candidateIndex < 0)) {
                    if (candidateIndex >= 0) decoder.releaseOutputBuffer(candidateIndex, false);
                    candidateIndex = pendingIndex;
                    candidatePts = pendingPtsUs;
                    consumedPtsUs = pendingPtsUs;
                    pendingIndex = -1;
                } else {
                    break; // pending frame is in the future: keep it for later
                }
            }

            if (candidateIndex >= 0 && candidatePts < floorUs) {
                decoder.releaseOutputBuffer(candidateIndex, false); // before the clip starts: keep whatever is shown
            } else if (candidateIndex >= 0) {
                synchronized (frameLock) { frameAvailable = false; }
                decoder.releaseOutputBuffer(candidateIndex, true); // render this one only
                if (waitForFrame()) {
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(matrix);
                    hasLatched = true;
                    latchedPtsUs = candidatePts;
                    acceptFirst = false;
                }
            }
            feedInput(MAX_FEED_PER_CALL); // the decoder works on the next frames while we draw this one
            return hasLatched;
        }

        /**
         * Decodes toward {@code targetUs} WITHOUT showing anything, until the deadline: frames before the target are
         * dropped, the last two are left for the real draw. Only for a stream that has not shown a frame yet (a clip
         * about to start). Returns true when it has caught up (or the stream ended), false when time ran out.
         */
        boolean preRoll(long targetUs, long deadlineNanos) {
            if (latchedPtsUs != NONE) return true;
            long stopBeforeUs = targetUs - 2 * frameDurationUs;
            while (System.nanoTime() < deadlineNanos) {
                feedInput(MAX_FEED_PER_CALL);
                if (pendingIndex < 0) {
                    fillPending();
                    if (pendingIndex < 0) return true; // stream ended (or stalled)
                }
                if (pendingPtsUs >= stopBeforeUs) return true; // close enough: keep this frame for the real draw
                decoder.releaseOutputBuffer(pendingIndex, false);
                consumedPtsUs = pendingPtsUs;
                pendingIndex = -1;
            }
            return false;
        }

        /** Input buffers fed per call: a hardware decoder wants several frames in flight, not one. */
        private static final int MAX_FEED_PER_CALL = 8;

        private void feedInput(int max) {
            for (int i = 0; i < max && !sawInputEos; i++) {
                int in = decoder.dequeueInputBuffer(0);
                if (in < 0) return; // every input buffer is in flight: the pipeline is full
                ByteBuffer buf = decoder.getInputBuffer(in);
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) {
                    decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    sawInputEos = true;
                    return;
                }
                decoder.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                extractor.advance();
            }
        }

        float[] texMatrix() { return matrix; }

        private void fillPending() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int idleSpins = 0;
            while (pendingIndex < 0 && !sawOutputEos) {
                feedInput(MAX_FEED_PER_CALL);
                int out = decoder.dequeueOutputBuffer(info, STEP_TIMEOUT_US);
                if (out >= 0) {
                    idleSpins = 0;
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size == 0) {
                        decoder.releaseOutputBuffer(out, false);
                    } else {
                        pendingIndex = out;
                        pendingPtsUs = info.presentationTimeUs;
                    }
                    if (eos) sawOutputEos = true;
                } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (++idleSpins > MAX_IDLE_SPINS) {
                        Log.w(TAG, "Decoder produced nothing for " + path + ", treating as end of stream");
                        sawOutputEos = true;
                    }
                }
            }
        }

        /** Blocks the GL thread until the callback thread says a frame arrived (max 1s). */
        private boolean waitForFrame() {
            synchronized (frameLock) {
                long deadline = System.currentTimeMillis() + 1000;
                while (!frameAvailable) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        Log.w(TAG, "Timed out waiting for a frame from " + path);
                        return false;
                    }
                    try {
                        frameLock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                frameAvailable = false;
                return true;
            }
        }

        void release() {
            try {
                if (decoder != null) {
                    try { decoder.stop(); } catch (RuntimeException ignored) {}
                    decoder.release();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Error releasing decoder for " + path + ": " + e);
            }
            decoder = null;
            if (surfaceTexture != null) surfaceTexture.release();
            if (decoderSurface != null) decoderSurface.release();
            extractor.release();
            if (textureId != -1) {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                textureId = -1;
            }
        }
    }
}
