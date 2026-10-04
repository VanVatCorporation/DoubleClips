package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Adds one or many clips (e.g. a paste) as a single undo step, creating missing tracks. */
public class AddClipsCommand implements CommandUtils.Command {

    public static final class Entry {
        public final EditingActivity.Clip clip;
        public final int track;

        public Entry(EditingActivity.Clip clip, int track) {
            this.clip = clip;
            this.track = track;
        }
    }

    private final EditingActivity activity;
    private final List<Entry> entries;
    private int createdTracks = 0;

    public AddClipsCommand(EditingActivity activity, List<Entry> entries) {
        this.activity = activity;
        this.entries = new ArrayList<>(entries);
    }

    @Override
    public void execute() {
        int needed = 0;
        for (Entry e : entries) needed = Math.max(needed, e.track + 1);
        createdTracks = TrackGrowth.ensureTracks(activity, needed);
        Set<Integer> touched = new HashSet<>();
        for (Entry e : entries) {
            EditingActivity.Track track = activity.timeline.tracks.get(e.track);
            track.addClip(e.clip); // also sets clip.trackIndex
            activity.attachClipUi(track, e.clip);
            touched.add(e.track);
        }
        for (int t : touched) activity.timeline.tracks.get(t).sortClips();
        activity.afterGroupEdit();
    }

    @Override
    public void undo() {
        for (Entry e : entries) {
            activity.timeline.tracks.get(e.clip.trackIndex).removeClip(e.clip);
            activity.detachClipUi(e.clip);
        }
        TrackGrowth.dropTrailingEmptyTracks(activity, createdTracks);
        createdTracks = 0;
        activity.afterGroupEdit();
    }

    @Override
    public String toString() {
        return entries.size() == 1
                ? "Add Clip: " + entries.get(0).clip.getClipName()
                : "Add " + entries.size() + " Clips";
    }
}
