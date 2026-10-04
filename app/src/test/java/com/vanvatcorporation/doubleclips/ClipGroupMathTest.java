package com.vanvatcorporation.doubleclips;

import static org.junit.Assert.assertEquals;

import com.vanvatcorporation.doubleclips.helper.ClipGroupMath;

import org.junit.Test;

/** Group-drag and paste maths. Pure Java: runs on the JVM, no device needed. Rows are 0-based track indexes. */
public class ClipGroupMathTest {

    @Test
    public void upwardStopsWhenTopmostClipReachesFirstTrack() {
        // clips on tracks 1 and 2 (3 tracks): dragging far up may move only 1 row, nobody "jumps"
        assertEquals(-1, ClipGroupMath.clampTrackDelta(-5, 1, 2, 3));
        // a group whose top clip is already on track 0 cannot move up at all
        assertEquals(0, ClipGroupMath.clampTrackDelta(-1, 0, 1, 3));
        assertEquals(0, ClipGroupMath.clampTrackDelta(-1, 0, 0, 3));
    }

    @Test
    public void downwardMayGoOnBlankRowsButOnlyEntirelyBelowTheTimeline() {
        assertEquals(0, ClipGroupMath.clampTrackDelta(0, 0, 1, 3));
        assertEquals(1, ClipGroupMath.clampTrackDelta(1, 1, 2, 3));              // bottom clip goes on a blank row
        assertEquals((3 - 1 + 2) - 2, ClipGroupMath.clampTrackDelta(99, 1, 2, 3)); // group fully below
        assertEquals(1, ClipGroupMath.clampTrackDelta(99, 2, 2, 3));              // a single clip: one blank row max
    }

    @Test
    public void tracksAreCreatedOnlyForTheOverflow() {
        assertEquals(1, ClipGroupMath.tracksToCreate(ClipGroupMath.lowestTrackAfterMove(2, 1), 3));
        assertEquals(0, ClipGroupMath.tracksToCreate(2, 3));
        // the scenario from the request: 3 clips on their own tracks, 4 tracks, group top clip dropped on track 3
        // (rows 0,1,2 -> 3,4,5): rows 4 and 5 don't exist, tracks 4..5 are created -> 2 new tracks
        int delta = ClipGroupMath.clampTrackDelta(3, 0, 2, 4);
        assertEquals(3, delta);
        assertEquals(2, ClipGroupMath.tracksToCreate(ClipGroupMath.lowestTrackAfterMove(2, delta), 4));
    }

    @Test
    public void rowUnderThePointerIsNotClamped() {
        assertEquals(-1, ClipGroupMath.rawTrackFromY(-3, 75));
        assertEquals(1, ClipGroupMath.rawTrackFromY(76, 75));
        assertEquals(4, ClipGroupMath.rawTrackFromY(300, 75));
    }

    @Test
    public void earliestClipStopsAtZeroSeconds() {
        assertEquals(-120, ClipGroupMath.clampTimeDeltaPx(-500, 120), 0.0);
        assertEquals(40, ClipGroupMath.clampTimeDeltaPx(40, 120), 0.0);
    }

    @Test
    public void pastePlanKeepsTheGroupLayout() {
        ClipGroupMath.PastePlan p = ClipGroupMath.planPaste(2f, 1, 10f, 3);
        assertEquals(8f, p.timeShift, 0f);
        assertEquals(2, p.trackShift);
        // baseTrack below zero is treated as the first track
        assertEquals(-1, ClipGroupMath.planPaste(0f, 1, 0f, -4).trackShift);
    }
}
