package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

public class AddClipCommand implements Command {
    private final EditingActivity.Timeline timeline;
    private final EditingActivity.Clip clip;
    private final int trackIndex;
    private final Runnable onUpdate;

    public AddClipCommand(EditingActivity.Timeline timeline, EditingActivity.Clip clip, int trackIndex, Runnable onUpdate) {
        this.timeline = timeline;
        this.clip = clip;
        this.trackIndex = trackIndex;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        while (timeline.tracks.size() <= trackIndex) {
            timeline.addTrack(new EditingActivity.Track());
        }
        timeline.tracks.get(trackIndex).addClip(clip);
        timeline.tracks.get(trackIndex).sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        timeline.tracks.get(trackIndex).removeClip(clip);
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Add Clip: " + clip.getClipName();
    }
}
