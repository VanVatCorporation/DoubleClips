package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

/**
 * Moves a track to another position as one undo step. Track order is layer order (a later track draws on top of the
 * earlier ones), so this also decides which clips cover which, in the preview and in both exports.
 * <p>
 * Undo moves it back; the activity renumbers the tracks, their clips and transitions, and puts the rows in the new order.
 */
public class MoveTrackCommand implements CommandUtils.Command {
    private final EditingActivity activity;
    private final int from, to;

    public MoveTrackCommand(EditingActivity activity, int from, int to) {
        this.activity = activity;
        this.from = from;
        this.to = to;
    }

    @Override
    public void execute() {
        activity.moveTrackUi(from, to);
    }

    @Override
    public void undo() {
        activity.moveTrackUi(to, from);
    }

    @Override
    public String toString() {
        return "Move Track " + (from + 1) + " to " + (to + 1);
    }
}
