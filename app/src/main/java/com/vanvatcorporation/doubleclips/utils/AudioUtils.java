package com.vanvatcorporation.doubleclips.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.util.Objects;

public class AudioUtils {

    /** How finely a clip's loudness is sampled for the timeline band (levels per source second); zoom never needs more. */
    public static final int WAVEFORM_LEVELS_PER_SECOND = 200;
    /** A very long clip is sampled more coarsely rather than growing the array without limit (400k floats = 1.6 MB). */
    private static final int MAX_WAVEFORM_LEVELS = 400_000;

    /** A clip's loudness, decoded once: {@code levels} cover {@code spanSec} source seconds starting at {@code startSec}. */
    public static final class WaveformLevels {
        public final float[] levels;
        public final float startSec, spanSec;

        WaveformLevels(float[] levels, float startSec, float spanSec) {
            this.levels = levels;
            this.startSec = startSec;
            this.spanSec = spanSec;
        }
    }

    /**
     * The sound of a clip for its timeline waveform. Unlike the old pre-drawn bitmaps (capped at 4096 px, then stretched when
     * zooming) this is zoom independent: {@code WaveformBandView} draws its bars from these levels at whatever width the clip has,
     * following the clip's trim, its speed and its volume (like iOS). The WHOLE source is decoded (up to an hour of it), so
     * trimming further out, changing the speed or re-trimming never needs another decode; a longer source only covers the clip's
     * part. Throws when the file has no audio track (the caller then shows no waveform).
     */
    public static WaveformLevels decodeWaveformLevels(String filePath, EditingActivity.Clip clip) throws Exception {
        float startSec, spanSec;
        if (clip.originalDuration >= 1f && clip.originalDuration <= 3600f) {
            startSec = 0f;
            spanSec = clip.originalDuration;
        } else {
            // Unknown or very long source: just what the clip plays now (the source seconds its timeline length uses).
            startSec = Math.max(0f, clip.startClipTrim);
            spanSec = Math.max(0.05f, (float) clip.timeMap().sourceOffset(Math.max(0.05f, clip.duration)));
        }
        int count = (int) Math.max(1, Math.min(MAX_WAVEFORM_LEVELS, Math.ceil(spanSec * (double) WAVEFORM_LEVELS_PER_SECOND)));
        return new WaveformLevels(decodeBarLevels(filePath, startSec, spanSec, count), startSec, spanSec);
    }

    /**
     * Loudness (RMS, 0..1) of each of {@code barCount} equal slices of {@code spanSec} seconds of the file's audio from {@code startSec}. The samples are folded into the
     * bars as they are decoded, so memory doesn't grow with the clip's length (a long video decodes as cheaply as a short one).
     */
    static float[] decodeBarLevels(String filePath, float startSec, float spanSec, int barCount) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        android.media.MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            int audioTrack = TimelineUtils.findMediaTrackIndex(extractor, EditingActivity.ClipType.AUDIO);
            extractor.selectTrack(audioTrack);
            MediaFormat format = extractor.getTrackFormat(audioTrack);
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 2;
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? Math.max(8000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE)) : 44100;

            long startUs = (long) (startSec * 1_000_000L);
            long spanUs = Math.max(1L, (long) (spanSec * 1_000_000L));
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
}
