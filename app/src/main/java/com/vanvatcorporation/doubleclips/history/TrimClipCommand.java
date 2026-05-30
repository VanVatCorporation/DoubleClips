package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

public class TrimClipCommand implements Command {
    private final EditingActivity.Timeline timeline;
    private final EditingActivity.Clip clip;
    private final Runnable onUpdate;

    private final float oldStartTime, newStartTime;
    private final float oldDuration, newDuration;
    private final float oldStartTrim, newStartTrim;
    private final float oldEndTrim, newEndTrim;

    public TrimClipCommand(EditingActivity.Timeline timeline, EditingActivity.Clip clip,
                           float oldStartTime, float newStartTime,
                           float oldDuration, float newDuration,
                           float oldStartTrim, float newStartTrim,
                           float oldEndTrim, float newEndTrim,
                           Runnable onUpdate) {
        this.timeline = timeline;
        this.clip = clip;
        this.oldStartTime = oldStartTime;
        this.newStartTime = newStartTime;
        this.oldDuration = oldDuration;
        this.newDuration = newDuration;
        this.oldStartTrim = oldStartTrim;
        this.newStartTrim = newStartTrim;
        this.oldEndTrim = oldEndTrim;
        this.newEndTrim = newEndTrim;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        clip.startTime = newStartTime;
        clip.duration = newDuration;
        clip.startClipTrim = newStartTrim;
        clip.endClipTrim = newEndTrim;
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        clip.startTime = oldStartTime;
        clip.duration = oldDuration;
        clip.startClipTrim = oldStartTrim;
        clip.endClipTrim = oldEndTrim;
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Trim Clip: " + clip.getClipName();
    }
}
