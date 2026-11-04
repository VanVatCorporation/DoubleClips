package com.vanvatcorporation.doubleclips.utils;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.view.Surface;

import com.vanvatcorporation.doubleclips.constants.Constants;

import java.io.File;
import java.io.IOException;

/**
 * Compatibility layer for the zero-copy MediaCodec decode path (PreviewEngine and
 * OpenGLEditNative). MediaCodec can only decode what the device's codecs support, so
 * files such as ProRes (or a 10-bit / oversized stream the hardware can't take) produce no
 * frames. For those, the video track is converted ONCE by FFmpeg into a hardware-decodable
 * H.264 MP4 stored next to the preview proxy ("PreviewClips/<name>_compat.mp4"), and the
 * decoders read that file instead of the original. Everything else keeps decoding the
 * original directly, so nothing changes for files MediaCodec already handles.
 *
 * The compat file is found purely by naming convention (no new field in the clip JSON),
 * so iOS / Desktop projects that carry the same clip data are unaffected.
 *
 * Audio is not handled here: audio already goes through FFmpeg, which reads any format.
 */
public final class MediaCompat {

    public static final String COMPAT_SUFFIX = "_compat";
    public static final String COMPAT_EXTENSION = ".mp4";

    /** Longest edge of the compat file. Larger sources are scaled down to this. */
    private static final int MAX_LONG_EDGE = 1920;
    /** Target bitrate at 1080p (Mbps); scaled by pixel count, clamped below. */
    private static final int BITRATE_MBPS_1080P = 24;
    private static final int BITRATE_MBPS_MIN = 6;
    private static final int BITRATE_MBPS_MAX = 40;
    /** Short GOP so MediaExtractor.seekTo(PREVIOUS_SYNC) stays cheap while scrubbing. */
    private static final int GOP_FRAMES = 15;

    private MediaCompat() {
    }

    /**
     * True when MediaCodec can decode this file's video on this device: a video track the
     * extractor recognises, a decoder that claims its format, and - the part the capability
     * tables get wrong for 10-bit / HDR streams - a decoder that really configures and starts.
     * False for files MediaExtractor can't open, files with no recognised video track (e.g.
     * ProRes in MOV), and streams no decoder here accepts.
     *
     * Opens a real codec, so call it at import / project load / export, not per frame.
     */
    public static boolean isVideoDecodable(String path) {
        if (path == null || !new File(path).isFile()) return false;
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            int track = selectVideoTrack(extractor);
            return track >= 0 && canDecode(extractor.getTrackFormat(track));
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            extractor.release();
        }
    }

    /**
     * The video track a decoder should read: the first one a decoder here claims, otherwise
     * the first video track, or -1 when there is none. The "first one a decoder claims" matters
     * for Dolby Vision files (iPhone), where the extractor lists a "video/dolby-vision" track
     * ahead of the plain "video/hevc" base layer: taking the first track blindly asks for a
     * Dolby Vision decoder most devices don't have, while the HEVC track plays fine.
     * Cheap (no codec is opened), so the decoders call it on every open.
     */
    public static int selectVideoTrack(MediaExtractor extractor) {
        int firstVideo = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("video/")) continue;
            if (firstVideo < 0) firstVideo = i;
            if (decoderName(format) != null) return i;
        }
        return firstVideo;
    }

    private static String decoderName(MediaFormat format) {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                // Lollipop's findDecoderForFormat rejects a format that carries a frame rate.
                format.setString(MediaFormat.KEY_FRAME_RATE, null);
            }
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Configures and starts the decoder that claims the format, then drops it. */
    private static boolean canDecode(MediaFormat format) {
        String name = decoderName(format);
        if (name == null) return false;
        MediaCodec codec = null;
        SurfaceTexture texture = null;
        Surface surface = null;
        try {
            texture = new SurfaceTexture(0);
            surface = new Surface(texture);
            codec = MediaCodec.createByCodecName(name);
            codec.configure(format, surface, null, 0);
            codec.start();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
            if (surface != null) surface.release();
            if (texture != null) texture.release();
        }
    }

    /** Where the compat file for a clip lives, whether or not it exists yet. */
    public static String compatPath(String projectPath, String clipName) {
        String name = clipName == null ? "" : clipName;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(new File(projectPath, Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY),
                base + COMPAT_SUFFIX + COMPAT_EXTENSION).getAbsolutePath();
    }

    /**
     * The conversion writes to this temp name and is only renamed to {@link #compatPath}
     * once FFmpeg succeeds, so a cancelled or killed conversion never leaves a half-written
     * file that the decoders would pick up.
     */
    public static String tempPath(String compatPath) {
        return compatPath + ".tmp.mp4";
    }

    /** Promotes the finished temp file to the real compat file. */
    public static boolean commit(String compatPath) {
        File tmp = new File(tempPath(compatPath));
        File dest = new File(compatPath);
        if (!tmp.isFile()) return false;
        if (dest.exists() && !dest.delete()) return false;
        return tmp.renameTo(dest);
    }

    /** Drops the temp file of a failed or cancelled conversion. */
    public static void discard(String compatPath) {
        File tmp = new File(tempPath(compatPath));
        if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
            tmp.delete();
    }

    /**
     * The file a MediaCodec decoder should open for a clip: the compat conversion when one
     * has been made, otherwise the original.
     */
    public static String resolveSource(String projectPath, String clipName, String originalPath) {
        String compat = compatPath(projectPath, clipName);
        return new File(compat).isFile() ? compat : originalPath;
    }

    /**
     * FFmpeg arguments that convert the first video track into a MediaCodec-friendly
     * H.264 / yuv420p MP4: video only (audio has its own FFmpeg path), full quality up to
     * 1920 on the long edge, short GOP, high bitrate.
     */
    public static String buildCompatCommand(String inputPath, String outputPath) {
        int[] size = readSize(inputPath);
        int w = size[0], h = size[1];

        String scale = "";
        if (w > 0 && h > 0 && Math.max(w, h) > MAX_LONG_EDGE) {
            scale = w >= h
                    ? "-vf \"scale=" + MAX_LONG_EDGE + ":-2\" "
                    : "-vf \"scale=-2:" + MAX_LONG_EDGE + "\" ";
            // Same rounding ffmpeg will apply, only used for the bitrate estimate.
            if (w >= h) { h = Math.round(h * (MAX_LONG_EDGE / (float) w)); w = MAX_LONG_EDGE; }
            else { w = Math.round(w * (MAX_LONG_EDGE / (float) h)); h = MAX_LONG_EDGE; }
        }

        int mbps = BITRATE_MBPS_1080P;
        if (w > 0 && h > 0) {
            mbps = Math.round(BITRATE_MBPS_1080P * ((w * (long) h) / (1920f * 1080f)));
        }
        mbps = Math.max(BITRATE_MBPS_MIN, Math.min(BITRATE_MBPS_MAX, mbps));

        return "-y -i \"" + inputPath + "\" -map 0:v:0 " + scale
                + "-an -sn -dn -c:v libopenh264 -b:v " + mbps + "M -g " + GOP_FRAMES
                + " -pix_fmt yuv420p -threads 0 \"" + outputPath + "\"";
    }

    /** Coded width/height of the first video track, or {0, 0} when unknown. */
    private static int[] readSize(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String ws = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (ws != null && hs != null) {
                return new int[]{Integer.parseInt(ws), Integer.parseInt(hs)};
            }
        } catch (RuntimeException e) {
            // Unknown size: the caller falls back to the 1080p defaults.
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return new int[]{0, 0};
    }
}
