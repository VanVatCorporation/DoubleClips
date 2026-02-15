package com.vanvatcorporation.doubleclips.commands;

import android.view.View;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

/**
 * Command to delete a clip from a track.
 * Captures the clip's position in the track and all UI state for restoration.
 */
public class DeleteClipCommand implements CommandUtils.Command {
    private EditingActivity activity;
    private EditingActivity.Timeline timeline;
    private EditingActivity.Track track;
    private EditingActivity.Clip clip;
    private int clipIndexInTrack;
    private boolean wasDeleted = false;

    public DeleteClipCommand(EditingActivity activity, EditingActivity.Clip clip) {
        this.activity = activity;
        this.clip = clip;
        
        // Find the track containing this clip
        this.track = activity.timeline.tracks.get(clip.trackIndex);
        
        // Capture the clip's position for restoration
        this.clipIndexInTrack = track.clips.indexOf(clip);
    }

    @Override
    public void execute() {
        if (!wasDeleted) {
            // Deselect if this clip is selected
            if (EditingActivity.selectedClip == clip) {
                activity.deselectingClip();
            }
            
            // Remove from data model
            track.removeClip(clip);
            
            // Remove from UI
            if (track.viewRef != null && clip.viewRef != null) {
                track.viewRef.removeView(clip.viewRef);
            }
            
            wasDeleted = true;
        }
    }

    @Override
    public void undo() {
        if (wasDeleted) {
            // Restore clip to data model at original position
            if (clipIndexInTrack >= 0 && clipIndexInTrack <= track.clips.size()) {
                track.clips.add(clipIndexInTrack, clip);
            } else {
                track.addClip(clip);
            }
            
            // Restore UI
            activity.addClipToTrackUi(track.viewRef, clip);
            
            wasDeleted = false;
        }
    }

    @Override
    public String toString() {
        return "Delete Clip: " + clip.getClipName();
    }
}
