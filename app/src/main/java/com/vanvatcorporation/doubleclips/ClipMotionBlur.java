package com.vanvatcorporation.doubleclips;

import com.google.gson.annotations.Expose;

import java.io.Serializable;

/**
 * A clip's motion blur (the "Motion blur" switch of the Motion panel, CapCut's): moving things get a streak along the way they
 * move, found from the optical flow between the clip's frames. The two sliders are keyframable and live in the clip's
 * {@link com.vanvatcorporation.doubleclips.activities.EditingActivity.VideoProperties} ({@code valueMotionBlur},
 * {@code valueMotionBlend}; the channels "motionBlur" and "motionBlend"), so they follow the same keyframe rules as every other
 * property; what is here is what the keyframes don't carry. Same JSON as iOS and Desktop ("motionBlur" on the clip).
 *
 * <ul>
 * <li><b>Blur</b> 0 ... {@link #SCALE_MAX} (default 100): how long the streak is;</li>
 * <li><b>Blend</b> 0 ... {@link #SCALE_MAX} (default 0): how much of the sharp frame is mixed back over the streak;</li>
 * <li>{@link #direction}: the streak reaches back to where things were, forward to where they go, or both;</li>
 * <li>{@link #passes}: how many times the blur is applied (CapCut's "Speed"). Typed by the user, so any whole number from 1 up
 *     is accepted.</li>
 * </ul>
 * Plain Java.
 */
public class ClipMotionBlur implements Serializable {

    public static final float SCALE_MAX = 100f;
    public static final float DEFAULT_BLUR = 100f;
    public static final float DEFAULT_BLEND = 0f;
    public static final int DEFAULT_PASSES = 1;

    public enum Direction {
        BOTH, FORWARD, BACKWARD;

        /** The direction named {@code name} (any case); BOTH for null or a name this build doesn't know. */
        public static Direction of(String name) {
            if (name != null) for (Direction d : values()) if (d.name().equalsIgnoreCase(name.trim())) return d;
            return BOTH;
        }
    }

    @Expose public boolean enabled = true;
    /** {@link Direction#name()}, stored as text so a newer build's extra direction doesn't stop an older one reading the file. */
    @Expose public String direction = Direction.BOTH.name();
    @Expose public int passes = DEFAULT_PASSES;

    public ClipMotionBlur() {}

    public ClipMotionBlur(ClipMotionBlur other) {
        this.enabled = other.enabled;
        this.direction = other.direction;
        this.passes = other.passes;
    }

    public Direction direction() {
        return Direction.of(direction);
    }

    public void setDirection(Direction d) {
        direction = (d == null ? Direction.BOTH : d).name();
    }

    /** {@link #passes}, never below 1 (a bad value in a project file counts as once). */
    public int effectivePasses() {
        return Math.max(1, passes);
    }
}
