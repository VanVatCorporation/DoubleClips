package com.vanvatcorporation.doubleclips.impl;

/**
 * The geometry of dragging a track to a new position: where it would land, and how far every row is drawn from
 * its resting place meanwhile. Plain Java, a port of the iOS port's {@code TrackReorderDrag} so both behave alike.
 * <p>
 * The drag only changes how rows are DRAWN (offsets). Nothing in the timeline changes until the finger lifts; then
 * the move is applied once, as one undoable command. That keeps the row under the finger steady while the rows it
 * passes slide one row out of its way.
 */
public final class TrackReorderDrag {

    public final int startIndex;
    public final int count;
    public final float rowHeight;
    /** Finger travel since the grip was grabbed (+ = down). */
    private float translation;

    public TrackReorderDrag(int startIndex, int count, float rowHeight) {
        this.startIndex = startIndex;
        this.count = count;
        this.rowHeight = rowHeight;
    }

    public void setTranslation(float translation) { this.translation = translation; }

    public float translation() { return translation; }

    /** Where the track lands if released now: whole rows travelled, rounded to the nearest, kept inside the list. */
    public int targetIndex() {
        if (count <= 0) return 0;
        int steps = Math.round(translation / rowHeight);
        return Math.min(Math.max(startIndex + steps, 0), count - 1);
    }

    /**
     * Vertical offset to draw the row that RESTS at {@code index} with: the dragged row follows the finger (kept
     * inside the list), the rows it passes over move one row out of its way, everything else stays put.
     */
    public float offsetForRow(int index) {
        if (index == startIndex) {
            float lowest = -startIndex * rowHeight;
            float highest = (count - 1 - startIndex) * rowHeight;
            return Math.min(Math.max(translation, lowest), highest);
        }
        int target = targetIndex();
        if (startIndex < target && index > startIndex && index <= target) return -rowHeight;
        if (startIndex > target && index >= target && index < startIndex) return rowHeight;
        return 0;
    }
}
