package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

/**
 * Command to add a clip to a track.
 * Handles both data model and UI synchronization.
 */
public class AddClipCommand implements CommandUtils.Command {
    private EditingActivity activity;
    private EditingActivity.Track track;
    private EditingActivity.Clip clip;
    private boolean wasAdded = false;

    public AddClipCommand(EditingActivity activity, EditingActivity.Track track, EditingActivity.Clip clip) {
        this.activity = activity;
        this.track = track;
        this.clip = clip;
    }

    @Override
    public void execute() {
        if (!wasAdded) {
            // Add clip to data model
            track.addClip(clip);
            
            // Add clip to UI - use the internal UI method
            activity.addClipToTrackUi(track.viewRef, clip);
            
            wasAdded = true;
        }
    }

    @Override
    public void undo() {
        if (wasAdded) {
            // Remove from data model
            track.removeClip(clip);
            
            // Remove from UI
            if (clip.viewRef != null && track.viewRef != null) {
                track.viewRef.removeView(clip.viewRef);
            }
            
            // Clear selection if this clip was selected
            if (EditingActivity.selectedClip == clip) {
                activity.deselectingClip();
            }
            
            wasAdded = false;
        }
    }

    @Override
    public String toString() {
        return "Add Clip: " + clip.getClipName();
    }
}
