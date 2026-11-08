package com.vanvatcorporation.doubleclips;

import android.media.MediaMetadataRetriever;

import com.vanvatcorporation.doubleclips.activities.EditingActivity.Clip;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.ClipType;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.utils.MediaCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files a project keeps in {@code PreviewClips}, and which of them a project is MISSING.
 * <p>
 * They are all made from the originals in {@code Clips}, so an export or a backup leaves the folder out (see
 * {@link ProjectZip#PREVIEW_DIRECTORY}) and the editor makes them again whenever a project is entered, a file is imported or the
 * "Use proxy" switch is turned on:
 * <ul>
 *   <li><b>{@link Kind#AUDIO}</b> - {@code <name>.wav}: the sound the preview plays. Always needed: an audio clip, and a video that
 *       has sound. (Played without it, the preview would push the original's compressed bytes through the speaker.)</li>
 *   <li><b>{@link Kind#COMPAT}</b> - {@code <name>_compat.mp4}: an H.264 copy of a video MediaCodec can't decode (ProRes, ...).
 *       Always needed for such a video.</li>
 *   <li><b>{@link Kind#PROXY}</b> - {@code <name>.mp4}: the small video-only copy the preview plays. Only wanted while "Use proxy"
 *       is on; with it off the preview plays the originals, so nothing is made.</li>
 * </ul>
 * Several clips can share one source file, so a {@link Need} is per FILE and lists every clip that uses it.
 */
public final class PreviewMedia {

    public enum Kind { AUDIO, PROXY, COMPAT }

    /** One file to make, and the clips that wait for it. */
    public static final class Need {
        public final Kind kind;
        /** The original media in {@code Clips}. */
        public final String original;
        /** The file that is wanted (for {@link Kind#COMPAT}, the real compat path; the work is written beside it first). */
        public final File target;
        /** Seconds of the SOURCE that get processed (progress = processed time / this). */
        public final float sourceSeconds;
        /** The ffmpeg scale for a proxy ("1280:-2" / "-2:720"), unused for the other kinds. */
        public final String proxyScale;
        public final List<Clip> clips = new ArrayList<>();

        Need(Kind kind, String original, File target, float sourceSeconds, String proxyScale) {
            this.kind = kind;
            this.original = original;
            this.target = target;
            this.sourceSeconds = sourceSeconds;
            this.proxyScale = proxyScale;
        }

        /** Where ffmpeg writes before the finished file is renamed into place: a half-written file is never picked up. */
        public File workFile() {
            if (kind == Kind.COMPAT) return new File(MediaCompat.tempPath(target.getPath()));
            String name = target.getName();
            int dot = name.lastIndexOf('.');
            return new File(target.getParentFile(), name.substring(0, dot) + ".tmp" + name.substring(dot));
        }

        public String command() {
            String out = workFile().getPath();
            switch (kind) {
                case AUDIO:
                    return "-i \"" + original + "\" -vn -ac 1 -ar 22050 -c:a pcm_s16le -y \"" + out + "\"";
                case PROXY:
                    return "-i \"" + original + "\" -vf \"scale=" + proxyScale
                            + "\" -c:v libopenh264 -preset ultrafast -crf 32 -g 1 -an -y \"" + out + "\"";
                default:
                    return MediaCompat.buildCompatCommand(original, out);
            }
        }

        /** Renames the finished work file into place. False when it didn't turn out to be a file. */
        public boolean commit() {
            if (kind == Kind.COMPAT) return MediaCompat.commit(target.getPath());
            File work = workFile();
            if (!work.isFile() || work.length() == 0) return false;
            if (target.exists() && !target.delete()) return false;
            return work.renameTo(target);
        }

        /** Drops the work file of a failed run. */
        public void discard() {
            //noinspection ResultOfMethodCallIgnored
            workFile().delete();
        }
    }

    /** What a scan found: the files to make, and how many clips can't be served because their original is gone. */
    public static final class Scan {
        public final List<Need> needs;
        public final int clipsWithoutSource;

        Scan(List<Need> needs, int clipsWithoutSource) {
            this.needs = needs;
            this.clipsWithoutSource = clipsWithoutSource;
        }

        public int count(Kind kind) {
            int n = 0;
            for (Need need : needs) if (need.kind == kind) n++;
            return n;
        }

        /** The number of CLIPS (not files) that wait for this kind. */
        public int clipCount(Kind kind) {
            int n = 0;
            for (Need need : needs) if (need.kind == kind) n += need.clips.size();
            return n;
        }
    }

    private PreviewMedia() { }

    // ------------------------------------------------------------------ paths

    private static String baseName(String clipName) {
        String name = clipName == null ? "" : clipName;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static File directory(String projectPath) {
        return new File(projectPath, Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY);
    }

    /** {@code PreviewClips/<name>.wav}, whether or not it exists yet. */
    public static File audioFile(String projectPath, String clipName) {
        return new File(directory(projectPath), baseName(clipName) + Constants.DEFAULT_PREVIEW_CLIP_AUDIO_EXTENSION);
    }

    /** {@code PreviewClips/<name>.mp4}, whether or not it exists yet. */
    public static File proxyFile(String projectPath, String clipName) {
        return new File(directory(projectPath), baseName(clipName) + Constants.DEFAULT_PREVIEW_CLIP_VIDEO_EXTENSION);
    }

    /** A made file: there, and not an empty leftover. */
    public static boolean isMade(File file) {
        return file.isFile() && file.length() > 0;
    }

    /** Whether the clip's preview sound exists (a clip with no sound, or no file behind it, never needs one). */
    public static boolean hasAudioFile(String projectPath, Clip clip) {
        return isMade(audioFile(projectPath, clip.getClipName()));
    }

    // ------------------------------------------------------------------ scanning

    /** Whether a clip has sound the preview has to play. */
    public static boolean wantsAudio(Clip clip) {
        return clip.type == ClipType.AUDIO || (clip.type == ClipType.VIDEO && clip.isClipHasAudio);
    }

    /**
     * What is missing for these clips. Opens a codec to judge a video that has no compat copy, so call it OFF the UI thread.
     *
     * @param useProxy whether "Use proxy" is on: only then are missing video proxies part of the result
     */
    public static Scan scan(String projectPath, Collection<Clip> clips, boolean useProxy) {
        Map<String, Need> found = new LinkedHashMap<>(); // keyed by kind + target: one file, however many clips use it
        Map<String, Boolean> decodable = new LinkedHashMap<>();
        int withoutSource = 0;
        java.util.Set<Clip> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (Clip clip : clips) {
            if (clip == null || (clip.type != ClipType.VIDEO && clip.type != ClipType.AUDIO)) continue;
            String name = clip.getClipName();
            if (name == null || name.isEmpty()) continue;
            String original = clip.getAbsolutePath(projectPath);
            if (!new File(original).isFile()) {
                if (counted.add(clip)) withoutSource++;
                continue;
            }
            float seconds = clip.originalDuration > 0 ? clip.originalDuration : Math.max(clip.duration, 0.1f);

            if (wantsAudio(clip)) {
                File wav = audioFile(projectPath, name);
                if (!isMade(wav)) add(found, new Need(Kind.AUDIO, original, wav, seconds, null), clip);
            }
            if (clip.type != ClipType.VIDEO) continue;

            String compat = MediaCompat.compatPath(projectPath, name);
            if (!new File(compat).isFile()) {
                Boolean ok = decodable.get(original);
                if (ok == null) {
                    ok = MediaCompat.isVideoDecodable(original);
                    decodable.put(original, ok);
                }
                if (!ok) add(found, new Need(Kind.COMPAT, original, new File(compat), seconds, null), clip);
            }
            if (useProxy) {
                File proxy = proxyFile(projectPath, name);
                if (!isMade(proxy)) add(found, new Need(Kind.PROXY, original, proxy, seconds, proxyScale(original)), clip);
            }
        }
        return new Scan(new ArrayList<>(found.values()), withoutSource);
    }

    /** How many clips (not files) are missing their video proxy: what "Use proxy" is about to make. Cheap: no codec is opened. */
    public static int countMissingProxies(String projectPath, Collection<Clip> clips) {
        int n = 0;
        for (Clip clip : clips) {
            if (clip == null || clip.type != ClipType.VIDEO || clip.getClipName() == null) continue;
            if (!new File(clip.getAbsolutePath(projectPath)).isFile()) continue;
            if (!isMade(proxyFile(projectPath, clip.getClipName()))) n++;
        }
        return n;
    }

    private static void add(Map<String, Need> found, Need need, Clip clip) {
        String key = need.kind + "|" + need.target.getPath();
        Need existing = found.get(key);
        if (existing == null) {
            found.put(key, need);
            existing = need;
        }
        existing.clips.add(clip);
    }

    /** A portrait video is scaled to 720 wide-ish on the short edge, a landscape one to 1280 on the long edge (as before). */
    private static String proxyScale(String original) {
        int width = 2, height = 1;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(original);
            String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (w != null && h != null) {
                width = Integer.parseInt(w);
                height = Integer.parseInt(h);
            }
        } catch (Exception ignored) {
            // unreadable size (a codec the platform can't read): the landscape scale is as good a guess as any
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
        return width < height ? "-2:720" : "1280:-2";
    }
}
