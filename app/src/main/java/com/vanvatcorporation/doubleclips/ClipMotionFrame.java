package com.vanvatcorporation.doubleclips;

/**
 * What a clip's Motion settings (optical flow, motion blur) come to at ONE output frame, ready for the GPU: the keyframed
 * Blur / Blend already read at that time. Built by OpenGLEdit.buildDrawCommand, used by the export's motion pass
 * (OpticalFlowGl). Null on the draw command of every clip that has neither switch on.
 */
public final class ClipMotionFrame {
    /** Optical flow on: the in-between frames are made at {@link #flowFps}. */
    public final boolean flow;
    public final float flowFps;
    /** Motion blur on. */
    public final boolean blur;
    /** The streak length and the share of the sharp picture mixed back, both 0 ... 1 (the panel's 0 ... 100). */
    public final float blurLength, blend;
    /** 0 both, 1 forward, 2 backward (the order of {@link ClipMotionBlur.Direction}). */
    public final int direction;
    public final int passes;

    private ClipMotionFrame(boolean flow, float flowFps, boolean blur, float blurLength, float blend, int direction, int passes) {
        this.flow = flow;
        this.flowFps = flowFps;
        this.blur = blur;
        this.blurLength = blurLength;
        this.blend = blend;
        this.direction = direction;
        this.passes = passes;
    }

    /** @param blurValue / blendValue the slider values (0 ... 100) at this frame; null when neither switch is on */
    public static ClipMotionFrame of(com.vanvatcorporation.doubleclips.activities.EditingActivity.Clip clip, float blurValue, float blendValue) {
        boolean flow = clip.hasOpticalFlow();
        boolean blur = clip.hasMotionBlur();
        if (!flow && !blur) return null;
        float scale = ClipMotionBlur.SCALE_MAX;
        return new ClipMotionFrame(flow, flow ? clip.opticalFlow.effectiveFps() : 0f, blur,
                Math.max(0f, Math.min(1f, blurValue / scale)), Math.max(0f, Math.min(1f, blendValue / scale)),
                blur ? clip.motionBlur.direction().ordinal() : 0, blur ? clip.motionBlur.effectivePasses() : 1);
    }

    /** True when there is a streak to draw: the switch is on and Blur is above 0. */
    public boolean smears() {
        return blur && blurLength > 0.0005f;
    }
}
