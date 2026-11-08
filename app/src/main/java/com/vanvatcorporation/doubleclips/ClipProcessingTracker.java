package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which clip features are being pre-processed right now, and how far along each is (CapCut's "Applying Motion blur... 52.6%").
 * A feature that can't be shown at once (the optical flow cache of a clip, a reversed copy of its video...) reports here; the
 * editor shows the same state in three places: a badge over the preview canvas, a "Processing xx%" line in the clip's
 * properties and "Applying ... xx%" on the clip in the timeline.
 *
 * <p>A feature is a {@link Job}: {@link #begin} when it starts, {@link Job#progress} as it goes (0 ... 1), {@link Job#finish}
 * or {@link Job#fail} at the end. One job per clip and feature name: beginning it again replaces the older one. The clip is
 * identified by object identity (whatever the caller holds for it), so this class knows nothing about the editor's own types.
 *
 * <p>Plain Java and thread-safe: a job is updated from whatever thread does the work, so the listeners ({@link #addListener})
 * are told on THAT thread and a JavaFX listener must hop to the FX thread itself. A finished job stays visible to
 * {@link #lastFinished()} for {@link #DONE_VISIBLE_MS} so the preview badge can say "applied" for a moment.
 */
public final class ClipProcessingTracker {

    /** How long a finished job still counts as "just finished" (the green "applied" message). */
    public static final long DONE_VISIBLE_MS = 2500;

    public enum State { RUNNING, DONE, FAILED }

    /** The feature names the editor reports under (what the user reads: "Applying Motion blur..."). */
    public static final String OPTICAL_FLOW = "Optical flow", MOTION_BLUR = "Motion blur", REVERSE = "Reverse";
    /** The preview files of PreviewMedia: the small video proxy, the preview sound, and the H.264 copy of a video MediaCodec can't decode. */
    public static final String PROXY = "Proxy", PREVIEW_AUDIO = "Preview audio", COMPATIBLE_COPY = "Compatible copy";

    /** One feature being applied to one clip. */
    public final class Job {
        public final Object clip;
        public final String feature;
        private volatile double progress;
        private volatile State state = State.RUNNING;
        private volatile String message = "";
        private volatile long endedAt;

        private Job(Object clip, String feature) {
            this.clip = clip;
            this.feature = feature;
        }

        /** 0 ... 1, clamped; ignored once the job has ended. */
        public void progress(double fraction) {
            if (state != State.RUNNING) return;
            progress = Math.max(0.0, Math.min(1.0, Double.isNaN(fraction) ? 0.0 : fraction));
            fire();
        }

        public void finish() {
            if (state != State.RUNNING) return;
            progress = 1.0;
            end(State.DONE, "");
        }

        /** The work failed; {@code reason} is what the editor may show. */
        public void fail(String reason) {
            if (state != State.RUNNING) return;
            end(State.FAILED, reason == null ? "" : reason);
        }

        private void end(State s, String why) {
            message = why;
            endedAt = System.currentTimeMillis();
            state = s;
            synchronized (ClipProcessingTracker.this) {
                jobs.remove(this);
                if (s == State.DONE) lastDone = this;
            }
            fire();
        }

        public double progress() { return progress; }
        public State state() { return state; }
        public String message() { return message; }
        public boolean isRunning() { return state == State.RUNNING; }
    }

    private static final ClipProcessingTracker INSTANCE = new ClipProcessingTracker();

    public static ClipProcessingTracker get() { return INSTANCE; }

    private final List<Job> jobs = new ArrayList<>();
    private Job lastDone;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Public so a test can make its own; the editor uses {@link #get()}. */
    public ClipProcessingTracker() {}

    public void addListener(Runnable listener) { if (listener != null) listeners.add(listener); }

    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private void fire() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a broken listener must not stop the job or the other listeners
            }
        }
    }

    /** Starts a job at 0 %. An unfinished job of the same clip and feature is dropped (the new one replaces it). */
    public Job begin(Object clip, String feature) {
        Job job = new Job(clip, feature == null ? "feature" : feature);
        synchronized (this) {
            jobs.removeIf(j -> j.clip == clip && j.feature.equals(job.feature));
            jobs.add(job);
        }
        fire();
        return job;
    }

    /** The clip was deleted or its feature switched off: its unfinished jobs are forgotten (their owners should stop working). */
    public void drop(Object clip) {
        boolean any;
        synchronized (this) {
            any = jobs.removeIf(j -> j.clip == clip);
        }
        if (any) fire();
    }

    /** The jobs that are running, oldest first. */
    public synchronized List<Job> running() {
        return new ArrayList<>(jobs);
    }

    public synchronized int runningCount() { return jobs.size(); }

    /** The running jobs of one clip, oldest first. */
    public synchronized List<Job> runningFor(Object clip) {
        List<Job> out = new ArrayList<>();
        for (Job j : jobs) if (j.clip == clip) out.add(j);
        return out;
    }

    /** The running job of this clip and feature, or null. */
    public synchronized Job find(Object clip, String feature) {
        for (Job j : jobs) if (j.clip == clip && j.feature.equals(feature)) return j;
        return null;
    }

    /** The job that finished last, if it ended less than {@link #DONE_VISIBLE_MS} ago and nothing is running; else null. */
    public synchronized Job lastFinished() {
        if (!jobs.isEmpty() || lastDone == null) return null;
        return System.currentTimeMillis() - lastDone.endedAt <= DONE_VISIBLE_MS ? lastDone : null;
    }

    /** "52.6%", or "100%" at the end. */
    public static String percent(double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        return f >= 1.0 ? "100%" : String.format(Locale.ROOT, "%.1f%%", f * 100.0);
    }

    /**
     * The line the clip shows in the timeline: "Applying Motion blur... 52.6%" for one job, "Applying 2 features... 40.0%" (the
     * average) for several; null when nothing is running for the clip.
     */
    public String clipLabel(Object clip) {
        List<Job> mine = runningFor(clip);
        if (mine.isEmpty()) return null;
        if (mine.size() == 1) return "Applying " + mine.get(0).feature + "... " + percent(mine.get(0).progress());
        return "Applying " + mine.size() + " features... " + percent(average(mine));
    }

    /** "Processing 91.1%" for the clip's properties row; null when nothing is running for the clip. */
    public String propertiesLabel(Object clip) {
        List<Job> mine = runningFor(clip);
        return mine.isEmpty() ? null : "Processing " + percent(average(mine));
    }

    /** The preview badge's title: "Applying 1 feature...", "Applying 3 features..."; null when nothing is running. */
    public String badgeTitle() {
        int n = runningCount();
        if (n == 0) return null;
        return "Applying " + n + (n == 1 ? " feature..." : " features...");
    }

    private static double average(List<Job> list) {
        double sum = 0;
        for (Job j : list) sum += j.progress();
        return list.isEmpty() ? 0 : sum / list.size();
    }
}
