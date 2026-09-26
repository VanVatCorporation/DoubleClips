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

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Android-specific half of OpenGLEdit. Owns the headless EGL context used for
 * export rendering (no on-screen Surface/View involved — this is not the preview
 * path), and per-clip MediaCodec decode -> GL texture plumbing.
 *
 * Step 1-3 of the export plan: frame-in, frame-out, and a single-clip passthrough
 * driver to validate them end to end. Multi-clip scheduling, effects/transitions,
 * and color grading are separate steps built on top of this (see PLAN.md).
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
         * Advances decode until a frame at/after targetTimeUs has been pushed to
         * the texture, then calls updateTexImage() so textureId holds that frame.
         * Deterministic/blocking on purpose — export correctness matters more
         * than throughput here, unlike live preview.
         *
         * Returns false if the stream ended before reaching targetTimeUs.
         */
        public boolean advanceToTime(long targetTimeUs, long timeoutUsPerStep) {
            synchronized (frameLock) {
                frameAvailable = false;
            }

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    int inputIndex = decoder.dequeueInputBuffer(timeoutUsPerStep);
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

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outputIndex = decoder.dequeueOutputBuffer(info, timeoutUsPerStep);
                if (outputIndex >= 0) {
                    boolean isEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    // render=true pushes the frame to the Surface -> SurfaceTexture
                    decoder.releaseOutputBuffer(outputIndex, info.presentationTimeUs >= 0);

                    if (info.presentationTimeUs >= targetTimeUs) {
                        waitForFrameAvailable();
                        surfaceTexture.updateTexImage();
                        return true;
                    }
                    if (isEos) {
                        sawOutputEos = true;
                    }
                }
            }
            return false;
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


    // ---- Step 3: single-clip passthrough sanity test ---------------------------

    /**
     * Decodes inputClipPath from its start, draws each frame through
     * PassthroughShader (no transform/color/FX — that's steps 5/7), and encodes
     * to outputPath as a video-only mp4 via ExportEncoder.
     *
     * Purpose: prove frame-in -> GL -> frame-out works end to end before adding
     * any compositing complexity (step 4) or effects (step 5). Ignores Clip/
     * trim/timeline data entirely on purpose — plays the whole source clip
     * through untouched. If outputPath plays back correctly and matches
     * inputClipPath visually, steps 1+2 are validated.
     *
     * Must be called after start(). Blocks until export completes.
     */
    public void exportSingleClipPassthrough(String inputClipPath, String outputPath,
                                            int width, int height, int bitrate, int frameRate) {
        runOnGlThreadAndWait(() -> {
            ClipFrameSource source = new ClipFrameSource(inputClipPath);
            PassthroughShader shader = new PassthroughShader();
            ExportEncoder encoder = new ExportEncoder();

            try {
                source.open();
                shader.init();
                encoder.open(width, height, bitrate, frameRate, /*iFrameIntervalSeconds*/ 1, outputPath);

                long frameDurationUs = Math.round(1_000_000.0 / frameRate);
                long timeoutUsPerStep = 100_000; // per dequeue call, not per frame

                int frameIndex = 0;
                while (true) {
                    long targetTimeUs = frameIndex * frameDurationUs;

                    boolean gotFrame = source.advanceToTime(targetTimeUs, timeoutUsPerStep);
                    if (!gotFrame) {
                        LoggingManager.LogToPersistentDataPath(context,
                                "OpenGLEditNative: source EOS/stall at frame " + frameIndex + ", ending export");
                        break;
                    }

                    encoder.makeEncoderSurfaceCurrent();
                    shader.draw(source.getTextureId(), width, height);
                    // Source time == output time for this single-clip sanity test;
                    // once step 4 exists, presentation time is the OUTPUT timeline
                    // position, not the source clip's local time.
                    encoder.swapAndPresent(targetTimeUs * 1000L);

                    frameIndex++;
                }

                LoggingManager.LogToPersistentDataPath(context,
                        "OpenGLEditNative: passthrough export finished, " + frameIndex + " frames -> " + outputPath);
            } catch (IOException e) {
                throw new RuntimeException("Passthrough export failed for " + inputClipPath, e);
            } finally {
                // Order matters: encoder.close() drains + finalizes the mp4 before
                // we tear down the source texture it was reading from.
                encoder.close();
                source.release();
            }
        });
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


    // ---- Minimal passthrough shader: draws an OES texture as a fullscreen quad -
    // Placeholder for step 3 (single-clip sanity test) — no transform/color/FX
    // yet, those come in steps 5 and 7 per the plan. Just proves frame-in ->
    // GL -> frame-out works end to end.

    public static class PassthroughShader {
        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n";

        private static final float[] QUAD_VERTICES = {
                // x, y,      u, v
                -1f, -1f,     0f, 0f,
                 1f, -1f,     1f, 0f,
                -1f,  1f,     0f, 1f,
                 1f,  1f,     1f, 1f,
        };

        private int program;
        private int aPositionLoc;
        private int aTexCoordLoc;
        private int uTextureLoc;
        private java.nio.FloatBuffer vertexBuffer;

        /** Must be called on the GL thread, once, after EGL context is current. */
        public void init() {
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture");

            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4);
            bb.order(java.nio.ByteOrder.nativeOrder());
            vertexBuffer = bb.asFloatBuffer();
            vertexBuffer.put(QUAD_VERTICES);
            vertexBuffer.position(0);
        }

        /** Must be called on the GL thread, with the encoder surface already current. */
        public void draw(int oesTextureId, int viewportWidth, int viewportHeight) {
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);

            vertexBuffer.position(0);
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aPositionLoc);

            vertexBuffer.position(2);
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoordLoc);

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
}
