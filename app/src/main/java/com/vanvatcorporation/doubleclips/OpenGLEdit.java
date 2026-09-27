package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Platform-agnostic half of the OpenGL export pipeline — mirrors FFmpegEdit's
 * role: walks Timeline/Track/Clip and produces platform-neutral output
 * (transform matrices + per-clip draw info) that OpenGLEditNative (Android
 * today, a JavaFX/LWJGL equivalent later) executes with its own GL bindings.
 *
 * No android.* imports on purpose — same discipline FFmpegEdit already follows
 * despite referencing EditingActivity.Clip/Track/Timeline (see PLAN.md notes
 * on FFmpegEdit/FFmpegEditNative split).
 *
 * SCOPE (matches PLAN.md step 4): position/scale/rotation/opacity compositing
 * across multiple tracks/clips, matched pixel-for-pixel against FFmpegEdit's
 * scale->rotate->overlay filter chain (see FFmpegEdit.java:412-426). Color
 * grading (hue/saturation/brightness/temperature), effects (FXCommandEmitter),
 * and transitions are NOT here yet — steps 5 and 7. Only ClipType.VIDEO and
 * ClipType.IMAGE are composited; other clip types are skipped (logged by the
 * caller, not here, since this class has no logging dependency either).
 */
public class OpenGLEdit {

    /** One clip's contribution to a single output frame. */
    public static class DrawCommand {
        public final EditingActivity.Clip clip;
        /** Where to seek/advance this clip's decoder to, in the clip's OWN source time. */
        public final float localSourceTimeSeconds;
        /** Column-major 4x4, ready for glUniformMatrix4fv(..., false, mvpMatrix, ...). */
        public final float[] mvpMatrix;
        public final float opacity;

        public DrawCommand(EditingActivity.Clip clip, float localSourceTimeSeconds, float[] mvpMatrix, float opacity) {
            this.clip = clip;
            this.localSourceTimeSeconds = localSourceTimeSeconds;
            this.mvpMatrix = mvpMatrix;
            this.opacity = opacity;
        }
    }

    /**
     * Computes what to draw for one output timestamp. Tracks are walked in
     * ascending timelineIndex order and returned in that same order — this
     * matches FFmpegEdit's overlay chain (FFmpegEdit.java:107: tracks iterated
     * in list order, each overlaid on top of the accumulated base), so track
     * index 0 is bottom/first-drawn, higher indices composite on top.
     *
     * Only one clip per track is normally active at a given timestamp (clips
     * within a track don't overlap — transitions between adjacent clips are
     * a separate future step). Tracks with no active clip at this timestamp
     * are skipped.
     */
    public List<DrawCommand> computeFrameForTimestamp(EditingActivity.Timeline timeline, float outputTimeSeconds,
                                                        int canvasWidth, int canvasHeight) {
        List<DrawCommand> commands = new ArrayList<>();
        if (timeline == null || timeline.tracks == null) return commands;

        float[] projection = new float[16];
        // left=0,right=W,bottom=H,top=0: deliberately flips Y so pixel-space
        // Y-down (top-left origin, matching FFmpeg's overlay=X:Y convention)
        // lands correctly in NDC — top of canvas -> NDC +1, not -1.
        orthoM(projection, 0, canvasWidth, canvasHeight, 0, -1, 1);

        for (EditingActivity.Track track : timeline.tracks) {
            if (track == null || track.clips == null) continue;

            EditingActivity.Clip activeClip = findActiveClip(track, outputTimeSeconds);
            if (activeClip == null) continue;
            if (activeClip.type != EditingActivity.ClipType.VIDEO && activeClip.type != EditingActivity.ClipType.IMAGE) {
                continue; // effects/text/transitions/3D scenes: not this step
            }

            float localSourceTime = (outputTimeSeconds - activeClip.startTime) + activeClip.startClipTrim;

            float[] mvp = buildClipMvp(activeClip, projection);
            float opacity = activeClip.videoProperties != null
                    ? activeClip.videoProperties.getValue(EditingActivity.VideoProperties.ValueType.Opacity)
                    : 1f;

            commands.add(new DrawCommand(activeClip, localSourceTime, mvp, opacity));
        }

        return commands;
    }

    private EditingActivity.Clip findActiveClip(EditingActivity.Track track, float t) {
        for (EditingActivity.Clip clip : track.clips) {
            if (clip == null) continue;
            if (t >= clip.startTime && t < clip.startTime + clip.duration) {
                return clip;
            }
        }
        return null;
    }

    /**
     * Builds the MVP matrix for one clip, matching FFmpegEdit's
     * scale(iw*ScaleX, ih*ScaleY) -> rotate(auto-expand bbox) -> overlay(PosX,PosY)
     * chain (FFmpegEdit.java:412-426, 557) exactly, so OpenGL and FFmpeg output
     * land the clip in the same place:
     *
     * - scaledW/H: the clip's own intrinsic size times ScaleX/ScaleY (matches
     *   FFmpeg's "iw*ScaleX"/"ih*ScaleY" — NOT the timeline canvas size).
     * - rotation is around the clip's own center, by valueRot degrees.
     * - FFmpeg's rotate filter auto-expands its output canvas to the rotated
     *   bounding box (rotw/roth) BEFORE overlay positions it — so PosX/PosY is
     *   the top-left corner of that EXPANDED box, not the unrotated one. We
     *   don't need to actually expand a canvas in GL (alpha blending handles
     *   the transparent margins for free), but the CENTER position must still
     *   be computed from the expanded bbox to land in the same place FFmpeg would.
     */
    private float[] buildClipMvp(EditingActivity.Clip clip, float[] projection) {
        EditingActivity.VideoProperties vp = clip.videoProperties;
        float scaleX = vp != null ? vp.getValue(EditingActivity.VideoProperties.ValueType.ScaleX) : 1f;
        float scaleY = vp != null ? vp.getValue(EditingActivity.VideoProperties.ValueType.ScaleY) : 1f;
        float posX = vp != null ? vp.getValue(EditingActivity.VideoProperties.ValueType.PosX) : 0f;
        float posY = vp != null ? vp.getValue(EditingActivity.VideoProperties.ValueType.PosY) : 0f;
        float rotRadians = vp != null ? vp.getValue(EditingActivity.VideoProperties.ValueType.RotInRadians) : 0f;

        float scaledW = clip.width * scaleX;
        float scaledH = clip.height * scaleY;

        float cos = (float) Math.cos(rotRadians);
        float sin = (float) Math.sin(rotRadians);

        // FFmpeg's rotw()/roth(): the axis-aligned bounding box of a
        // scaledW x scaledH rectangle rotated by rotRadians.
        float bboxW = Math.abs(scaledW * cos) + Math.abs(scaledH * sin);
        float bboxH = Math.abs(scaledW * sin) + Math.abs(scaledH * cos);

        float centerX = posX + bboxW / 2f;
        float centerY = posY + bboxH / 2f;

        // Model matrix for a unit quad spanning (-1,-1)..(1,1): rotate + scale
        // by the clip's own half-extents, then translate to its center.
        // Column-major (index = column*4 + row), same layout glUniformMatrix4fv expects.
        float halfW = scaledW / 2f;
        float halfH = scaledH / 2f;
        float[] model = new float[16];
        model[0] = halfW * cos;   model[1] = halfW * sin;   model[2] = 0; model[3] = 0;
        model[4] = -halfH * sin;  model[5] = halfH * cos;   model[6] = 0; model[7] = 0;
        model[8] = 0;             model[9] = 0;             model[10] = 1; model[11] = 0;
        model[12] = centerX;      model[13] = centerY;      model[14] = 0; model[15] = 1;

        float[] mvp = new float[16];
        multiplyMM(mvp, projection, model);
        return mvp;
    }


    // ---- Minimal platform-neutral 4x4 matrix math (column-major, OpenGL layout) -
    // Deliberately not android.opengl.Matrix: that class doesn't exist on desktop,
    // and this class needs to stay usable from a future desktop OpenGLEditNative.

    public static void orthoM(float[] m, float left, float right, float bottom, float top, float near, float far) {
        float rWidth = 1.0f / (right - left);
        float rHeight = 1.0f / (top - bottom);
        float rDepth = 1.0f / (far - near);
        java.util.Arrays.fill(m, 0f);
        m[0] = 2.0f * rWidth;
        m[5] = 2.0f * rHeight;
        m[10] = -2.0f * rDepth;
        m[12] = -(right + left) * rWidth;
        m[13] = -(top + bottom) * rHeight;
        m[14] = -(far + near) * rDepth;
        m[15] = 1.0f;
    }

    /** result = lhs * rhs. result must not alias lhs or rhs. */
    public static void multiplyMM(float[] result, float[] lhs, float[] rhs) {
        float[] tmp = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += lhs[k * 4 + row] * rhs[col * 4 + k];
                }
                tmp[col * 4 + row] = sum;
            }
        }
        System.arraycopy(tmp, 0, result, 0, 16);
    }
}
