package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deletes one or many clips as a single undo step; undo puts each back on the track it came from. */
public class DeleteClipsCommand implements CommandUtils.Command {

    private final EditingActivity activity;
    private final List<EditingActivity.Clip> clips;
    private final int[] trackIndexes;

    public DeleteClipsCommand(EditingActivity activity, List<EditingActivity.Clip> clips) {
        this.activity = activity;
        this.clips = new ArrayList<>(clips);
        this.trackIndexes = new int[this.clips.size()];
        for (int i = 0; i < this.clips.size(); i++) trackIndexes[i] = this.clips.get(i).trackIndex;
    }

    @Override
    public void execute() {
        for (int i = 0; i < clips.size(); i++) {
            activity.timeline.tracks.get(trackIndexes[i]).removeClip(clips.get(i));
            activity.detachClipUi(clips.get(i));
        }
        activity.afterGroupEdit();
    }

    @Override
    public void undo() {
        Set<Integer> touched = new HashSet<>();
        for (int i = 0; i < clips.size(); i++) {
            EditingActivity.Track track = activity.timeline.tracks.get(trackIndexes[i]);
            track.addClip(clips.get(i));
            activity.attachClipUi(track, clips.get(i));
            touched.add(trackIndexes[i]);
        }
        for (int t : touched) activity.timeline.tracks.get(t).sortClips();
        activity.afterGroupEdit();
    }

    @Override
    public String toString() {
        return clips.size() == 1 ? "Delete Clip: " + clips.get(0).getClipName() : "Delete " + clips.size() + " Clips";
    }
}
