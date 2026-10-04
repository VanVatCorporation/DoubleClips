package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

/**
 * Shared helper for the group commands: grow the timeline so a track index exists, and
 * take those tracks away again on undo. Only trailing tracks that are EMPTY are removed,
 * so an undo can never delete a clip.
 *
 * On Android a track is more than a model object: it owns a row in the tracks container
 * and a header in the left track-info column, so the work is delegated to the activity.
 */
final class TrackGrowth {
    private TrackGrowth() {}

    /** Adds empty tracks (model + UI) until {@code requiredTracks} exist. Returns how many were added. */
    static int ensureTracks(EditingActivity activity, int requiredTracks) {
        int added = 0;
        while (activity.timeline.tracks.size() < requiredTracks) {
            activity.appendTrackUi();
            added++;
        }
        return added;
    }

    /** Removes up to {@code count} trailing tracks, stopping at the first one that still holds a clip. */
    static void dropTrailingEmptyTracks(EditingActivity activity, int count) {
        for (int i = 0; i < count && !activity.timeline.tracks.isEmpty(); i++) {
            EditingActivity.Track last = activity.timeline.tracks.get(activity.timeline.tracks.size() - 1);
            if (!last.clips.isEmpty()) return;
            activity.removeLastTrackUi();
        }
    }
}
