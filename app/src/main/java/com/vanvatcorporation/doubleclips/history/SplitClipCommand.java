package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

public class SplitClipCommand implements Command {
    private final EditingActivity.Timeline timeline;
    private final EditingActivity.Clip originalClip;
    private final float splitTime;
    private final float oldDuration;
    private final float oldStartClipTrim;
    private final float oldEndClipTrim;
    private EditingActivity.Clip secondPart;
    private final Runnable onUpdate;

    public SplitClipCommand(EditingActivity.Timeline timeline, EditingActivity.Clip clip, float splitTime, Runnable onUpdate) {
        this.timeline = timeline;
        this.originalClip = clip;
        this.splitTime = splitTime;
        this.oldDuration = clip.duration;
        this.oldStartClipTrim = clip.startClipTrim;
        this.oldEndClipTrim = clip.endClipTrim;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        EditingActivity.Track track = timeline.tracks.get(originalClip.trackIndex);
        float localSplitTime = originalClip.getLocalClipTime(splitTime);

        if (secondPart == null) {
            secondPart = new EditingActivity.Clip(originalClip);
        }

        // Secondary part
        secondPart.startTime = splitTime;
        secondPart.setStartClipTrim(localSplitTime + oldStartClipTrim);
        secondPart.setEndClipTrim(oldEndClipTrim);

        // Primary part
        originalClip.setEndClipTrim(originalClip.originalDuration - (localSplitTime + oldStartClipTrim));

        track.addClip(secondPart);
        track.sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        EditingActivity.Track track = timeline.tracks.get(originalClip.trackIndex);
        track.removeClip(secondPart);
        
        originalClip.startClipTrim = oldStartClipTrim;
        originalClip.endClipTrim = oldEndClipTrim;
        originalClip.duration = oldDuration;
        
        track.sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Split Clip: " + originalClip.getClipName();
    }
}
