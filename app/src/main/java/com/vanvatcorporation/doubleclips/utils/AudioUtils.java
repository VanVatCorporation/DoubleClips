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
     * Decode audio samples from [filePath] and render a waveform Bitmap sized to the clip.
     * Width  = clip.duration * pixelsPerSecond (clamped 64…4096)
     * Height = TRACK_HEIGHT - 4  (same as clip view height)
     *
     * Visual style: dark navy background, teal/blue symmetric bars centred on midline.
     * Inspired by CapCut / Adobe Premiere.
     */
    public static Bitmap generateAudioWaveformBitmap(String filePath, EditingActivity.Clip clip,
                                                      float pixelsPerSecond, int trackHeight)
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
        final int BAR_WIDTH  = 1;   // px default: 3
        final int BAR_GAP    = 0;   // px between bars default: 1
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
