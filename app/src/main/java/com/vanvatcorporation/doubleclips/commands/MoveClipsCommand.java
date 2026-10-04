package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves one or MANY clips as a single undo step (a group drag, or the "paste" half of a cut).
 *
 * Every clip carries its own old/new start time and track. If a target track does not exist
 * yet (the group was dropped below the last track) the missing tracks are created on execute
 * and removed again on undo (only while they are still empty).
 */
public class MoveClipsCommand implements CommandUtils.Command {

    public static final class Entry {
        public final EditingActivity.Clip clip;
        public final float oldStart, newStart;
        public final int oldTrack, newTrack;

        public Entry(EditingActivity.Clip clip, float oldStart, float newStart, int oldTrack, int newTrack) {
            this.clip = clip;
            this.oldStart = oldStart;
            this.newStart = newStart;
            this.oldTrack = oldTrack;
            this.newTrack = newTrack;
        }
    }

    private final EditingActivity activity;
    private final List<Entry> entries;
    private int createdTracks = 0;

    public MoveClipsCommand(EditingActivity activity, List<Entry> entries) {
        this.activity = activity;
        this.entries = new ArrayList<>(entries);
    }

    @Override
    public void execute() {
        int needed = 0;
        for (Entry e : entries) needed = Math.max(needed, e.newTrack + 1);
        createdTracks = TrackGrowth.ensureTracks(activity, needed);
        apply(true);
    }

    @Override
    public void undo() {
        apply(false);
        TrackGrowth.dropTrailingEmptyTracks(activity, createdTracks);
        createdTracks = 0;
        activity.afterGroupEdit();
    }

    /** Two phases (take everything out, then put everything in) so one clip's move never disturbs another's. */
    private void apply(boolean forward) {
        for (Entry e : entries) {
            activity.timeline.tracks.get(e.clip.trackIndex).removeClip(e.clip);
        }
        Set<Integer> touched = new HashSet<>();
        for (Entry e : entries) {
            e.clip.startTime = forward ? e.newStart : e.oldStart;
            int target = forward ? e.newTrack : e.oldTrack;
            activity.timeline.tracks.get(target).addClip(e.clip); // also sets clip.trackIndex
            touched.add(target);
        }
        for (int t : touched) activity.timeline.tracks.get(t).sortClips();
        for (Entry e : entries) activity.placeClipUi(e.clip);
        if (forward) activity.afterGroupEdit();
    }

    @Override
    public String toString() {
        return entries.size() == 1
                ? "Move Clip: " + entries.get(0).clip.getClipName()
                : "Move " + entries.size() + " Clips";
    }
}
