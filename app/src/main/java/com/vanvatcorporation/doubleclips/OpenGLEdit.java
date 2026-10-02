package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 * and transitions are NOT here yet — steps 5 and 7. Only ClipType.VIDEO is
 * composited for now; other clip types are skipped (logged by the
 * caller, not here, since this class has no logging dependency either).
 */
public class OpenGLEdit {

    // ---- Capability flags -------------------------------------------------------
    // The single source of truth for what this renderer can't do yet. The export
    // screen asks getUnsupportedFeatures() and warns from that list, so when a
    // feature is implemented, flip its flag to true and the warning (and the
    // "use OpenGL anyway" choice) stops applying to it automatically - no UI
    // change needed. Anything unsupported is currently skipped/ignored by the
    // compositor rather than approximated.
    // Transitions supported so far - a real per-pixel crossfade/wipe/slide,
    // matching FFmpeg's xfade of the same name, via a two-pass FBO pipeline
    // (each clip rendered to its own full-canvas offscreen texture first, then
    // blended - see OpenGLEditNative.exportTimeline). Only EFFECT_TEMPLATE
    // styles in this set are honored; anything else (radial, circleopen,
    // pixelize, slices, fadeblack/white, distance, diag*, reveal*, custom_*)
    // and any transition using a mode other than OVERLAP are flagged as
    // unsupported by getUnsupportedFeatures below rather than silently
    // approximated or ignored. Keys must match FXCommandEmitter.FXRegistry.
    public static final Set<String> SUPPORTED_TRANSITION_STYLES = new java.util.HashSet<>(java.util.Arrays.asList(
            "fade", "dissolve", "wipeleft", "wiperight", "slideleft", "slideright", "slideup", "slidedown"
    ));
    public static final boolean SUPPORTS_TRANSITIONS = true;
    public static final boolean SUPPORTS_REVERSE = true;
    public static final boolean SUPPORTS_KEYFRAMES = true;
    public static final boolean SUPPORTS_IMAGES = true;

    /**
     * Human-readable list of timeline features this renderer will NOT reproduce
     * yet (empty = the OpenGL export will match the FFmpeg one for this project,
     * as far as this class covers). AUDIO clips are fine: audio is always mixed
     * by FFmpeg, see PLAN.md.
     */
    public static List<String> getUnsupportedFeatures(EditingActivity.Timeline timeline) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        if (timeline == null || timeline.tracks == null) return new ArrayList<>(found);

        for (EditingActivity.Track track : timeline.tracks) {
            if (track == null || track.clips == null) continue;
            for (EditingActivity.Clip clip : track.clips) {
                if (clip == null) continue;
                switch (clip.type) {
                    case IMAGE:
                        if (!SUPPORTS_IMAGES) found.add("Image clips");
                        break;
                    case TEXT:
                        found.add("Text clips");
                        break;
                    case EFFECT:
                        found.add("Effect clips");
                        break;
                    case SCENE_3D:
                        found.add("3D scene clips");
                        break;
                    case TRANSITION:
                        found.add("Transition clips");
                        break;
                    default: // VIDEO, AUDIO
                        break;
                }
                if (clip.isEndTransitionEnabled() && clip.endTransition != null
                        && clip.endTransition.effect != null && !"none".equals(clip.endTransition.effect.style)) {
                    if (clip.endTransition.mode != EditingActivity.TransitionClip.TransitionMode.OVERLAP) {
                        found.add("Transitions using End First/Begin Second timing (only Overlap is supported)");
                    } else if (!SUPPORTED_TRANSITION_STYLES.contains(clip.endTransition.effect.style)) {
                        found.add("Transitions using the '" + clip.endTransition.effect.style + "' effect");
                    }
                }
                if (!SUPPORTS_REVERSE && clip.isReverse()) found.add("Reversed clips");
                if (!SUPPORTS_KEYFRAMES && clip.hasAnimatedProperties()) found.add("Keyframe animations");
            }
        }
        return new ArrayList<>(found);
    }

    /** One clip's contribution to a single output frame. */
    public static class DrawCommand {
        public final EditingActivity.Clip clip;
        /** Where to seek/advance this clip's decoder to, in the clip's OWN source time. */
        public final float localSourceTimeSeconds;
        /** Column-major 4x4, ready for glUniformMatrix4fv(..., false, mvpMatrix, ...). */
        public final float[] mvpMatrix;
        public final float opacity;
        // Color grading, matching FFmpegEdit's hue=h=..:s=..:b=.. and
        // colortemperature=temperature=.. filters (FFmpegEdit.java:421-424).
        // Units match FFmpeg's own: hueDegrees is degrees, saturation/brightness
        // are the same multiplier/offset the hue filter takes, temperatureKelvin
        // is Kelvin (6500 = neutral/no change).
        public final float hueDegrees;
        public final float saturation;
        public final float brightness;
        public final float temperatureKelvin;
        /**
         * Gaussian blur SIGMA in output pixels for this frame, from an active
         * "unfold" in-animation (see UnfoldAnimation / buildDrawCommand).
         * 0 means no blur — the caller should skip the extra blur passes
         * entirely rather than run a Gaussian blur shader with sigma 0.
         */
        public final float blurSigmaPixels;
        /**
         * "unfold" in-animation frame warp, relative to the clip's own box: top
         * edge width, bottom edge width, and overall height (anchored at the top
         * edge, widths about the vertical center line = top-center). All three 1 =
         * no warp (every frame outside an active unfold). Applied in the fragment shader.
         */
        public final float unfoldTopWidth;
        public final float unfoldBottomWidth;
        public final float unfoldHeight;
        /** Contrast multiplier about mid-grey from "unfold" (1 = none). Applied in the colour stage of the shader. */
        public final float contrast;

        public DrawCommand(EditingActivity.Clip clip, float localSourceTimeSeconds, float[] mvpMatrix, float opacity,
                            float hueDegrees, float saturation, float brightness, float temperatureKelvin, float blurSigmaPixels,
                            float unfoldTopWidth, float unfoldBottomWidth, float unfoldHeight, float contrast) {
            this.clip = clip;
            this.localSourceTimeSeconds = localSourceTimeSeconds;
            this.mvpMatrix = mvpMatrix;
            this.opacity = opacity;
            this.hueDegrees = hueDegrees;
            this.saturation = saturation;
            this.brightness = brightness;
            this.temperatureKelvin = temperatureKelvin;
            this.blurSigmaPixels = blurSigmaPixels;
            this.unfoldTopWidth = unfoldTopWidth;
            this.unfoldBottomWidth = unfoldBottomWidth;
            this.unfoldHeight = unfoldHeight;
            this.contrast = contrast;
        }
    }

    /**
     * A transition window between two clips on the same track: clipA (ending)
     * and clipB (starting) each get their OWN complete DrawCommand — own
     * transform, own color grading, own local source time — because FFmpeg's
     * xfade blends two INDEPENDENTLY fully-rendered full-canvas layers, not two
     * raw textures at a shared position (see PLAN.md decisions log). style is
     * guaranteed to be a key in SUPPORTED_TRANSITION_STYLES (callers filter
     * unsupported ones out before this is created).
     */
    public static class TransitionCommand {
        public final DrawCommand clipACommand;
        public final DrawCommand clipBCommand;
        public final String style;
        /** 0 at the start of the transition window, 1 at the end. */
        public final float progress;

        public TransitionCommand(DrawCommand clipACommand, DrawCommand clipBCommand, String style, float progress) {
            this.clipACommand = clipACommand;
            this.clipBCommand = clipBCommand;
            this.style = style;
            this.progress = progress;
        }
    }

    /** One track's single frame layer: exactly one of simpleDraw/transition is non-null. Order in the list returned by computeFrameForTimestamp is the actual draw order. */
    public static class FrameLayer {
        public final DrawCommand simpleDraw;
        public final TransitionCommand transition;
        private FrameLayer(DrawCommand simpleDraw, TransitionCommand transition) {
            this.simpleDraw = simpleDraw;
            this.transition = transition;
        }
        static FrameLayer of(DrawCommand d) { return new FrameLayer(d, null); }
        static FrameLayer of(TransitionCommand t) { return new FrameLayer(null, t); }
    }

    /**
     * Computes what to draw for one output timestamp, one FrameLayer per track
     * that has anything active, in the EXACT order tracks were walked. This
     * matches FFmpegEdit's overlay chain (FFmpegEdit.java:107: tracks iterated
     * in list order, each overlaid on top of the accumulated base) — so the
     * caller (OpenGLEditNative) MUST draw this list front-to-back in order,
     * not group simple draws and transitions into separate batches, or track
     * stacking order breaks whenever a project mixes plain and transitioning
     * tracks.
     */
    public List<FrameLayer> computeFrameForTimestamp(EditingActivity.Timeline timeline, float outputTimeSeconds,
                                                        int canvasWidth, int canvasHeight, boolean stretchToFull) {
        List<FrameLayer> layers = new ArrayList<>();
        if (timeline == null || timeline.tracks == null) return layers;

        float[] projection = new float[16];
        // left=0,right=W,bottom=H,top=0: deliberately flips Y so pixel-space
        // Y-down (top-left origin, matching FFmpeg's overlay=X:Y convention)
        // lands correctly in NDC — top of canvas -> NDC +1, not -1.
        orthoM(projection, 0, canvasWidth, canvasHeight, 0, -1, 1);

        for (EditingActivity.Track track : timeline.tracks) {
            if (track == null || track.clips == null) continue;

            TransitionWindow window = findActiveTransition(track, outputTimeSeconds);
            if (window != null) {
                DrawCommand cmdA = buildDrawCommand(window.clipA, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection);
                DrawCommand cmdB = buildDrawCommand(window.clipB, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection);
                if (cmdA != null && cmdB != null) {
                    layers.add(FrameLayer.of(new TransitionCommand(cmdA, cmdB, window.style, window.progress)));
                    continue;
                }
                // One side has an unsupported clip type (e.g. text/3D) - fall
                // through to normal single-clip handling below rather than
                // dropping the track entirely.
            }

            EditingActivity.Clip activeClip = findActiveClip(track, outputTimeSeconds);
            if (activeClip == null) continue;
            DrawCommand cmd = buildDrawCommand(activeClip, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection);
            if (cmd != null) layers.add(FrameLayer.of(cmd));
        }

        return layers;
    }

    /** Detects an active OVERLAP-mode, supported-style transition on this track at t, if any. */
    private static class TransitionWindow {
        final EditingActivity.Clip clipA, clipB;
        final String style;
        final float progress;
        TransitionWindow(EditingActivity.Clip clipA, EditingActivity.Clip clipB, String style, float progress) {
            this.clipA = clipA; this.clipB = clipB; this.style = style; this.progress = progress;
        }
    }

    /**
     * Window = [clipA.startTime + clipA.duration - transitionDuration,
     * clipA.startTime + clipA.duration) — derived from FXCommandEmitter's own
     * OVERLAP-mode offset (clipA.duration - transitionDuration), NOT from
     * TransitionClip.startTime (that field appears to be a UI knot-display
     * position, not what the actual FFmpeg render uses — see PLAN.md decisions
     * log). END_FIRST/BEGIN_SECOND have different offsets and are not handled
     * here; they're reported by getUnsupportedFeatures instead.
     */
    private TransitionWindow findActiveTransition(EditingActivity.Track track, float t) {
        for (int i = 0; i < track.clips.size() - 1; i++) {
            EditingActivity.Clip clipA = track.clips.get(i);
            if (clipA == null || !clipA.isEndTransitionEnabled() || clipA.endTransition == null) continue;
            EditingActivity.TransitionClip transition = clipA.endTransition;
            if (transition.mode != EditingActivity.TransitionClip.TransitionMode.OVERLAP) continue;
            if (transition.effect == null || "none".equals(transition.effect.style)) continue;
            if (!SUPPORTED_TRANSITION_STYLES.contains(transition.effect.style)) continue;

            float windowEnd = clipA.startTime + clipA.duration;
            float windowStart = windowEnd - transition.duration;
            if (t >= windowStart && t < windowEnd) {
                EditingActivity.Clip clipB = track.clips.get(i + 1);
                float progress = transition.duration > 0f ? (t - windowStart) / transition.duration : 1f;
                return new TransitionWindow(clipA, clipB, transition.effect.style, progress);
            }
        }
        return null;
    }

    // ---- "unfold" in-animation ----------------------------------------------------
    // All of the curves (top-centre squish, colour flash, blur) live in UnfoldAnimation,
    // which is plain Java and shared with FFmpegEdit and the desktop port. This class
    // only turns them into per-frame DrawCommand values. The squish itself is applied in
    // the fragment shader (OpenGLEditNative.UNFOLD_WARP_*) as an inverse mapping with
    // edge clamping, so the area the shrunken picture no longer covers is filled with
    // edge pixels, like the reference, rather than showing a gap.

    /** Shared window/progress check. Returns -1 if there's no active "unfold" in-animation right now. */
    private float unfoldProgress(EditingActivity.Clip clip, float outputTimeSeconds) {
        EditingActivity.AnimationClip anim = clip.inAnimation;
        if (anim == null || !UnfoldAnimation.TYPE.equals(anim.type)) return -1f;
        return UnfoldAnimation.progress(outputTimeSeconds - clip.startTime, anim.duration); // 0 at clip start -> 1 at animation end
    }

    /** Builds one clip's complete draw info at outputTimeSeconds, or null if its type isn't drawable (audio/text/effect/3D — see getUnsupportedFeatures). */
    private DrawCommand buildDrawCommand(EditingActivity.Clip clip, float outputTimeSeconds,
                                          int canvasWidth, int canvasHeight, boolean stretchToFull, float[] projection) {
        if (clip.type != EditingActivity.ClipType.VIDEO && clip.type != EditingActivity.ClipType.IMAGE) {
            return null; // audio has no picture; text/effects/3D: see getUnsupportedFeatures
        }

        // Speed: FFmpeg remaps clip-local time via
        // setpts='(PTS-STARTPTS)/Speed+...' (FFmpegEdit.java:426), i.e. the
        // clip plays Speed times faster than the output timeline. Elapsed
        // OUTPUT time must be scaled by Speed to get elapsed SOURCE time -
        // this was missing before (localSourceTime just used elapsed output
        // time directly), which made any clip with Speed != 1.0 drift out
        // of sync with FFmpeg's export and eventually its own audio.
        float speed = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Speed);
        if (speed <= 0f) speed = 1f; // guard against a bad/zero value stalling the decoder forever
        float elapsedOutput = outputTimeSeconds - clip.startTime;
        // Reversed clips are decoded from a pre-rendered, already-reversed
        // intermediate that OpenGLEditNative builds for just the used trim
        // range (see PLAN.md decisions log) - that file starts at local time
        // 0 with the trim-in point, so no startClipTrim offset applies here,
        // unlike the normal (forward, original-file) case. Note: elapsedOutput
        // is intentionally allowed to be negative (clipB pre-rolling into a
        // transition, before its own nominal start) or exceed the clip's own
        // duration (clipA continuing past its nominal end, during a
        // transition) - both are correct here, matching what FFmpeg's own
        // xfade does with the same underlying clip stream.
        float localSourceTime = clip.isReverse()
                ? elapsedOutput * speed
                : clip.startClipTrim + elapsedOutput * speed;

        // "unfold": every value stays neutral (0 / 1) outside the animation window, so the
        // overwhelming majority of frames pay nothing for this.
        float unfoldProgress = unfoldProgress(clip, outputTimeSeconds);

        float[] mvp = buildClipMvp(clip, outputTimeSeconds, projection, canvasWidth, canvasHeight, stretchToFull);
        float opacity = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Opacity);
        float hue = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Hue);
        float saturation = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Saturation)
                * UnfoldAnimation.saturationMultiplier(unfoldProgress);
        float brightness = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Brightness)
                + UnfoldAnimation.brightness(unfoldProgress);
        float temperature = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Temperature);
        float blurSigmaPixels = UnfoldAnimation.blurSigmaFraction(unfoldProgress) * canvasWidth;

        return new DrawCommand(clip, localSourceTime, mvp, opacity, hue, saturation, brightness, temperature, blurSigmaPixels,
                UnfoldAnimation.topWidth(unfoldProgress), UnfoldAnimation.bottomWidth(unfoldProgress),
                UnfoldAnimation.heightScale(unfoldProgress), UnfoldAnimation.contrastMultiplier(unfoldProgress));
    }

    /**
     * Reads a property at outputTimeSeconds (the ABSOLUTE output-timeline time -
     * AnimatedProperty.getValueAtTime subtracts clip.startTime itself). Works
     * for both keyframed and static clips: getValueAtTime already falls back to
     * clip.videoProperties.getValue(valueType) when there are no keyframes, so
     * no branching is needed here - this always matches whichever the clip has.
     */
    private float readAtTime(EditingActivity.Clip clip, float outputTimeSeconds, EditingActivity.VideoProperties.ValueType valueType) {
        if (clip.keyframes != null) return clip.keyframes.getValueAtTime(clip, outputTimeSeconds, valueType);
        return clip.videoProperties != null ? clip.videoProperties.getValue(valueType) : 0f;
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
    private float[] buildClipMvp(EditingActivity.Clip clip, float outputTimeSeconds, float[] projection,
                                  int canvasWidth, int canvasHeight, boolean stretchToFull) {
        float scaleX = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.ScaleX);
        float scaleY = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.ScaleY);
        float posX = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.PosX);
        float posY = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.PosY);
        float pivotX = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.PivotX);
        float pivotY = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.PivotY);
        float rotRadians = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.RotInRadians);

        // Stretch-to-fit: matches FFmpegEdit's scale=w=(stretchToFull ? renderWidth
        // : iw)*ScaleX:h=(stretchToFull ? renderHeight : ih)*ScaleY (FFmpegEdit.java,
        // scaleXStretchExpr/scaleYStretchExpr) - the OUTPUT canvas size replaces the
        // clip's own intrinsic size as the base ScaleX/ScaleY multiplies against.
        float baseW = stretchToFull ? canvasWidth : clip.width;
        float baseH = stretchToFull ? canvasHeight : clip.height;
        float scaledW = baseW * scaleX;
        float scaledH = baseH * scaleY;

        float cos = (float) Math.cos(rotRadians);
        float sin = (float) Math.sin(rotRadians);

        // Pivot is ONLY the transform origin for scale and rotation (like CSS
        // transform-origin / Android View.setPivotX). It must NOT move the clip:
        // PosX/PosY is always the canvas position of the clip's UNSCALED, unrotated
        // top-left corner, regardless of pivot. Normalized pivot: [0, 1],
        // 0 = left/top, 1 = right/bottom.
        float halfW = scaledW / 2f;
        float halfH = scaledH / 2f;

        // Pivot point in canvas pixel space. It is located on the UNSCALED clip, so it
        // stays fixed while scale and rotation are applied around it.
        float pivotCanvasX = posX + pivotX * baseW;
        float pivotCanvasY = posY + pivotY * baseH;

        // Vector from pivot to quad center after scaling about the pivot, then rotated
        // about the pivot by rotRadians to get the final center.
        float toCenterX = (0.5f - pivotX) * scaledW;
        float toCenterY = (0.5f - pivotY) * scaledH;
        float rotatedOffsetX = toCenterX * cos - toCenterY * sin;
        float rotatedOffsetY = toCenterX * sin + toCenterY * cos;
        float centerX = pivotCanvasX + rotatedOffsetX;
        float centerY = pivotCanvasY + rotatedOffsetY;

        // Model matrix for a unit quad spanning (-1,-1)..(1,1): rotate + scale
        // by the clip's own half-extents, then translate to its center.
        // Column-major (index = column*4 + row), same layout glUniformMatrix4fv expects.
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
