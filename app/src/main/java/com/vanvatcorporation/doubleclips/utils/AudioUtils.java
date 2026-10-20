package com.vanvatcorporation.doubleclips.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.util.Objects;

public class AudioUtils {

    /**
     * The sound of a clip as a thin waveform band for the bottom of its timeline thumbnail (iOS draws one on video clips):
     * a translucent dark strip with light bars centred on its middle line. Width = clip.duration * pixelsPerSecond
     * (clamped 64..4096), height = bandHeight. Throws when the file has no audio track (the caller then draws no band).
     */
    public static Bitmap generateWaveformBand(String filePath, EditingActivity.Clip clip,
                                              float pixelsPerSecond, int bandHeight,
                                              int barWidth, int barGap) throws Exception {
        final int bmpW = (int) Math.max(64, Math.min(4096, clip.duration * pixelsPerSecond));
        final int bmpH = Math.max(8, bandHeight);
        final int stride = Math.max(1, barWidth + barGap);
        final int barCount = Math.max(1, bmpW / stride);
        float[] levels = decodeBarLevels(filePath, clip, barCount);

        Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(0x66000000); // translucent: the frames still show through

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        float mid = bmpH / 2f;
        float maxHalf = mid - 1.5f;
        for (int b = 0; b < barCount; b++) {
            // sqrt: quiet passages stay visible, like most editors' waveforms
            float half = Math.max(1f, (float) Math.sqrt(Math.min(1f, levels[b] * 2f)) * maxHalf);
            int alpha = 120 + (int) (120 * Math.min(1f, levels[b] * 2f));
            paint.setColor(android.graphics.Color.argb(alpha, 255, 255, 255));
            float left = b * stride;
            canvas.drawRoundRect(left, mid - half, left + Math.max(1, barWidth), mid + half, 1f, 1f, paint);
        }
        return bmp;
    }

    /**
     * Loudness (RMS, 0..1) of each of {@code barCount} equal slices of the clip's audio. The samples are folded into the
     * bars as they are decoded, so memory doesn't grow with the clip's length (a long video decodes as cheaply as a short one).
     */
    static float[] decodeBarLevels(String filePath, EditingActivity.Clip clip, int barCount) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        android.media.MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            int audioTrack = TimelineUtils.findMediaTrackIndex(extractor, EditingActivity.ClipType.AUDIO);
            extractor.selectTrack(audioTrack);
            MediaFormat format = extractor.getTrackFormat(audioTrack);
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 2;
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? Math.max(8000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE)) : 44100;

            long startUs = (long) (clip.startClipTrim * 1_000_000L);
            long spanUs = Math.max(1L, (long) (clip.duration * 1_000_000L));
            long endUs = startUs + spanUs;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            codec = android.media.MediaCodec.createDecoderByType(Objects.requireNonNull(format.getString(MediaFormat.KEY_MIME)));
            codec.configure(format, null, null, 0);
            codec.start();

            // Looking at every 200th sample or so per bar is plenty for a loudness estimate.
            double framesPerBar = spanUs / 1_000_000.0 * sampleRate / barCount;
            int step = Math.max(1, (int) (framesPerBar / 200.0));

            double[] sumSquares = new double[barCount];
            long[] counts = new long[barCount];
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int idleSpins = 0;

            while (!outputDone && idleSpins < 600) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(5_000);
                    if (inIdx >= 0) {
                        java.nio.ByteBuffer in = codec.getInputBuffer(inIdx);
                        int size = extractor.readSampleData(in, 0);
                        long presentUs = extractor.getSampleTime();
                        if (size < 0 || presentUs > endUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, presentUs, 0);
                            extractor.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 5_000);
                if (outIdx >= 0) {
                    idleSpins = 0;
                    java.nio.ByteBuffer pcm = codec.getOutputBuffer(outIdx);
                    if (pcm != null && info.size > 0) {
                        pcm.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        java.nio.ShortBuffer shorts = pcm.asShortBuffer();
                        int frames = shorts.remaining() / channels;
                        for (int f = 0; f < frames; f += step) {
                            long timeUs = info.presentationTimeUs + f * 1_000_000L / sampleRate;
                            if (timeUs < startUs) continue;
                            if (timeUs >= endUs) break;
                            int bar = (int) Math.min(barCount - 1, (timeUs - startUs) * barCount / spanUs);
                            double sum = 0;
                            for (int c = 0; c < channels; c++) sum += shorts.get(f * channels + c);
                            double mono = sum / channels / 32768.0;
                            sumSquares[bar] += mono * mono;
                            counts[bar]++;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else {
                    idleSpins++; // a decoder that never answers must not hang the thumbnail thread
                }
            }

            float[] levels = new float[barCount];
            for (int b = 0; b < barCount; b++) {
                levels[b] = counts[b] > 0 ? (float) Math.sqrt(sumSquares[b] / counts[b]) : 0f;
            }
            return levels;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
        }
    }


    /**
     * Decode audio samples from [filePath] and render a waveform Bitmap sized to the clip.
     * Width  = clip.duration * pixelsPerSecond (clamped 64…4096)
     * Height = TRACK_HEIGHT - 4  (same as clip view height)
     *
     * Visual style: dark navy background, teal/blue symmetric bars centred on midline.
     * Inspired by CapCut / Adobe Premiere.
     */
    public static Bitmap generateAudioWaveformBitmap(String filePath, EditingActivity.Clip clip,
                                                      float pixelsPerSecond, int trackHeight,
                                                      int barWidth, int barGap)
            throws Exception {

        // ── Dimensions ────────────────────────────────────────────────────────
        // Match the actual clip view width so the bitmap tiles perfectly
        final int bmpW = (int) Math.max(64, Math.min(4096, clip.duration * pixelsPerSecond));
        final int bmpH = trackHeight - 4;

        // ── Decode PCM via MediaExtractor + MediaCodec ────────────────────────
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(filePath);

        int audioTrack = TimelineUtils.findMediaTrackIndex(extractor, EditingActivity.ClipType.AUDIO);
        extractor.selectTrack(audioTrack);
        android.media.MediaFormat audioFormat = extractor.getTrackFormat(audioTrack);

        // Seek to clip start trim so we visualise what the user actually hears
        long startUs = (long)(clip.startClipTrim * 1_000_000L);
        long endUs   = startUs + (long)(clip.duration * 1_000_000L);
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        android.media.MediaCodec codec = android.media.MediaCodec.createDecoderByType(
                Objects.requireNonNull(audioFormat.getString(MediaFormat.KEY_MIME)));
        codec.configure(audioFormat, null, null, 0);
        codec.start();

        // Collect raw PCM samples (16-bit shorts, all channels mixed to mono-magnitude)
        java.util.ArrayList<Short> samples = new java.util.ArrayList<>(64_000);
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        boolean inputDone  = false;
        boolean outputDone = false;
        // WTF? TODO: Display a loading indicator when the preview thumbnail are loading.
//        final long TIMEOUT_US = 3_000_000L; // 3 s overall decode budget
//        long startTime = System.currentTimeMillis();

        while (!outputDone) {
            // WTF?
            //if (System.currentTimeMillis() - startTime > 3000) break; // safety time-out

            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    java.nio.ByteBuffer buf = codec.getInputBuffer(inIdx);
                    int sampleSize = extractor.readSampleData(buf, 0);
                    long presentUs = extractor.getSampleTime();
                    if (sampleSize < 0 || presentUs > endUs) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, presentUs, 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, 5_000);
            if (outIdx >= 0) {
                java.nio.ByteBuffer pcmBuf = codec.getOutputBuffer(outIdx);
                if (pcmBuf != null && info.size > 0) {
                    // Read 16-bit signed PCM (little-endian)
                    pcmBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    while (pcmBuf.remaining() >= 2) {
                        samples.add(pcmBuf.getShort());
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }

        codec.stop();
        codec.release();
        extractor.release();

        // ── Downsample into bars ───────────────────────────────────────────────
        // TODO: Can be change based on user preference -> EditorPreference setting.
        final int BAR_WIDTH  = barWidth;   // px default: 3
        final int BAR_GAP    = barGap;   // px between bars default: 1
        final int STRIDE     = BAR_WIDTH + BAR_GAP;
        final int barCount   = Math.max(1, bmpW / STRIDE);
        float[]   amps       = new float[barCount];

        if (samples.isEmpty()) {
            // Silence — flat line
            java.util.Arrays.fill(amps, 0f);
        } else {
            int samplesPerBar = Math.max(1, samples.size() / barCount);
            for (int b = 0; b < barCount; b++) {
                int start = b * samplesPerBar;
                int end   = Math.min(start + samplesPerBar, samples.size());
                long rms  = 0;
                for (int s = start; s < end; s++) {
                    long v = samples.get(s);
                    rms += v * v;
                }
                amps[b] = (float) Math.sqrt((double) rms / (end - start)) / 32768f; // 0..1
            }
        }

        // ── Draw waveform ──────────────────────────────────────────────────────
        Bitmap bmp    = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Background: dark navy
        canvas.drawColor(0xFF0D1B2A);

        // Baseline (thin horizontal centre line)
        Paint basePaint = new Paint();
        basePaint.setColor(0xFF1E90FF);
        basePaint.setAlpha(60);
        basePaint.setStrokeWidth(1f);
        float midY = bmpH / 2f;
        canvas.drawLine(0, midY, bmpW, midY, basePaint);

        // Bars
        Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);

        final float MAX_HALF = midY - 2f;  // max half-height of a bar

        for (int b = 0; b < barCount; b++) {
            float amp       = amps[b];
            float halfBar   = Math.max(2f, amp * MAX_HALF);  // at least 2px visible
            float left      = b * STRIDE;
            float right     = left + BAR_WIDTH;
            float top       = midY - halfBar;
            float bot       = midY + halfBar;

            // Gradient-like colour: brighter blue for louder bars
//            int alpha   = (int)(180 + 75 * amp);   // 180…255
            int alpha   = (int)(10 + 245 * amp);   // 180…255
            // TODO: Can be equal 255, alpha not change -> EditorPreference setting.
            barPaint.setColor(android.graphics.Color.argb(alpha, 0x1E, 0x90, 0xFF)); // #1E90FF dodger blue
            canvas.drawRoundRect(left, top, right, bot, 1.5f, 1.5f, barPaint);
        }

        return bmp;
    }
}
