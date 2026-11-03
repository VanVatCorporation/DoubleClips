package com.vanvatcorporation.doubleclips;

import com.google.gson.annotations.Expose;

import java.io.Serializable;
import java.util.Locale;

/**
 * A clip's optical flow (the "Optical flow" switch of the Motion panel, CapCut's frame interpolation): the in-between frames
 * of a slowed-down (or low frame rate) clip are made by estimating how things move between two source frames, instead of
 * repeating the nearer one. {@code fps} is the rate those frames are made at: the clip's time is snapped to a grid of
 * {@code fps} frames per second and each grid frame is interpolated, which is what ffmpeg's {@code minterpolate=fps=N} does
 * too. Same JSON as iOS and Desktop ("opticalFlow" on the clip).
 *
 * <p>Typed by the user, so any positive number is accepted (no upper limit, like the video settings); a value that isn't a
 * positive number falls back to {@link #DEFAULT_FPS}. Plain Java: the panel, the exporters and the preview share it.
 */
public class ClipOpticalFlow implements Serializable {

    public static final float DEFAULT_FPS = 60f;

    @Expose public boolean enabled = true;
    @Expose public float fps = DEFAULT_FPS;

    public ClipOpticalFlow() {}

    public ClipOpticalFlow(ClipOpticalFlow other) {
        this.enabled = other.enabled;
        this.fps = other.fps;
    }

    /** {@code value} when it is a usable frame rate (finite and above 0), else {@link #DEFAULT_FPS}. */
    public static float sanitize(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) || value <= 0f ? DEFAULT_FPS : value;
    }

    /** The rate to interpolate at (never 0 or NaN, even for a project file with a bad value). */
    public float effectiveFps() {
        return sanitize(fps);
    }

    /** True when this optical flow is on and asks for more frames per second than the project plays. */
    public boolean exceedsProject(int projectFps) {
        return enabled && effectiveFps() > projectFps + 0.001f;
    }

    /** "60" / "29.97": the rate as the text box shows it. */
    public static String format(float fps) {
        String s = String.format(Locale.ROOT, "%.3f", fps);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("[.]$", "");
        return s;
    }
}
