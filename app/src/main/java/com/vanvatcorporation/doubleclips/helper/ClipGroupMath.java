package com.vanvatcorporation.doubleclips.helper;

/**
 * Pure maths for moving a GROUP of selected clips and for pasting one. No JavaFX in here so it
 * can be tested on its own (see the unit test).
 *
 * Group-move rules:
 *  - The whole group shifts by the same time delta and the same track delta, so every clip
 *    keeps its place relative to the others (clip A on track 1 + clip B on track 2 stay one
 *    track apart wherever they go).
 *  - Upward the group stops when its TOPMOST clip reaches track 0: nothing "jumps" because the
 *    other clips still have room to move.
 *  - Downward there is no wall at the last track. A clip may be shown (as a ghost) on a blank
 *    row below the timeline and the track is created when the group is dropped. The only limit
 *    is that the group may go at most entirely below the existing tracks, so a stray drag can't
 *    create dozens of tracks.
 *  - Time never goes below 0: the EARLIEST clip stops at 0 and the others follow.
 */
public final class ClipGroupMath {
    private ClipGroupMath() {}

    /** Track row under a pointer Y (in tracks-pane coordinates). Not clamped: may be negative or past the last track. */
    public static int rawTrackFromY(double localY, double rowHeight) {
        return (int) Math.floor(localY / rowHeight);
    }

    /**
     * @param requestedDelta  pointerTrack - anchorTrack
     * @param minTrack        topmost track index in the group
     * @param maxTrack        bottommost track index in the group
     * @param trackCount      tracks that exist right now
     */
    public static int clampTrackDelta(int requestedDelta, int minTrack, int maxTrack, int trackCount) {
        int lo = -minTrack;
        int span = maxTrack - minTrack + 1;
        int hi = (trackCount - 1 + span) - maxTrack;   // lowest clip may land on index trackCount-1+span
        if (hi < 0) hi = 0;
        return Math.max(lo, Math.min(hi, requestedDelta));
    }

    /** Keep the group's left edge at or after x = 0. */
    public static double clampTimeDeltaPx(double requestedDeltaPx, double groupLeftPx) {
        return Math.max(requestedDeltaPx, -groupLeftPx);
    }

    /** Highest track index the group occupies after the move (used to size the preview area / create tracks). */
    public static int lowestTrackAfterMove(int maxTrack, int trackDelta) {
        return maxTrack + trackDelta;
    }

    /** How many tracks must be created for the group to land. 0 when everything fits. */
    public static int tracksToCreate(int lowestTrackAfterMove, int trackCount) {
        return Math.max(0, lowestTrackAfterMove + 1 - trackCount);
    }

    /** Where to put a pasted group: how far to shift its times and tracks. */
    public static final class PastePlan {
        public final float timeShift;
        public final int trackShift;
        PastePlan(float timeShift, int trackShift) {
            this.timeShift = timeShift;
            this.trackShift = trackShift;
        }
    }

    /**
     * @param groupMinStart earliest start among the copied clips
     * @param groupMinTrack topmost track among the copied clips
     * @param anchorStart   where the earliest pasted clip should start
     * @param baseTrack     where the topmost pasted clip should land
     */
    public static PastePlan planPaste(float groupMinStart, int groupMinTrack, float anchorStart, int baseTrack) {
        return new PastePlan(anchorStart - groupMinStart, Math.max(0, baseTrack) - groupMinTrack);
    }
}
