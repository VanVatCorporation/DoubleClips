package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.util.List;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Android-specific half of OpenGLEdit. Owns the headless EGL context used for
 * export rendering (no on-screen Surface/View involved — this is not the preview
 * path), and per-clip MediaCodec decode -> GL texture plumbing.
 *
 * Frame-in (MediaCodec -> GL texture), frame-out (GL -> MediaCodec encoder ->
 * video-only mp4) and the multi-clip timeline driver. Effects, transitions and
 * color grading are later steps (see PLAN.md).
 */
public class OpenGLEditNative {

    // Held for LoggingManager calls only (it requires Context on this platform).
    // Set once at construction, unlike FFmpegEditNative's static methods which
    // take Context per-call — this class is already instance-based (owns the
    // EGL context/GL thread), so a field is the natural fit here.
    private final Context context;

    public OpenGLEditNative(Context context) {
        this.context = context;
    }

    /**
     * Lets the UI follow an export without this class knowing about any screen.
     * Called from the GL thread - the listener must hop to the UI thread itself.
     */
    public interface ExportListener {
        void onLog(String message);
        /** frameIndex counts from 0; totalFrames is the whole export. */
        void onProgress(int frameIndex, int totalFrames);
    }

    private volatile boolean cancelled = false;

    /** Safe from any thread. exportTimeline stops at the next frame and still finalizes/cleans up. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    // ---- Headless EGL context -------------------------------------------------

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglDummySurface = EGL14.EGL_NO_SURFACE; // 1x1 pbuffer, never rendered to
    private EGLConfig eglConfig; // kept: also needed to create the encoder's window surface later

    // Export needs its own Looper thread: SurfaceTexture.OnFrameAvailableListener
    // requires one, and there is no Activity/main thread involved in a headless export.
    private HandlerThread glThread;
    private Handler glHandler;

    // Frame-available callbacks must NOT be delivered on glThread: advanceToTime()
    // blocks glThread waiting for a frame, so if the callback were posted to
    // glHandler it would sit in the queue behind that block and never run
    // (self-deadlock). A separate thread delivers the callback and wakes
    // glThread via wait()/notifyAll() on frameLock instead.
    private HandlerThread callbackThread;
    private Handler callbackHandler;

    public void start() {
        glThread = new HandlerThread("OpenGLEdit-Export-GLThread");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        callbackThread = new HandlerThread("OpenGLEdit-Export-FrameCallback");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());

        runOnGlThreadAndWait(this::initEgl);
    }

    public void shutdown() {
        runOnGlThreadAndWait(this::releaseEgl);
        if (glThread != null) {
            glThread.quitSafely();
            glThread = null;
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
        }
    }

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = null;
            throw new RuntimeException("Unable to initialize EGL14");
        }

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                // Needs BOTH bits: PBUFFER for our throwaway dummy surface,
                // WINDOW because the encoder's input Surface (step 2) also
                // needs an EGLSurface from this same config.
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("Unable to find suitable EGLConfig");
        }
        eglConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGL context");
        }

        // 1x1 pbuffer purely to satisfy eglMakeCurrent's surface requirement.
        // Nothing is ever drawn into this — real output happens via the encoder
        // Surface in step 2.
        int[] pbufferAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };
        eglDummySurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        if (eglDummySurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Unable to create dummy pbuffer surface");
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglDummySurface, eglDummySurface, eglContext)) {
            throw new RuntimeException("Unable to make EGL context current");
        }
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, eglDummySurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDummySurface = EGL14.EGL_NO_SURFACE;
    }

    private void runOnGlThreadAndWait(Runnable r) {
        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        glHandler.post(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (error[0] != null) {
            throw new RuntimeException(error[0]);
        }
    }


    // ---- Per-clip frame-in: MediaCodec decode -> GL_TEXTURE_EXTERNAL_OES ------

    /**
     * Owns one clip's decode pipeline: extractor + decoder + the OES texture
     * its frames land on. One instance per clip that needs decoding for the
     * current output frame (see step 4, multi-track scheduling, for how many
     * of these are alive at once).
     */
    public class ClipFrameSource {

        private final String clipPath;
        private final MediaExtractor extractor = new MediaExtractor();
        private MediaCodec decoder;

        private int textureId = -1;
        private SurfaceTexture surfaceTexture;
        private Surface decoderSurface;

        // Guards frameAvailable. Callback thread sets+notifies; GL thread waits+clears.
        private final Object frameLock = new Object();
        private boolean frameAvailable = false;

        private boolean sawInputEos = false;
        private boolean sawOutputEos = false;

        // One decoded-but-not-yet-released output buffer, held as a lookahead so
        // we can tell whether the NEXT frame is still in the future (see
        // advanceToTime). At most one is held between calls.
        private int pendingIndex = -1;
        private long pendingPtsUs = 0;
        // True once textureId holds a real frame; it keeps holding it (last
        // frame stays on screen) if the stream ends before the clip does.
        private boolean hasLatchedFrame = false;
        private boolean seekedToStart = false;

        public ClipFrameSource(String clipPath) {
            this.clipPath = clipPath;
        }

        /** Must be called on the GL thread. */
        public void open() throws IOException {
            extractor.setDataSource(clipPath);
            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No video track found in " + clipPath);
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);

            textureId = createExternalOesTexture();
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
        }

        private int selectVideoTrack(MediaExtractor extractor) {
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return i;
                }
            }
            return -1;
        }

        private int createExternalOesTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return textures[0];
        }

        /**
         * Makes textureId hold the frame that belongs on screen at targetTimeUs:
         * the frame with the greatest presentation time <= targetTimeUs. If the
         * next decoded frame is still in the future, the current frame is simply
         * kept (no new decode, no re-render) - that is what lets a 25fps source
         * play at the right speed inside a 30fps export. Skipped frames are
         * released without rendering. If the stream ends first, the last frame is
         * held (FFmpeg's tpad stop_mode=clone equivalent).
         *
         * The previous version returned the first frame at/after the target and
         * always consumed at least one new frame per call, so a 25fps clip in a
         * 30fps export ran 1.2x fast and ran out of frames at ~83% of its length.
         *
         * Returns false only if no frame has ever been decoded for this clip.
         * Targets must be non-decreasing (export only moves forward).
         */
        public boolean advanceToTime(long targetTimeUs, long timeoutUsPerStep) {
            if (!seekedToStart) {
                seekedToStart = true;
                // Clip trimmed to start late: jump to the nearest earlier keyframe
                // instead of decoding everything before it. Safe here because
                // nothing has been fed to the decoder yet.
                if (targetTimeUs > 1_000_000L) {
                    extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                }
            }

            int candidateIndex = -1; // best frame so far (pts <= target), unreleased

            while (true) {
                if (pendingIndex < 0) {
                    fillPending(timeoutUsPerStep);
                    if (pendingIndex < 0) break; // stream ended
                }
                // The very first frame is accepted even if it starts after the
                // target (clip whose first frame is slightly late).
                boolean acceptFirstFrame = !hasLatchedFrame && candidateIndex < 0;
                if (pendingPtsUs <= targetTimeUs || acceptFirstFrame) {
                    if (candidateIndex >= 0) decoder.releaseOutputBuffer(candidateIndex, false);
                    candidateIndex = pendingIndex;
                    pendingIndex = -1;
                } else {
                    break; // pending frame is in the future: keep it for later
                }
            }

            if (candidateIndex >= 0) {
                synchronized (frameLock) {
                    frameAvailable = false;
                }
                decoder.releaseOutputBuffer(candidateIndex, true); // render this one only
                waitForFrameAvailable();
                surfaceTexture.updateTexImage();
                hasLatchedFrame = true;
            }
            return hasLatchedFrame;
        }

        /** Feeds input and dequeues output until one decoded frame is pending, or the stream ends. */
        private void fillPending(long timeoutUsPerStep) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int idleSpins = 0;
            while (pendingIndex < 0 && !sawOutputEos) {
                if (!sawInputEos) {
                    int inputIndex = decoder.dequeueInputBuffer(0);
                    if (inputIndex >= 0) {
                        java.nio.ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEos = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, timeoutUsPerStep);
                if (outputIndex >= 0) {
                    idleSpins = 0;
                    boolean isEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size == 0) {
                        decoder.releaseOutputBuffer(outputIndex, false); // no picture in it
                    } else {
                        pendingIndex = outputIndex;
                        pendingPtsUs = info.presentationTimeUs;
                    }
                    if (isEos) sawOutputEos = true;
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Normal for a few iterations while the decoder pipeline
                    // fills; a long run of them means it is genuinely stuck.
                    if (++idleSpins > 50) {
                        LoggingManager.LogToPersistentDataPath(context,
                                "OpenGLEditNative: decoder produced nothing for " + clipPath + ", treating as end of stream");
                        sawOutputEos = true;
                    }
                }
            }
        }

        /**
         * Blocks glThread (safe: it's not the thread delivering the callback)
         * until the callback thread signals a frame arrived, or 1s elapses.
         * A timeout here means the decoder stalled — treated as EOS by the caller
         * rather than hanging the export forever.
         */
        private void waitForFrameAvailable() {
            synchronized (frameLock) {
                long deadline = System.currentTimeMillis() + 1000;
                while (!frameAvailable) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        LoggingManager.LogToPersistentDataPath(context,
                                "OpenGLEditNative: timed out waiting for frame from " + clipPath);
                        return;
                    }
                    try {
                        frameLock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frameAvailable = false;
            }
        }

        public int getTextureId() {
            return textureId;
        }

        /**
         * SurfaceTexture frames aren't necessarily upright/uncropped in texture
         * space — this matrix corrects that (a well-known MediaCodec/SurfaceTexture
         * requirement; skipping it can silently flip or misalign the image).
         * Valid only after a successful advanceToTime() call.
         */
        public float[] getTexTransformMatrix() {
            float[] matrix = new float[16];
            surfaceTexture.getTransformMatrix(matrix);
            return matrix;
        }

        /** Must be called on the GL thread. */
        public void release() {
            try {
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
            } catch (Exception e) {
                LoggingManager.LogToPersistentDataPath(context, "OpenGLEditNative: error releasing decoder for " + clipPath + ": " + e.getMessage());
            }
            if (surfaceTexture != null) surfaceTexture.release();
            if (decoderSurface != null) decoderSurface.release();
            extractor.release();
            if (textureId != -1) {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            }
        }
    }

    /** Runs r on the dedicated GL/export thread and blocks until it completes. */
    public void runOnGlThread(Runnable r) {
        runOnGlThreadAndWait(r);
    }


    // ---- Frame-out: composited GL frame -> MediaCodec encoder Surface ---------

    /**
     * Owns the export's video encoder: MediaCodec configured for Surface input
     * (so GL renders directly into it, no glReadPixels/CPU round-trip), the
     * EGLSurface wrapping its input Surface, and a MediaMuxer that wraps the
     * encoded stream into a video-only mp4.
     *
     * Audio is NOT handled here — see PLAN.md decisions log. This mp4 is handed
     * to FFmpeg afterward as a previousRenderedClipPath stage for audio mixing
     * and final mux (-c:v copy, no re-encode).
     */
    public class ExportEncoder {

        private MediaCodec encoder;
        private EGLSurface encoderEglSurface = EGL14.EGL_NO_SURFACE;
        private MediaMuxer muxer;
        private int muxerVideoTrackIndex = -1;
        private boolean muxerStarted = false;

        private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        /** Must be called on the GL thread. outputPath is the video-only mp4 this stage produces. */
        public void open(int width, int height, int bitrate, int frameRate, int iFrameIntervalSeconds, String outputPath) throws IOException {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            int[] surfaceAttribs = { EGL14.EGL_NONE };
            encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, inputSurface, surfaceAttribs, 0);
            if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("Unable to create EGL window surface for encoder input");
            }
            // inputSurface itself isn't retained past this — the EGLSurface owns
            // the connection to it now.
            inputSurface.release();

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        /** Must be called on the GL thread, after drawing this frame's content into the encoder EGL surface. */
        public void makeEncoderSurfaceCurrent() {
            if (!EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)) {
                throw new RuntimeException("Unable to make encoder EGL surface current");
            }
        }

        /**
         * Call after drawing one composited frame into the encoder surface.
         * presentationTimeNs must be strictly increasing across calls — use the
         * output frame's timeline position, not wall-clock time (export runs
         * faster or slower than real time).
         */
        public void swapAndPresent(long presentationTimeNs) {
            android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, presentationTimeNs);
            if (!EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)) {
                throw new RuntimeException("eglSwapBuffers failed for encoder surface");
            }
            drainEncoder(false);
        }

        /**
         * Pumps available encoded output to the muxer. endOfStream=true signals
         * the encoder to flush and finalizes the muxer once drained.
         */
        private void drainEncoder(boolean endOfStream) {
            if (endOfStream) {
                encoder.signalEndOfInputStream();
            }

            while (true) {
                int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);

                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break; // nothing ready yet, come back on the next frame
                    // else: keep polling, we must drain fully before finishing
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new RuntimeException("Encoder format changed twice — unexpected");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    muxerVideoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    java.nio.ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
                    if (encodedData == null) {
                        throw new RuntimeException("Encoder returned null output buffer at index " + outputIndex);
                    }

                    // MediaCodec can emit a codec-config buffer with size>0 that
                    // must NOT be passed to the muxer directly (format already
                    // carries it via addTrack).
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }

                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            throw new RuntimeException("Muxer not started before encoder produced data");
                        }
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(muxerVideoTrackIndex, encodedData, bufferInfo);
                    }

                    encoder.releaseOutputBuffer(outputIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break; // fully drained
                    }
                }
            }
        }

        /** Must be called on the GL thread. Flushes remaining frames and finalizes the mp4. */
        public void close() {
            try {
                drainEncoder(true);
            } finally {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
                if (muxer != null) {
                    if (muxerStarted) muxer.stop();
                    muxer.release();
                }
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, encoderEglSurface);
                }
            }
        }
    }


    // ---- Multi-clip compositing shader (step 4) --------------------------------
    // Applies SurfaceTexture's texture matrix, plus: an MVP matrix
    // (position/scale/rotation, computed by OpenGLEdit.buildClipMvp) applied to
    // vertex position instead of a fixed fullscreen quad, an opacity uniform,
    // and GL_BLEND enabled so multiple clips composite correctly track-over-track.

    public static class TransformShader {
        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "uniform mat4 uMvpMatrix;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = uMvpMatrix * aPosition;\n" +
                "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "uniform float uOpacity;\n" +
                "void main() {\n" +
                "    vec4 color = texture2D(uTexture, vTexCoord);\n" +
                "    gl_FragColor = vec4(color.rgb, color.a * uOpacity);\n" +
                "}\n";

        // Unit quad (-1,-1)..(1,1); OpenGLEdit's model matrix scales/rotates/
        // translates this into the clip's actual on-canvas position and size.
        // NOTE the flipped V: OpenGLEdit works in Y-DOWN canvas pixels (matching
        // FFmpeg's overlay), so model-space (-1,-1) lands at the TOP-left of the
        // output. SurfaceTexture's transform matrix follows the GL Y-UP
        // convention (v=1 is the top of the picture), so the top-left vertex must
        // sample v=1. Without this every clip renders upside-down.
        private static final float[] QUAD_VERTICES = {
                // x, y,      u, v
                -1f, -1f,     0f, 1f,
                 1f, -1f,     1f, 1f,
                -1f,  1f,     0f, 0f,
                 1f,  1f,     1f, 0f,
        };

        private int program;
        private int aPositionLoc;
        private int aTexCoordLoc;
        private int uTextureLoc;
        private int uTexMatrixLoc;
        private int uMvpMatrixLoc;
        private int uOpacityLoc;
        private java.nio.FloatBuffer vertexBuffer;

        /** Must be called on the GL thread, once, after EGL context is current. */
        public void init() {
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture");
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uMvpMatrixLoc = GLES20.glGetUniformLocation(program, "uMvpMatrix");
            uOpacityLoc = GLES20.glGetUniformLocation(program, "uOpacity");

            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4);
            bb.order(java.nio.ByteOrder.nativeOrder());
            vertexBuffer = bb.asFloatBuffer();
            vertexBuffer.put(QUAD_VERTICES);
            vertexBuffer.position(0);
        }

        /** Call once per output frame, before drawing any clips: sets viewport, clears, enables blending. */
        public void beginFrame(int viewportWidth, int viewportHeight) {
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }

        /** Call once per active clip, in back-to-front (track index ascending) order. */
        public void drawClip(int oesTextureId, float[] texMatrix, float[] mvpMatrix, float opacity) {
            GLES20.glUseProgram(program);

            vertexBuffer.position(0);
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aPositionLoc);

            vertexBuffer.position(2);
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoordLoc);

            GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0);
            GLES20.glUniform1f(uOpacityLoc, opacity);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniform1i(uTextureLoc, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPositionLoc);
            GLES20.glDisableVertexAttribArray(aTexCoordLoc);
        }

        private int buildProgram(String vertexSrc, String fragmentSrc) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);

            int prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vertexShader);
            GLES20.glAttachShader(prog, fragmentShader);
            GLES20.glLinkProgram(prog);

            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(prog);
                GLES20.glDeleteProgram(prog);
                throw new RuntimeException("Shader program link failed: " + log);
            }
            return prog;
        }

        private int compileShader(int type, String src) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, src);
            GLES20.glCompileShader(shader);

            int[] compileStatus = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
            if (compileStatus[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new RuntimeException("Shader compile failed: " + log);
            }
            return shader;
        }
    }


    // ---- Step 4: multi-clip/multi-track timeline export ------------------------

    /**
     * Full timeline export: walks every output frame via OpenGLEdit, opening/
     * closing each clip's ClipFrameSource lazily (only while that clip is
     * actually active - clips don't overlap within a track, so once a clip
     * drops out of the active set it's done and its decoder is released
     * immediately rather than held for the rest of the export).
     *
     * What is (not) reproduced is defined by OpenGLEdit - see its capability
     * flags. Audio is not handled here (see PLAN.md: audio stays on FFmpeg).
     *
     * @param listener optional; receives log lines and frame progress
     */
    public void exportTimeline(EditingActivity.Timeline timeline, OpenGLEdit edit, String projectPath,
                                int width, int height, int bitrate, int frameRate, String outputPath,
                                ExportListener listener) {
        runOnGlThreadAndWait(() -> {
            java.util.Map<EditingActivity.Clip, ClipFrameSource> activeSources = new java.util.IdentityHashMap<>();
            java.util.Set<EditingActivity.Clip> reportedNoFrame =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            TransformShader shader = new TransformShader();
            ExportEncoder encoder = new ExportEncoder();

            try {
                shader.init();
                encoder.open(width, height, bitrate, frameRate, /*iFrameIntervalSeconds*/ 1, outputPath);

                long timeoutUsPerStep = 100_000;
                float timelineDuration = timeline != null ? timeline.duration : 0f;
                // Frame count and every timestamp come from integer maths on the frame
                // index, so nothing drifts however long the timeline is.
                int totalFrames = (int) Math.max(1, Math.ceil(timelineDuration * frameRate - 1e-6));

                report(listener, "OpenGL: compositing " + totalFrames + " frames at " + width + "x" + height + " @" + frameRate + "fps");

                int frameIndex = 0;
                for (; frameIndex < totalFrames; frameIndex++) {
                    if (cancelled) {
                        report(listener, "OpenGL: export cancelled");
                        break;
                    }

                    long outputTimeUs = Math.round(frameIndex * 1_000_000.0 / frameRate);
                    float outputTimeSeconds = (float) (outputTimeUs / 1_000_000.0);

                    List<OpenGLEdit.DrawCommand> commands = edit.computeFrameForTimestamp(timeline, outputTimeSeconds, width, height);

                    // Close sources for clips no longer active this frame - they
                    // don't recur (clips don't loop/repeat within a track).
                    java.util.Set<EditingActivity.Clip> stillActive =
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    for (OpenGLEdit.DrawCommand cmd : commands) stillActive.add(cmd.clip);
                    java.util.Iterator<java.util.Map.Entry<EditingActivity.Clip, ClipFrameSource>> it = activeSources.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<EditingActivity.Clip, ClipFrameSource> entry = it.next();
                        if (!stillActive.contains(entry.getKey())) {
                            entry.getValue().release();
                            it.remove();
                        }
                    }

                    encoder.makeEncoderSurfaceCurrent();
                    shader.beginFrame(width, height);

                    for (OpenGLEdit.DrawCommand cmd : commands) {
                        ClipFrameSource source = activeSources.get(cmd.clip);
                        if (source == null) {
                            source = new ClipFrameSource(cmd.clip.getAbsolutePath(projectPath));
                            try {
                                source.open();
                            } catch (IOException | RuntimeException e) {
                                if (reportedNoFrame.add(cmd.clip)) {
                                    report(listener, "OpenGL: could not open a clip, it will be missing from the export: " + e.getMessage());
                                }
                                continue;
                            }
                            activeSources.put(cmd.clip, source);
                        }

                        long localSourceTimeUs = Math.round(cmd.localSourceTimeSeconds * 1_000_000.0);
                        if (!source.advanceToTime(localSourceTimeUs, timeoutUsPerStep)) {
                            // Only when the decoder never produced a single frame.
                            // Reported once per clip, not once per frame.
                            if (reportedNoFrame.add(cmd.clip)) {
                                report(listener, "OpenGL: a clip produced no frames (from output t=" + outputTimeSeconds + "s)");
                            }
                            continue;
                        }

                        shader.drawClip(source.getTextureId(), source.getTexTransformMatrix(), cmd.mvpMatrix, cmd.opacity);
                    }

                    encoder.swapAndPresent(outputTimeUs * 1000L);

                    if (listener != null) {
                        if (frameIndex % 5 == 0) listener.onProgress(frameIndex, totalFrames);
                        if (frameIndex % 30 == 0 && frameIndex > 0) {
                            listener.onLog("OpenGL: frame " + frameIndex + "/" + totalFrames + " (t=" + String.format(java.util.Locale.US, "%.2f", outputTimeSeconds) + "s)");
                        }
                    }
                }

                if (listener != null) listener.onProgress(frameIndex, totalFrames);
                report(listener, "OpenGL: timeline export finished, " + frameIndex + " frames -> " + outputPath);
            } catch (IOException e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            } finally {
                for (ClipFrameSource source : activeSources.values()) {
                    source.release();
                }
                encoder.close();
            }
        });
    }

    /** Persistent log always; on-screen log too when a listener is attached. */
    private void report(ExportListener listener, String message) {
        LoggingManager.LogToPersistentDataPath(context, "OpenGLEditNative: " + message);
        if (listener != null) listener.onLog(message);
    }
}
