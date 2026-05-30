package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

public class DeleteClipCommand implements Command {
    private final EditingActivity.Timeline timeline;
    private final EditingActivity.Clip clip;
    private final int trackIndex;
    private final Runnable onUpdate;

    public DeleteClipCommand(EditingActivity.Timeline timeline, EditingActivity.Clip clip, Runnable onUpdate) {
        this.timeline = timeline;
        this.clip = clip;
        this.trackIndex = clip.trackIndex;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        timeline.tracks.get(trackIndex).removeClip(clip);
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        timeline.tracks.get(trackIndex).addClip(clip);
        timeline.tracks.get(trackIndex).sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Delete Clip: " + clip.getClipName();
    }
}
