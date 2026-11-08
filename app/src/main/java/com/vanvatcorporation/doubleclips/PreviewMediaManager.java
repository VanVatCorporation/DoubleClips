package com.vanvatcorporation.doubleclips;

import android.content.Context;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.Clip;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Makes the preview files a project is missing (see {@link PreviewMedia}) in the background, one file at a time, and reports to
 * {@link ClipProcessingTracker} exactly like the reversed copies do: every clip that waits for a file shows "Applying Proxy...
 * 0.0%" at once, then the real percentage, in the timeline, the properties and the badge over the preview. Nothing blocks the
 * editor; the preview plays the originals (and no sound for a clip whose preview audio isn't there yet) until a file is made.
 * <p>
 * When a project is entered, a file is imported or "Use proxy" is switched on, the editor calls {@link #sync}: it scans OFF the UI
 * thread, then queues what is missing. Calling it again while files are being made is harmless: a file already queued or running is
 * not queued twice (a clip that newly waits for it just joins).
 * <p>
 * All methods run on the UI thread; ffmpeg's callbacks hop back to it through {@link Host#runOnUi}.
 */
public final class PreviewMediaManager {

    public interface Host {
        /** Runs {@code r} on the UI thread. */
        void runOnUi(Runnable r);

        /** Every queued file is done (UI thread). {@code anyMade}: at least one was made, so the preview should be reloaded. */
        void onBatchDone(boolean anyMade);

        /** A file could not be made (UI thread); the clips keep playing the originals. */
        void onFailed(PreviewMedia.Need need, String why);

        /** Some clips have no original any more, so nothing can be made for them (UI thread, once per scan). */
        void onMissingSources(int clips);
    }

    /** One file being made, with the tracker job of every clip waiting for it. */
    private static final class Run {
        final PreviewMedia.Need need;
        final Map<Clip, ClipProcessingTracker.Job> jobs = new IdentityHashMap<>();

        Run(PreviewMedia.Need need) { this.need = need; }
    }

    private final Context context;
    private final Supplier<String> projectPath;
    private final FFmpegEdit.FfmpegRenderQueue queue;
    private final ClipProcessingTracker tracker;
    private final Host host;
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "preview-media-scan");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Run> inFlight = new HashMap<>();
    private int pending;
    private boolean anyMade;
    private boolean destroyed;

    public PreviewMediaManager(Context context, Supplier<String> projectPath, FFmpegEdit.FfmpegRenderQueue queue,
                               ClipProcessingTracker tracker, Host host) {
        this.context = context;
        this.projectPath = projectPath;
        this.queue = queue;
        this.tracker = tracker == null ? ClipProcessingTracker.get() : tracker;
        this.host = host;
    }

    /** The editor is closing: nothing more is queued, and what is running finishes quietly. */
    public void destroy() {
        destroyed = true;
        scanner.shutdownNow();
    }

    /** True while at least one file is queued or being made. */
    public boolean isBusy() { return pending > 0; }

    /** The feature a file kind reports under (what the user reads: "Applying Proxy... 52.6%"). */
    public static String featureOf(PreviewMedia.Kind kind) {
        switch (kind) {
            case PROXY: return ClipProcessingTracker.PROXY;
            case COMPAT: return ClipProcessingTracker.COMPATIBLE_COPY;
            default: return ClipProcessingTracker.PREVIEW_AUDIO;
        }
    }

    // ------------------------------------------------------------------ queueing

    /**
     * Makes whatever is missing for these clips. {@code useProxy} decides whether missing video proxies count (preview audio and
     * compatibility copies always do). Returns at once; the scan runs in the background.
     */
    public void sync(Collection<Clip> clips, boolean useProxy) {
        if (destroyed || clips == null || clips.isEmpty()) return;
        final List<Clip> snapshot = new ArrayList<>(clips);
        final String project = projectPath.get();
        try {
            scanner.execute(() -> {
                PreviewMedia.Scan scan;
                try {
                    scan = PreviewMedia.scan(project, snapshot, useProxy);
                } catch (RuntimeException e) {
                    return; // an unreadable project file: nothing to queue, the next entry scans again
                }
                host.runOnUi(() -> enqueueAll(scan));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // destroyed meanwhile
        }
    }

    public void syncClip(Clip clip, boolean useProxy) {
        if (clip == null) return;
        List<Clip> one = new ArrayList<>();
        one.add(clip);
        sync(one, useProxy);
    }

    private void enqueueAll(PreviewMedia.Scan scan) {
        if (destroyed) return;
        if (scan.clipsWithoutSource > 0) host.onMissingSources(scan.clipsWithoutSource);
        if (scan.needs.isEmpty()) return;
        File dir = PreviewMedia.directory(projectPath.get());
        if (!dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

        for (PreviewMedia.Need need : scan.needs) {
            String key = need.kind + "|" + need.target.getPath();
            Run existing = inFlight.get(key);
            if (existing != null) {
                // Already queued or running: the clips that newly wait for it just join.
                for (Clip clip : need.clips) {
                    if (!existing.jobs.containsKey(clip)) existing.jobs.put(clip, tracker.begin(clip, featureOf(need.kind)));
                }
                continue;
            }
            final Run run = new Run(need);
            for (Clip clip : need.clips) run.jobs.put(clip, tracker.begin(clip, featureOf(need.kind))); // shown at 0.0 % right away
            inFlight.put(key, run);
            pending++;
            queue.enqueue(new FFmpegEdit.FfmpegRenderQueue.FfmpegRenderQueueInfo(taskName(need), () -> start(run)));
        }
    }

    private static String taskName(PreviewMedia.Need need) {
        switch (need.kind) {
            case PROXY: return "Proxy Generation";
            case COMPAT: return "Compatibility Conversion";
            default: return "Preview Audio Generation";
        }
    }

    // ------------------------------------------------------------------ running

    private void start(Run run) {
        final PreviewMedia.Need need = run.need;
        final String cmd = need.command();
        need.discard(); // a leftover of a run that was killed
        try {
            LoggingManager.LogToPersistentDataPath(context, cmd);
        } catch (RuntimeException ignored) {
            // logging must never stop the work
        }
        final double totalMs = Math.max(1.0, need.sourceSeconds * 1000.0);
        final StringBuilder lastLog = new StringBuilder();
        try {
            FFmpegKit.executeAsync(cmd, session -> {
                boolean ok = ReturnCode.isSuccess(session.getReturnCode()) && need.commit();
                if (!ok) need.discard();
                final String why = ok ? "" : (lastLog.length() > 0 ? lastLog.toString() : "ffmpeg returned " + session.getReturnCode());
                host.runOnUi(() -> complete(run, ok, why));
            }, log -> {
                String m = log.getMessage();
                if (m != null && !m.trim().isEmpty()) {
                    lastLog.setLength(0);
                    lastLog.append(m.trim());
                }
            }, statistics -> {
                if (statistics.getTime() > 0) {
                    double fraction = Math.min(0.99, statistics.getTime() / totalMs); // 100 % is the commit, not the last frame
                    for (ClipProcessingTracker.Job job : run.jobs.values()) job.progress(fraction);
                }
            });
        } catch (RuntimeException e) {
            need.discard();
            host.runOnUi(() -> complete(run, false, String.valueOf(e.getMessage())));
        }
    }

    private void complete(Run run, boolean ok, String why) {
        inFlight.remove(run.need.kind + "|" + run.need.target.getPath());
        for (ClipProcessingTracker.Job job : run.jobs.values()) {
            if (ok) job.finish(); else job.fail(why);
        }
        if (ok) anyMade = true;
        else if (!destroyed) host.onFailed(run.need, why);

        pending = Math.max(0, pending - 1);
        boolean last = pending == 0;
        boolean made = anyMade;
        if (last) anyMade = false;
        queue.taskCompleted(); // starts the next file, if any
        if (last && !destroyed) host.onBatchDone(made);
    }
}
