package com.vanvatcorporation.doubleclips;

import android.graphics.SurfaceTexture;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.vanvatcorporation.doubleclips.utils.MediaCompat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Makes the reversed copy of a whole video file (see {@link ReverseMap}) with MediaCodec and the GPU, no FFmpeg. The same idea as iOS
 * (AVAssetReader/Writer): H.264/HEVC can't be decoded backwards, so the file is decoded FORWARD in small windows from the end:
 * <ol>
 *   <li>the file's index is read once (every frame's time, no decoding);</li>
 *   <li>for each window of W frames, last window first: the decoder is seeked to the key frame before it, decodes forward, and the
 *       frames of the window are copied into a pool of GPU textures (the decoder renders into a SurfaceTexture, one blit per frame,
 *       nothing comes back to the CPU);</li>
 *   <li>the pool is drawn back to front into the encoder's input Surface with the time {@code lastTime - t}: one continuous encode,
 *       so there are no joins.</li>
 * </ol>
 * Audio is decoded to PCM in a temp file, read back in blocks from the end (each block flipped), encoded to AAC, and the two streams are
 * joined by a stream-copy mux. The video encoder is hardware first, then MediaCodec's software one. Everything is written to temp files
 * next to the output and renamed at the end, so an interrupted run never leaves a half-written copy. Run it on a background thread.
 */
public final class ReverseProcessor {

    private static final String TAG = "ReverseProcessor";
    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AAC = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final long TIMEOUT_US = 10_000;

    private ReverseProcessor() {}

    /** Thrown when {@code cancelled} turned true; nothing is left behind. */
    public static final class Cancelled extends IOException {
        public Cancelled() { super("cancelled"); }
    }

    private static void check(BooleanSupplier cancelled) throws Cancelled {
        if (cancelled != null && cancelled.getAsBoolean()) throw new Cancelled();
    }

    // ── reading the frame index ───────────────────────────────────────────

    /** Every frame time of a file's video track, in presentation order, read from the container (nothing is decoded). */
    public static final class FrameScan {
        public final int track;
        public final MediaFormat format;
        public final int width, height, rotation;
        public final long[] times;      // microseconds, ascending, distinct
        public final long stepUs;       // the usual gap between two frames

        FrameScan(int track, MediaFormat format, long[] times, long stepUs) {
            this.track = track;
            this.format = format;
            this.times = times;
            this.stepUs = stepUs;
            this.width = format.getInteger(MediaFormat.KEY_WIDTH);
            this.height = format.getInteger(MediaFormat.KEY_HEIGHT);
            int rot = format.containsKey(MediaFormat.KEY_ROTATION) ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
            this.rotation = (((Math.round(rot / 90f) * 90) % 360) + 360) % 360;
        }

        public double fps() { return stepUs > 0 ? 1_000_000.0 / stepUs : 30.0; }

        /** The video's length in seconds: first to last frame plus one frame. */
        public double lengthSeconds() {
            if (times.length == 0) return -1;
            return ((times[times.length - 1] - times[0]) + stepUs) / 1_000_000.0;
        }
    }

    public static FrameScan scanVideo(String path) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(path);
            int track = MediaCompat.selectVideoTrack(ex);
            if (track < 0) throw new IOException("no video track in " + path);
            ex.selectTrack(track);
            MediaFormat format = ex.getTrackFormat(track);
            long[] buf = new long[4096];
            int n = 0;
            while (true) {
                long t = ex.getSampleTime();
                if (t < 0) break;
                if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
                buf[n++] = t;
                if (!ex.advance()) break;
            }
            if (n == 0) throw new IOException("no frames in " + path);
            long[] sorted = Arrays.copyOf(buf, n);
            Arrays.sort(sorted);
            int u = 0;
            for (int i = 0; i < n; i++) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[u++] = sorted[i];
            long[] times = Arrays.copyOf(sorted, u);

            long step;
            if (u >= 2) {
                long[] gaps = new long[u - 1];
                for (int i = 1; i < u; i++) gaps[i - 1] = times[i] - times[i - 1];
                Arrays.sort(gaps);
                step = gaps[gaps.length / 2];
            } else {
                step = 0;
            }
            if (step <= 0) {
                int fr = format.containsKey(MediaFormat.KEY_FRAME_RATE) ? format.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
                step = 1_000_000L / Math.max(1, fr);
            }
            return new FrameScan(track, format, times, step);
        } finally {
            ex.release();
        }
    }

    // ── the whole job ─────────────────────────────────────────────────────

    /**
     * Writes the reversed copy of {@code sourcePath} to {@code output}. {@code progress} gets 0..1 (from the worker thread);
     * {@code cancelled} is polled often. On any failure nothing is left at {@code output}.
     */
    public static void build(String sourcePath, File output, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
        File dir = output.getParentFile();
        if (dir != null) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        File videoTmp = new File(output.getPath() + ".video.tmp");
        File audioTmp = new File(output.getPath() + ".audio.tmp");
        File pcmTmp = new File(output.getPath() + ".pcm.tmp");
        File partial = new File(output.getPath() + ".partial");
        deleteQuietly(videoTmp, audioTmp, pcmTmp, partial);
        DoubleConsumer report = progress == null ? p -> {} : progress;
        try {
            FrameScan scan = scanVideo(sourcePath);
            check(cancelled);

            boolean haveAudio = false;
            try {
                haveAudio = reverseAudio(sourcePath, pcmTmp, audioTmp, p -> report.accept(p * 0.10), cancelled);
            } catch (Cancelled c) {
                throw c;
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "audio could not be reversed, the copy will be silent", e);
                deleteQuietly(audioTmp);
                haveAudio = false;
            } finally {
                deleteQuietly(pcmTmp);
            }
            check(cancelled);

            reverseVideo(sourcePath, scan, videoTmp, p -> report.accept(0.10 + p * 0.87), cancelled);
            check(cancelled);

            mux(videoTmp, haveAudio ? audioTmp : null, scan.rotation, partial);
            report.accept(0.99);
            if (output.exists() && !output.delete()) throw new IOException("cannot replace " + output);
            if (!partial.renameTo(output)) throw new IOException("cannot move the finished copy into place");
            report.accept(1.0);
        } finally {
            deleteQuietly(videoTmp, audioTmp, pcmTmp, partial);
        }
    }

    private static void deleteQuietly(File... files) {
        for (File f : files) if (f != null && f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ── video ─────────────────────────────────────────────────────────────

    private static void reverseVideo(String source, FrameScan scan, File out, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
        List<String> encoders = encoderNames(AVC);
        if (encoders.isEmpty()) throw new IOException("this device has no H.264 encoder");
        long sourceBitrate = scan.format.containsKey(MediaFormat.KEY_BIT_RATE) ? scan.format.getInteger(MediaFormat.KEY_BIT_RATE) : 0;
        if (sourceBitrate <= 0) {
            double len = Math.max(0.1, scan.lengthSeconds());
            sourceBitrate = (long) (new File(source).length() * 8.0 / len);
        }
        int bitrate = ReverseMap.copyBitrate(sourceBitrate, scan.width, scan.height, scan.fps());

        Exception last = null;
        for (String encoderName : encoders) { // hardware ones first, then MediaCodec's software encoder
            check(cancelled);
            try {
                new VideoPass(source, scan, encoderName, out, bitrate, progress, cancelled).run();
                return;
            } catch (Cancelled c) {
                throw c;
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "reverse with encoder " + encoderName + " failed, trying the next one", e);
                last = e instanceof Exception ? (Exception) e : new IOException(String.valueOf(e));
                deleteQuietly(out);
            }
        }
        throw new IOException("could not reverse the video: " + (last == null ? "" : last.getMessage()), last);
    }

    /** Names of the device's encoders for a type: hardware first, software last. */
    static List<String> encoderNames(String mime) {
        List<String> hardware = new ArrayList<>(), software = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean supports = false;
            for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(mime)) supports = true;
            if (!supports) continue;
            String name = info.getName();
            boolean soft = Build.VERSION.SDK_INT >= 29 ? info.isSoftwareOnly()
                    : (name.startsWith("OMX.google.") || name.startsWith("c2.android."));
            (soft ? software : hardware).add(name);
        }
        hardware.addAll(software);
        return hardware;
    }

    private static final String VERTEX =
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTex = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";
    private static final String FRAGMENT_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vTex); }\n";
    private static final String FRAGMENT_2D =
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D uTex;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vTex); }\n";

    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /** One attempt at the video with one encoder. Owns every codec / GL / EGL object it makes and frees them all. */
    private static final class VideoPass {
        final String source;
        final FrameScan scan;
        final String encoderName;
        final File out;
        final int bitrate;
        final DoubleConsumer progress;
        final BooleanSupplier cancelled;

        final int w, h;
        final long[] times;

        // encoder + muxer
        MediaCodec encoder;
        Surface encoderSurface;
        MediaMuxer muxer;
        int muxTrack = -1;
        boolean muxStarted;
        final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

        // EGL
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        // GL
        int progOes, progPlain;
        int oesTex;
        int[] slotTex;
        int slots;
        int fbo;
        final FloatBuffer quad = floats(-1, -1, 1, -1, -1, 1, 1, 1);
        final FloatBuffer quadTex = floats(0, 0, 1, 0, 0, 1, 1, 1);

        // decoder
        MediaExtractor extractor;
        MediaCodec decoder;
        SurfaceTexture surfaceTexture;
        Surface decoderSurface;
        HandlerThread frameThread;
        final Object frameLock = new Object();
        boolean frameAvailable;
        final float[] texMatrix = new float[16];
        final MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();

        VideoPass(String source, FrameScan scan, String encoderName, File out, int bitrate, DoubleConsumer progress, BooleanSupplier cancelled) {
            this.source = source;
            this.scan = scan;
            this.encoderName = encoderName;
            this.out = out;
            this.bitrate = bitrate;
            this.progress = progress;
            this.cancelled = cancelled;
            this.w = scan.width & ~1;
            this.h = scan.height & ~1;
            this.times = scan.times;
        }

        void run() throws IOException {
            try {
                openEncoder();
                openEgl();
                openGl();
                openDecoder();
                process();
                finishEncoder();
            } finally {
                release();
            }
        }

        // -- encoder --

        void openEncoder() throws IOException {
            MediaFormat fmt = MediaFormat.createVideoFormat(AVC, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, (int) Math.round(scan.fps())));
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createByCodecName(encoderName);
            try {
                MediaFormat high = new MediaFormat(fmt);
                high.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                encoder.configure(high, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException profileRejected) {
                encoder.release();
                encoder = MediaCodec.createByCodecName(encoderName);
                encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encoderSurface = encoder.createInputSurface();
            encoder.start();
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void drainEncoder(boolean endOfStream) throws IOException {
            int stalls = 0;
            while (true) {
                int idx = encoder.dequeueOutputBuffer(encInfo, endOfStream ? TIMEOUT_US : 0);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) return;
                    if (++stalls > 600) throw new IOException("the encoder stopped answering");
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new IOException("encoder format changed twice");
                    muxTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (idx >= 0) {
                    stalls = 0;
                    ByteBuffer data = encoder.getOutputBuffer(idx);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0;
                    if (encInfo.size > 0 && data != null) {
                        if (!muxStarted) throw new IOException("encoder produced data before its format");
                        data.position(encInfo.offset);
                        data.limit(encInfo.offset + encInfo.size);
                        muxer.writeSampleData(muxTrack, data, encInfo);
                    }
                    encoder.releaseOutputBuffer(idx, false);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
            }
        }

        void finishEncoder() throws IOException {
            encoder.signalEndOfInputStream();
            drainEncoder(true);
            if (!muxStarted) throw new IOException("no frames were encoded");
        }

        // -- EGL / GL --

        void openEgl() throws IOException {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) throw new IOException("EGL init failed");
            int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGLExt.EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) throw new IOException("no EGL config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IOException("EGL context failed");
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IOException("EGL surface failed");
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw new IOException("eglMakeCurrent failed");
            EGL14.eglSwapInterval(display, 0);
        }

        void openGl() throws IOException {
            progOes = program(VERTEX, FRAGMENT_OES);
            progPlain = program(VERTEX, FRAGMENT_2D);

            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            oesTex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            int wanted = ReverseMap.windowFrames(w, h);
            slotTex = new int[wanted];
            slots = 0;
            while (slots < wanted) {
                int[] one = new int[1];
                GLES20.glGenTextures(1, one, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, one[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                    GLES20.glDeleteTextures(1, one, 0);
                    break; // the GPU is out of room: work with the frames that fit
                }
                slotTex[slots++] = one[0];
            }
            if (slots < 4) throw new IOException("not enough GPU memory for a " + w + "x" + h + " reverse");
            int[] f = new int[1];
            GLES20.glGenFramebuffers(1, f, 0);
            fbo = f[0];
        }

        // -- decoder --

        void openDecoder() throws IOException {
            extractor = new MediaExtractor();
            extractor.setDataSource(source);
            extractor.selectTrack(scan.track);
            frameThread = new HandlerThread("reverse-frames");
            frameThread.start();
            surfaceTexture = new SurfaceTexture(oesTex);
            surfaceTexture.setOnFrameAvailableListener(st -> {
                synchronized (frameLock) {
                    frameAvailable = true;
                    frameLock.notifyAll();
                }
            }, new Handler(frameThread.getLooper()));
            decoderSurface = new Surface(surfaceTexture);
            String mime = scan.format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(scan.format, decoderSurface, null, 0);
            decoder.start();
        }

        void awaitFrame() throws IOException {
            long deadline = System.currentTimeMillis() + 3000;
            synchronized (frameLock) {
                while (!frameAvailable) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("the decoder rendered no frame");
                    try {
                        frameLock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Cancelled();
                    }
                }
                frameAvailable = false;
            }
        }

        // -- the windows --

        void process() throws IOException {
            int n = times.length;
            long lastTime = times[n - 1];
            boolean[] filled = new boolean[slots];
            int emitted = 0;
            int hi = n - 1;
            while (hi >= 0) {
                check(cancelled);
                int lo = Math.max(0, hi - slots + 1);
                Arrays.fill(filled, false);
                decodeWindow(lo, hi, filled);
                for (int i = hi; i >= lo; i--) {
                    if (!filled[i - lo]) continue;
                    drawSlotToEncoder(i - lo);
                    EGLExt.eglPresentationTimeANDROID(display, eglSurface, (lastTime - times[i]) * 1000L);
                    if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new IOException("eglSwapBuffers failed");
                    drainEncoder(false);
                    emitted++;
                }
                hi = lo - 1;
                progress.accept((double) (n - 1 - hi) / n);
            }
            if (emitted < n * 0.5) throw new IOException("the decoder returned only " + emitted + " of " + n + " frames");
        }

        /** Decodes frames lo..hi (presentation order) forward from the key frame before lo, and copies each into its slot. */
        void decodeWindow(int lo, int hi, boolean[] filled) throws IOException {
            decoder.flush(); // in synchronous mode the codec resumes by itself
            synchronized (frameLock) { frameAvailable = false; }
            extractor.seekTo(times[lo], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long hiTime = times[hi];
            int need = hi - lo + 1, got = 0;
            boolean inputDone = false;
            int idle = 0;
            while (got < need) {
                check(cancelled);
                boolean progressed = false;
                if (!inputDone) {
                    int ii = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (ii >= 0) {
                        ByteBuffer in = decoder.getInputBuffer(ii);
                        int size = in == null ? -1 : extractor.readSampleData(in, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(ii, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        progressed = true;
                    }
                }
                int oi = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                if (oi >= 0) {
                    progressed = true;
                    long pts = decInfo.presentationTimeUs;
                    boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    int idx = Arrays.binarySearch(times, lo, hi + 1, pts);
                    boolean want = idx >= 0 && decInfo.size > 0 && !filled[idx - lo];
                    decoder.releaseOutputBuffer(oi, want);
                    if (want) {
                        awaitFrame();
                        surfaceTexture.updateTexImage();
                        surfaceTexture.getTransformMatrix(texMatrix);
                        drawOesToSlot(idx - lo);
                        filled[idx - lo] = true;
                        got++;
                    }
                    if (eos || pts > hiTime) break; // presentation order: nothing wanted comes after this
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    progressed = true;
                }
                if (progressed) idle = 0;
                else if (++idle > 500) throw new IOException("the decoder stalled");
            }
        }

        // -- drawing --

        void drawOesToSlot(int slot) throws IOException {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, slotTex[slot], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) throw new IOException("framebuffer incomplete");
            GLES20.glViewport(0, 0, w, h);
            drawQuad(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texMatrix);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        void drawSlotToEncoder(int slot) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, w, h);
            drawQuad(progPlain, GLES20.GL_TEXTURE_2D, slotTex[slot], IDENTITY);
        }

        void drawQuad(int program, int target, int texture, float[] matrix) {
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, texture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0);
            int aPos = GLES20.glGetAttribLocation(program, "aPosition");
            int aTex = GLES20.glGetAttribLocation(program, "aTexCoord");
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, quadTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);
            GLES20.glDisableVertexAttribArray(aTex);
        }

        // -- cleanup --

        void release() {
            try { if (decoder != null) { decoder.stop(); } } catch (RuntimeException ignored) {}
            try { if (decoder != null) decoder.release(); } catch (RuntimeException ignored) {}
            try { if (decoderSurface != null) decoderSurface.release(); } catch (RuntimeException ignored) {}
            try { if (surfaceTexture != null) surfaceTexture.release(); } catch (RuntimeException ignored) {}
            if (frameThread != null) frameThread.quitSafely();
            try { if (extractor != null) extractor.release(); } catch (RuntimeException ignored) {}
            if (display != EGL14.EGL_NO_DISPLAY) {
                try {
                    if (slotTex != null) GLES20.glDeleteTextures(slots, slotTex, 0);
                    if (oesTex != 0) GLES20.glDeleteTextures(1, new int[]{oesTex}, 0);
                    if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
                    if (progOes != 0) GLES20.glDeleteProgram(progOes);
                    if (progPlain != 0) GLES20.glDeleteProgram(progPlain);
                } catch (RuntimeException ignored) {}
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            try { if (encoder != null) { encoder.stop(); } } catch (RuntimeException ignored) {}
            try { if (encoder != null) encoder.release(); } catch (RuntimeException ignored) {}
            try { if (encoderSurface != null) encoderSurface.release(); } catch (RuntimeException ignored) {}
            try { if (muxer != null) { if (muxStarted) muxer.stop(); muxer.release(); } } catch (RuntimeException ignored) {}
        }
    }

    private static FloatBuffer floats(float... v) {
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    private static int program(String vertex, String fragment) throws IOException {
        int vs = shader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new IOException("shader link failed: " + log);
        }
        return p;
    }

    private static int shader(int type, String src) throws IOException {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IOException("shader compile failed: " + log);
        }
        return s;
    }

    // ── audio ─────────────────────────────────────────────────────────────

    /** Decodes the audio to 16-bit PCM, flips it block by block, encodes AAC into {@code out}. False when the file has no audio track. */
    private static boolean reverseAudio(String src, File pcmTmp, File out, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(src);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; break; }
            }
            if (track < 0) return false;
            ex.selectTrack(track);
            MediaFormat inFmt = ex.getTrackFormat(track);
            long durUs = inFmt.containsKey(MediaFormat.KEY_DURATION) ? inFmt.getLong(MediaFormat.KEY_DURATION) : 0;
            int rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int srcCh = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean isFloat = false;

            dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME));
            dec.configure(inFmt, null, null, 0);
            dec.start();

            // 1. decode everything to PCM16 (at most two channels) in a temp file
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try (BufferedOutputStream pcm = new BufferedOutputStream(new FileOutputStream(pcmTmp), 1 << 16)) {
                boolean inputDone = false, outDone = false;
                int idle = 0;
                while (!outDone) {
                    check(cancelled);
                    boolean progressed = false;
                    if (!inputDone) {
                        int ii = dec.dequeueInputBuffer(TIMEOUT_US);
                        if (ii >= 0) {
                            ByteBuffer in = dec.getInputBuffer(ii);
                            int size = in == null ? -1 : ex.readSampleData(in, 0);
                            if (size < 0) {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                dec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                            progressed = true;
                        }
                    }
                    int oi = dec.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = dec.getOutputFormat();
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                        progressed = true;
                    } else if (oi >= 0) {
                        progressed = true;
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        if (ob != null && info.size > 0) {
                            ob.order(ByteOrder.LITTLE_ENDIAN);
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            writePcm16(pcm, ob, srcCh, isFloat);
                        }
                        dec.releaseOutputBuffer(oi, false);
                        if (durUs > 0) progress.accept(0.5 * Math.min(1.0, info.presentationTimeUs / (double) durUs));
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    }
                    if (progressed) idle = 0;
                    else if (++idle > 1000) throw new IOException("the audio decoder stalled");
                }
            }
            dec.stop();
            dec.release();
            dec = null;

            int outCh = Math.min(2, Math.max(1, srcCh));
            int frameBytes = outCh * 2;
            long totalFrames = pcmTmp.length() / frameBytes;
            if (totalFrames <= 0) return false;

            // 2. read it back from the end, flip each block, encode AAC
            MediaFormat ef = MediaFormat.createAudioFormat(AAC, rate, outCh);
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 96_000 * outCh);
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
            enc = MediaCodec.createEncoderByType(AAC);
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = -1;

            final int blockFrames = 2048;
            byte[] block = new byte[blockFrames * frameBytes];
            byte[] flipped = new byte[blockFrames * frameBytes];
            long remaining = totalFrames, outFrames = 0;
            boolean sentEos = false, encDone = false;
            int idle = 0;
            try (RandomAccessFile raf = new RandomAccessFile(pcmTmp, "r")) {
                while (!encDone) {
                    check(cancelled);
                    boolean progressed = false;
                    if (!sentEos) {
                        int ii = enc.dequeueInputBuffer(TIMEOUT_US);
                        if (ii >= 0) {
                            progressed = true;
                            ByteBuffer ib = enc.getInputBuffer(ii);
                            ib.clear();
                            long ptsUs = outFrames * 1_000_000L / rate;
                            if (remaining <= 0) {
                                enc.queueInputBuffer(ii, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sentEos = true;
                            } else {
                                int take = (int) Math.min(Math.min(blockFrames, remaining), ib.capacity() / frameBytes);
                                raf.seek((remaining - take) * frameBytes);
                                raf.readFully(block, 0, take * frameBytes);
                                for (int f = 0; f < take; f++) System.arraycopy(block, (take - 1 - f) * frameBytes, flipped, f * frameBytes, frameBytes);
                                ib.put(flipped, 0, take * frameBytes);
                                enc.queueInputBuffer(ii, 0, take * frameBytes, ptsUs, 0);
                                outFrames += take;
                                remaining -= take;
                                progress.accept(0.5 + 0.5 * (1.0 - remaining / (double) totalFrames));
                            }
                        }
                    }
                    MediaCodec.BufferInfo oi = new MediaCodec.BufferInfo();
                    int idx = enc.dequeueOutputBuffer(oi, TIMEOUT_US);
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                        progressed = true;
                    } else if (idx >= 0) {
                        progressed = true;
                        ByteBuffer ob = enc.getOutputBuffer(idx);
                        if ((oi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) oi.size = 0;
                        if (oi.size > 0 && muxStarted && ob != null) {
                            ob.position(oi.offset);
                            ob.limit(oi.offset + oi.size);
                            mux.writeSampleData(muxTrack, ob, oi);
                        }
                        enc.releaseOutputBuffer(idx, false);
                        if ((oi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
                    }
                    if (progressed) idle = 0;
                    else if (++idle > 1000) throw new IOException("the audio encoder stalled");
                }
            }
            if (!muxStarted) return false;
            mux.stop();
            muxStarted = false;
            return true;
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (RuntimeException ignored) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (RuntimeException ignored) {}
            try { if (mux != null) { if (muxStarted) mux.stop(); mux.release(); } } catch (RuntimeException ignored) {}
            ex.release();
        }
    }

    /** Writes the decoder's buffer as little-endian 16-bit, keeping the first two channels. */
    private static void writePcm16(BufferedOutputStream out, ByteBuffer in, int srcCh, boolean isFloat) throws IOException {
        int bytesPerSample = isFloat ? 4 : 2;
        int frames = in.remaining() / (bytesPerSample * srcCh);
        int outCh = Math.min(2, srcCh);
        byte[] chunk = new byte[frames * outCh * 2];
        int o = 0;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < srcCh; c++) {
                short s;
                if (isFloat) {
                    float v = in.getFloat();
                    s = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
                } else {
                    s = in.getShort();
                }
                if (c < outCh) {
                    chunk[o++] = (byte) (s & 0xFF);
                    chunk[o++] = (byte) ((s >> 8) & 0xFF);
                }
            }
        }
        out.write(chunk, 0, o);
    }

    // ── joining video and audio ───────────────────────────────────────────

    /** Stream-copies the reversed video (and audio, when there is some) into one mp4 and keeps the source's rotation. */
    private static void mux(File video, File audio, int rotation, File out) throws IOException {
        MediaExtractor vEx = new MediaExtractor(), aEx = audio == null ? null : new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            vEx.setDataSource(video.getPath());
            vEx.selectTrack(0);
            if (aEx != null) {
                aEx.setDataSource(audio.getPath());
                aEx.selectTrack(0);
            }
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotation);
            int vTrack = muxer.addTrack(vEx.getTrackFormat(0));
            int aTrack = aEx == null ? -1 : muxer.addTrack(aEx.getTrackFormat(0));
            muxer.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocateDirect(1 << 21);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                long vt = vEx.getSampleTime();
                long at = aEx == null ? -1 : aEx.getSampleTime();
                if (vt < 0 && at < 0) break;
                boolean useVideo = vt >= 0 && (at < 0 || vt <= at);
                MediaExtractor src = useVideo ? vEx : aEx;
                long size = src.getSampleSize();
                if (size > buf.capacity()) buf = ByteBuffer.allocateDirect((int) size + 1024);
                buf.clear();
                int n = src.readSampleData(buf, 0);
                if (n < 0) break;
                info.set(0, n, src.getSampleTime(), (src.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(useVideo ? vTrack : aTrack, buf, info);
                src.advance();
            }
            muxer.stop();
            started = false;
        } finally {
            try { if (muxer != null) { if (started) muxer.stop(); muxer.release(); } } catch (RuntimeException ignored) {}
            vEx.release();
            if (aEx != null) aEx.release();
        }
    }
}
