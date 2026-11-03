package com.vanvatcorporation.doubleclips;

import java.util.Locale;

/**
 * What the export screen says about the file that is about to be made: its video bitrate and its estimated size
 * (the same "Estimated size" as iOS and Desktop: duration x (video + audio bitrate) / 8).
 *
 * <p>Android has two export engines, so the video bitrate comes from one of three places:
 * <ul>
 *   <li>OpenGL engine: MediaCodec gets a target bitrate worked out from the resolution and frame rate
 *       ({@link #openGlBitrate}); the size is close to the estimate.</li>
 *   <li>FFmpeg engine with hardware acceleration: the "Bitrate (HW)" setting ({@code -b:v NM}); close as well.</li>
 *   <li>FFmpeg engine, software encoder: the settings give a quality (CRF), not a bitrate, so the encoder spends what
 *       the picture needs. That case uses a rule of thumb ({@link #bitsPerPixel}) and is marked rough
 *       ({@link #isRough()}).</li>
 * </ul>
 * Plain Java, no Android classes, so it can be unit-tested on the JVM.
 */
public final class ExportEstimate {

    /** The audio is AAC at 128 kbit/s (stereo). */
    public static final int AUDIO_BITS_PER_SECOND = 128_000;

    /** How the video bitrate was found. */
    public enum Basis { OPENGL_BITRATE, HARDWARE_BITRATE, CRF }

    public final Basis basis;
    public final long videoBitsPerSecond;
    public final double durationSeconds;
    /** The estimated size of the file in bytes (video + audio). */
    public final long bytes;

    private ExportEstimate(Basis basis, long videoBitsPerSecond, double durationSeconds) {
        this.basis = basis;
        this.videoBitsPerSecond = videoBitsPerSecond;
        this.durationSeconds = durationSeconds;
        this.bytes = (long) ((videoBitsPerSecond + AUDIO_BITS_PER_SECOND) / 8.0 * Math.max(0.0, durationSeconds));
    }

    /** True when the size is a guess from the quality setting (software encoder) rather than from a set bitrate. */
    public boolean isRough() {
        return basis == Basis.CRF;
    }

    /** The target bitrate the OpenGL export gives MediaCodec: about 0.1 bit per pixel per frame. The one place for this rule. */
    public static int openGlBitrate(int width, int height, int frameRate) {
        double bits = (double) Math.max(1, width) * Math.max(1, height) * Math.max(1, frameRate) * 0.1;
        return (int) Math.min(Integer.MAX_VALUE, bits);
    }

    /**
     * Bits per pixel per frame that x264 spends at a CRF, for ordinary footage: about 0.07 at CRF 23 (a 1080p30 picture then
     * needs about 4.5 Mbit/s), halving every 6 CRF steps. Screen recordings need far less, noisy or fast footage more.
     */
    static double bitsPerPixel(int crf) {
        int c = Math.max(0, Math.min(51, crf));
        return 0.07 * Math.pow(2.0, (23 - c) / 6.0);
    }

    /**
     * @param openGlEngine    true when the OpenGL (GPU) engine is chosen
     * @param hardwareEncoder true when the FFmpeg engine uses the hardware encoder (the settings' bitrate is then the video bitrate)
     * @param bitrateMbps     the settings' bitrate in Mbit/s (FFmpeg + hardware encoder)
     * @param crf             the settings' CRF (FFmpeg + software encoder)
     */
    public static ExportEstimate of(int width, int height, int frameRate, int crf, int bitrateMbps,
                                    boolean openGlEngine, boolean hardwareEncoder, double durationSeconds) {
        if (openGlEngine) {
            return new ExportEstimate(Basis.OPENGL_BITRATE, openGlBitrate(width, height, frameRate), durationSeconds);
        }
        if (hardwareEncoder) {
            return new ExportEstimate(Basis.HARDWARE_BITRATE, Math.max(1, bitrateMbps) * 1_000_000L, durationSeconds);
        }
        double bits = (double) Math.max(1, width) * Math.max(1, height) * Math.max(1, frameRate) * bitsPerPixel(crf);
        return new ExportEstimate(Basis.CRF, (long) bits, durationSeconds);
    }

    /** "12.3 MB" / "1.4 GB" (1024-based, like iOS's ByteCountFormatter .binary); "845 KB", "0 bytes". */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return (value >= 100 || unit == 0 ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value))
                + " " + units[unit];
    }

    /** "1:05" / "1:02:03": minutes and seconds, with hours when there are any. */
    public static String formatDuration(double seconds) {
        long total = Math.max(0, Math.round(seconds));
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** "15 Mbps" / "6.2 Mbps": the bitrate for the summary row. */
    public static String formatMbps(long bitsPerSecond) {
        double mbps = bitsPerSecond / 1_000_000.0;
        return (mbps >= 10 ? String.format(Locale.ROOT, "%.0f", mbps) : String.format(Locale.ROOT, "%.1f", mbps)) + " Mbps";
    }
}
