package com.vanvatcorporation.doubleclips.commands;

import android.view.ViewGroup;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

/**
 * Command to add a new track to the timeline.
 */
public class AddTrackCommand implements CommandUtils.Command {
    private EditingActivity activity;
    private EditingActivity.Track track;
    private boolean wasAdded = false;

    public AddTrackCommand(EditingActivity activity, EditingActivity.Track track) {
        this.activity = activity;
        this.track = track;
    }

    @Override
    public void execute() {
        if (!wasAdded) {
            // This will be called when integrating with the actual add track method
            // For now, we'll prepare the structure
            activity.timeline.addTrack(track);
            wasAdded = true;
        }
    }

    @Override
    public void undo() {
        if (wasAdded) {
            // Remove track from timeline
            activity.timeline.removeTrack(track);
            wasAdded = false;
        }
    }

    @Override
    public String toString() {
        return "Add Track " + track.timelineIndex;
    }
}
