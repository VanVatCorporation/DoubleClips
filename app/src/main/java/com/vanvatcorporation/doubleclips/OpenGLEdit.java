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
    /**
     * The transition ids the OpenGL export draws: the built-in shader styles plus every data-driven style (the
     * bundled iOS files re-create the xfade names fade, wipeleft, circleopen... as {@code layers} / {@code mask}
     * styles, and for those ids OpenGL uses the file, see {@link #computeFrameForTimestamp}). For the picker's badges.
     */
    public static Set<String> glTransitionStyles() {
        Set<String> all = new java.util.HashSet<>(SUPPORTED_TRANSITION_STYLES);
        if (SUPPORTS_DATA_DRIVEN_TRANSITIONS) for (TransitionStyle s : TransitionStyleLoader.list()) all.add(s.getId());
        return all;
    }
    public static final boolean SUPPORTS_TRANSITIONS = true;
    /**
     * Data-driven transition styles ({@link TransitionStyleLoader}): mode cut draws ONE picture (A before the style's
     * cut, B after), mode layers draws both (B over A) and mode mask reveals B through the style's mask shape
     * ({@link TransitionMaskRenderer}); each side's channels are merged into that clip's own animation frame, in all
     * three timing modes (see {@link TransitionTiming}). False would make the export screen warn about them.
     */
    public static final boolean SUPPORTS_DATA_DRIVEN_TRANSITIONS = true;
    /**
     * Effect clips (adjustment layers, {@link EffectLayerRenderer}): the filter ops of an effect style's
     * {@code filters} list that the GPU draws. A style whose list names an op outside this set still runs its channels
     * and the ops it does know, but the export screen warns about it (see getUnsupportedFeatures). Channel-only styles
     * (shakes, pulses, fades, spin, push...) are always fully drawn. Every op the loader knows is drawn since E2b.
     */
    public static final Set<String> SUPPORTED_EFFECT_OPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "blur", "colorControls", "photoEffect", "sepia", "vignette", "bloom", "pixellate", "dotScreen",
            "kaleidoscope", "twirl", "bump", "rgbShift", "tearSlices", "crossProcess", "vhs"
    ));
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
                    case EFFECT: {
                        String problem = effectProblem(clip);
                        if (problem != null) found.add(problem);
                        break;
                    }
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
                    if (TransitionStyleLoader.isDataDriven(clip.endTransition.effect.style)) {
                        // Data-driven styles are drawn in every timing mode.
                        if (!SUPPORTS_DATA_DRIVEN_TRANSITIONS) {
                            found.add("The '" + TransitionStyleLoader.get(clip.endTransition.effect.style).getName() + "' transition style (not drawn by the OpenGL export yet)");
                        }
                    } else if (clip.endTransition.mode != EditingActivity.TransitionClip.TransitionMode.OVERLAP) {
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

    /** Why the OpenGL engine can't draw this effect clip completely, or null when it can (unknown style: nothing is drawn anywhere). */
    private static String effectProblem(EditingActivity.Clip clip) {
        if (clip.effect == null || clip.effect.style == null) return null;
        EffectStyle style = EffectStyleLoader.get(EffectStyleLoader.canonical(clip.effect.style));
        if (style == null) return "The '" + clip.effect.style + "' effect (not known to this version, it is left out)";
        for (EffectFilterOps.Filter filter : style.getFilters()) {
            if (!SUPPORTED_EFFECT_OPS.contains(filter.getOp())) {
                return "The '" + style.getName() + "' effect (its " + filter.getOp() + " filter is not drawn by the OpenGL export yet)";
            }
        }
        return null;
    }

    /**
     * Sizes a TEXT clip's rasterised text in canvas pixels ({width, height}, or null = nothing to
     * draw). Injected by the platform (Android: TextRasterizer::measure) so this class stays free of
     * android.graphics; with none set, text clips simply aren't drawn.
     */
    public interface TextMeasurer {
        int[] measure(EditingActivity.Clip clip);
    }

    public static volatile TextMeasurer textMeasurer;

    /** One animatable piece of a text block (a character, word or line), in block pixels. */
    public static final class TextUnit {
        /** The text this unit draws (also the key of its texture). */
        public final String text;
        /** Top-left and size of the unit's own (padded) bitmap inside the text block. */
        public final float x, y, w, h;

        public TextUnit(String text, float x, float y, float w, float h) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** Splits a TEXT clip into units for {@code mode} (CHARACTER / WORD / LINE). Injected by the platform, like {@link TextMeasurer}. */
    public interface TextUnitProvider {
        List<TextUnit> units(EditingActivity.Clip clip, String mode);
    }

    public static volatile TextUnitProvider textUnitProvider;

    /**
     * Whole-picture effects of one frame (the transition-style channels that are not per-pixel colour). Everything is in
     * canvas texture space: u to the right, v UP (the offscreen layers are rendered with the canvas top at v = 1).
     */
    public static final class PostFx {
        /** Brightness multiplier (1 = none). */
        public final float exposure;
        /** Red colour cast 0..1 (lifts red, cuts green / blue) and wash-out toward white 0..1 (iOS ClipCompositor.colorAdjusted). */
        public final float redTint, whiteMix;
        /** Half-length of the motion-blur streak as (du, dv); (0,0) = none. Already includes the canvas aspect ratio. */
        public final float streakU, streakV;
        /** Streak LENGTH as a fraction of the canvas width (to pick the tap count). */
        public final float streakLengthFraction;
        /** Where the picture moves to: +u right, +v up, in canvas fractions. */
        public final float slideU, slideV;
        /** Horizontal squeeze (1 = none) toward the anchor (0 = left edge of the picture's box, 1 = right edge). */
        public final float squeezeX, squeezeAnchor;
        /** The picture's edge pixels cover the whole canvas. */
        public final boolean edgeFill;
        /** The picture's own bounding box on the canvas (minU, minV, maxU, maxV): what edge fill extends from. */
        public final float boxMinU, boxMinV, boxMaxU, boxMaxV;

        PostFx(float exposure, float redTint, float whiteMix, float streakU, float streakV, float streakLengthFraction,
               float slideU, float slideV, float squeezeX, float squeezeAnchor, boolean edgeFill,
               float boxMinU, float boxMinV, float boxMaxU, float boxMaxV) {
            this.exposure = exposure;
            this.redTint = redTint;
            this.whiteMix = whiteMix;
            this.streakU = streakU;
            this.streakV = streakV;
            this.streakLengthFraction = streakLengthFraction;
            this.slideU = slideU;
            this.slideV = slideV;
            this.squeezeX = squeezeX;
            this.squeezeAnchor = squeezeAnchor;
            this.edgeFill = edgeFill;
            this.boxMinU = boxMinU;
            this.boxMinV = boxMinV;
            this.boxMaxU = boxMaxU;
            this.boxMaxV = boxMaxV;
        }

        /** The post effects of this frame's animation values, or null when there is nothing to do. */
        static PostFx from(ClipAnimationFrame anim, float[] mvp, int canvasWidth, int canvasHeight) {
            float exposure = anim.exposure();
            float motion = anim.motionBlurWidthFraction();
            float slideU = anim.edgeSlideFraction();
            float slideV = -anim.edgeSlideYFraction(); // +Y is down on the canvas, v points up
            float squeeze = anim.squeezeX();
            boolean fill = anim.edgeFill();
            boolean hasExposure = Math.abs(exposure - 1f) > 0.001f;
            boolean hasStreak = motion > 0.0005f;
            boolean hasSlide = Math.abs(slideU) > 1e-5f || Math.abs(slideV) > 1e-5f;
            boolean hasSqueeze = Math.abs(squeeze - 1f) > 0.002f;
            float redTint = anim.redTint(), whiteMix = anim.whiteMix();
            boolean hasTint = redTint > 0.001f || whiteMix > 0.001f;
            if (!hasExposure && !hasStreak && !hasSlide && !hasSqueeze && !fill && !hasTint) return null;

            // The picture's bounding box: where the quad's corners land (mvp maps the unit quad to NDC; NDC +1 = canvas top = v 1).
            float minU = Float.MAX_VALUE, minV = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float qx = (i & 1) == 0 ? -1f : 1f;
                float qy = (i & 2) == 0 ? -1f : 1f;
                float nx = mvp[0] * qx + mvp[4] * qy + mvp[12];
                float ny = mvp[1] * qx + mvp[5] * qy + mvp[13];
                float u = (nx + 1f) * 0.5f, v = (ny + 1f) * 0.5f;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }

            // Streak: half-length is half of motionBlur (iOS MOTION_BLUR_RADIUS_FACTOR), as a fraction of the canvas WIDTH,
            // along blurAngle (0 = horizontal, counter-clockwise); a y step of the same pixel length is aspect times bigger in v.
            double angle = Math.toRadians(anim.blurAngleDegrees());
            float half = motion * 0.5f;
            float aspect = canvasHeight > 0 ? canvasWidth / (float) canvasHeight : 1f;
            float streakU = hasStreak ? (float) Math.cos(angle) * half : 0f;
            float streakV = hasStreak ? (float) Math.sin(angle) * half * aspect : 0f;
            return new PostFx(exposure, redTint, whiteMix, streakU, streakV, hasStreak ? motion : 0f, slideU, slideV,
                    squeeze, anim.squeezeAnchor(), fill, minU, minV, maxU, maxV);
        }
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
         * in-animation (see ClipAnimation / buildDrawCommand).
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
        /** TEXT only: when set, this command draws just this unit of the text (see TextUnit) instead of the whole block. */
        public final String textUnit;
        /**
         * Effects that need the finished picture as a canvas-sized layer (exposure, edge fill / slide, squeeze, motion
         * blur: the data-driven transition channels), or null for the overwhelming majority of frames. The caller draws
         * the command into an offscreen layer first, runs OpenGLEditNative.PostFxShader over it, then (when
         * blurSigmaPixels &gt; 0) the Gaussian blur, exactly like the in-animation blur path.
         */
        public PostFx post;

        public DrawCommand(EditingActivity.Clip clip, float localSourceTimeSeconds, float[] mvpMatrix, float opacity,
                            float hueDegrees, float saturation, float brightness, float temperatureKelvin, float blurSigmaPixels,
                            float unfoldTopWidth, float unfoldBottomWidth, float unfoldHeight, float contrast) {
            this(clip, localSourceTimeSeconds, mvpMatrix, opacity, hueDegrees, saturation, brightness, temperatureKelvin,
                    blurSigmaPixels, unfoldTopWidth, unfoldBottomWidth, unfoldHeight, contrast, null);
        }

        public DrawCommand(EditingActivity.Clip clip, float localSourceTimeSeconds, float[] mvpMatrix, float opacity,
                            float hueDegrees, float saturation, float brightness, float temperatureKelvin, float blurSigmaPixels,
                            float unfoldTopWidth, float unfoldBottomWidth, float unfoldHeight, float contrast, String textUnit) {
            this.textUnit = textUnit;
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
        /** Null only for a mask transition whose side could not be drawn (then that side is empty). */
        public final DrawCommand clipACommand;
        public final DrawCommand clipBCommand;
        public final String style;
        /** 0 at the start of the transition window, 1 at the end. */
        public final float progress;
        /** Set for a data-driven {@code mask} transition: B is revealed through this shape instead of a built-in blend style. */
        public final MaskSpec mask;

        /** A data-driven mask transition (either command may be null). */
        public TransitionCommand(DrawCommand clipACommand, DrawCommand clipBCommand, MaskSpec mask, float progress) {
            this.clipACommand = clipACommand;
            this.clipBCommand = clipBCommand;
            this.style = "mask";
            this.progress = progress;
            this.mask = mask;
        }

        public TransitionCommand(DrawCommand clipACommand, DrawCommand clipBCommand, String style, float progress) {
            this.clipACommand = clipACommand;
            this.clipBCommand = clipBCommand;
            this.style = style;
            this.progress = progress;
            this.mask = null;
        }
    }

    /**
     * A transition mask at one moment (iOS ClipCompositor.transitionMask): white = B, black = A. {@code reveal} is how
     * much of B shows; the other values depend on the shape (see TransitionMaskRenderer for the geometry):
     * wipe angle / softness / normalized, circle cx / cy / softness / invert, noise softness / grain, slices angle /
     * count, clock start.
     */
    public static final class MaskSpec {
        public final String shape;
        public final float reveal, angleDegrees, softness, cx, cy, grain, count, startDegrees;
        public final boolean normalized, invert;

        MaskSpec(EffectFilterOps.Filter mask, float p) {
            shape = mask.getOp();
            reveal = (float) Math.min(Math.max(mask.raw("reveal", p), 0.0), 1.0);
            angleDegrees = (float) mask.raw("angle", p);
            softness = (float) mask.raw("softness", p);
            cx = (float) mask.raw("cx", p);
            cy = (float) mask.raw("cy", p);
            grain = (float) mask.raw("grain", p);
            count = (float) mask.raw("count", p);
            startDegrees = (float) mask.raw("start", p);
            normalized = mask.hasFlag("normalized");
            invert = mask.hasFlag("invert");
        }
    }

    /** One evaluated {@code filters} entry of an effect style at one moment: the op, its parameters already moved by the strength, and its options. */
    public static final class FilterStep {
        public final String op;
        /** {@code photoEffect}'s name ("noir", "chrome"...), else null. */
        public final String name;
        /** The op's {@code randomSign} flag (rgbShift). */
        public final boolean randomSign;
        /** Which 1/15 s slot of output time this is: the seed of the random patterns (see {@link #random}). */
        public final int step;
        private final java.util.Map<String, Double> values;

        FilterStep(String op, String name, boolean randomSign, int step, java.util.Map<String, Double> values) {
            this.step = step;
            this.op = op;
            this.name = name;
            this.randomSign = randomSign;
            this.values = values;
        }

        /** Deterministic pseudo-random 0..1 for this step (iOS EffectRenderer.random): the same frame always looks the same in preview, scrubbing and export. */
        public float random(double salt) { return OpenGLEdit.random(step, salt); }

        /** A parameter at this moment (0 for a name the op doesn't have). Distances are fractions of the canvas, see EffectLayerRenderer. */
        public float v(String param) {
            Double d = values.get(param);
            return d == null ? 0f : d.floatValue();
        }
    }

    /**
     * An effect clip (adjustment layer) for one frame: process the picture drawn so far (the layers before this one in
     * the list), then the layers after it are drawn on top of the result. Everything is already evaluated for this
     * moment, so the GL side only draws: {@link #look} (the style's channels: whole-picture transform and colour, then
     * the post pass and blur) and {@link #steps} (the style's filters, in order). Mirrors iOS EffectRenderer.apply.
     */
    public static final class EffectCommand {
        public final EditingActivity.Clip clip;
        public final EffectStyle style;
        /** The strength this frame (effect.params["intensity"]; 1 = the file's own look). */
        public final float strength;
        /** Seconds into the effect clip and the clip's length. */
        public final float elapsed, duration;
        /** Output time, the seed of the random patterns (they jump 15 times a second, like iOS). */
        public final float time;
        /** The style's channels at this moment, or null when it has none. */
        public final ClipAnimationFrame look;
        /** Whether the look needs the transform / colour pass (false when only exposure / blur / slide... are asked for). */
        public final boolean lookPass;
        /** Column-major, maps the canvas-sized quad to where the picture ends up (rotation / scale / offset about the canvas centre). */
        public final float[] lookMvp;
        /** Colour of the look pass (clip-shader units, see ImageTransformShader): hue degrees, saturation x, brightness, contrast x, kelvin, opacity. */
        public final float hue, saturation, brightness, contrast, temperatureKelvin, opacity;
        /** Exposure / tint / white / edge fill / slide / squeeze / motion blur of the look, or null. */
        public final PostFx post;
        /** Gaussian blur of the look as a fraction of the canvas WIDTH (0 = none). */
        public final float blurWidthFraction;
        public final List<FilterStep> steps;
        /** How much of the filter chain's result is blended over the picture before it (1 = all of it). */
        public final float mix;

        EffectCommand(EditingActivity.Clip clip, EffectStyle style, float strength, float elapsed, float duration, float time,
                      ClipAnimationFrame look, boolean lookPass, float[] lookMvp, PostFx post, List<FilterStep> steps, float mix) {
            this.clip = clip;
            this.style = style;
            this.strength = strength;
            this.elapsed = elapsed;
            this.duration = duration;
            this.time = time;
            this.look = look;
            this.lookPass = lookPass;
            this.lookMvp = lookMvp;
            this.hue = look == null ? 0f : look.hueDegrees();
            this.saturation = look == null ? 1f : look.saturation();
            this.brightness = look == null ? 0f : look.brightness();
            this.contrast = look == null ? 1f : look.contrast();
            this.temperatureKelvin = 6500f + (look == null ? 0f : look.temperatureKelvin());
            this.opacity = look == null ? 1f : look.opacity();
            this.post = post;
            this.blurWidthFraction = look == null ? 0f : look.blurWidthFraction();
            this.steps = steps;
            this.mix = mix;
        }

        /** True when drawing it would change nothing (an effect whose look is neutral right now and that has no filters). */
        public boolean isNoOp() {
            return !lookPass && post == null && blurWidthFraction < 0.0005f && steps.isEmpty();
        }
    }

    /** One track's single frame layer: exactly one of simpleDraw / transition / effect is non-null. Order in the list returned by computeFrameForTimestamp is the actual draw order. */
    public static class FrameLayer {
        public final DrawCommand simpleDraw;
        public final TransitionCommand transition;
        /** An adjustment layer: filters everything drawn before it. */
        public final EffectCommand effect;
        private FrameLayer(DrawCommand simpleDraw, TransitionCommand transition, EffectCommand effect) {
            this.simpleDraw = simpleDraw;
            this.transition = transition;
            this.effect = effect;
        }
        static FrameLayer of(DrawCommand d) { return new FrameLayer(d, null, null); }
        static FrameLayer of(TransitionCommand t) { return new FrameLayer(null, t, null); }
        static FrameLayer of(EffectCommand e) { return new FrameLayer(null, null, e); }
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

            // Data-driven style in mode layers / mask (animations/transitions/*.json): BOTH pictures for the whole window.
            DataWindow both = findBothSidedWindow(track, outputTimeSeconds);
            if (both != null) {
                float p = both.window.progressAt(outputTimeSeconds);
                TransitionStyle style = both.style;
                // A mask style may leave a side out (that clip is then drawn as it is); a layers style always has both looks.
                ClipAnimationFrame fromLook = style.isMask() && style.getFrom().channels().isEmpty() ? null : style.getFrom().evaluate(p);
                ClipAnimationFrame toLook = style.isMask() && style.getTo().channels().isEmpty() ? null : style.getTo().evaluate(p);
                DrawCommand a = buildDrawCommand(both.clipA, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection,
                        fromLook, both.window);
                DrawCommand b = buildDrawCommand(both.clipB, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection,
                        toLook, null);
                if (a != null || b != null) {
                    if (style.isMask()) {
                        layers.add(FrameLayer.of(new TransitionCommand(a, b, new MaskSpec(style.getMask(), p), p)));
                    } else {
                        // B over A, the style's looks (slides, cross fades) do the rest.
                        if (a != null) layers.add(FrameLayer.of(a));
                        if (b != null) layers.add(FrameLayer.of(b));
                    }
                    continue;
                }
                // Neither side is drawable: fall through to the normal handling.
            }

            // Data-driven style (animations/transitions/*.json): one picture, A or B, with the style's channels merged in.
            DataDrivenHit dataHit = findDataDrivenHit(track, outputTimeSeconds);
            if (dataHit != null) {
                DrawCommand cmd = buildDrawCommand(dataHit.clip, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection,
                        dataHit.extra, dataHit.outWindow);
                if (cmd != null) {
                    layers.add(FrameLayer.of(cmd));
                    continue;
                }
                // Unsupported clip type on that side: fall through to the normal handling.
            }

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
            // An effect clip is an adjustment layer: it processes everything drawn on the tracks before it.
            if (activeClip.type == EditingActivity.ClipType.EFFECT) {
                EffectCommand effect = buildEffectCommand(activeClip, outputTimeSeconds, canvasWidth, canvasHeight, projection);
                if (effect != null && !effect.isNoOp()) layers.add(FrameLayer.of(effect));
                continue;
            }
            // Per-character / word / line text animation: one layer per unit, in reading order.
            List<DrawCommand> unitCommands = buildTextUnitCommands(activeClip, outputTimeSeconds, canvasWidth, canvasHeight, projection);
            if (unitCommands != null) {
                for (DrawCommand unit : unitCommands) layers.add(FrameLayer.of(unit));
                continue;
            }
            // A clip that ends in a data-driven transition runs its out animation over the transition's window.
            DrawCommand cmd = buildDrawCommand(activeClip, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection,
                    null, outgoingWindowOf(track, activeClip));
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

    // ---- clip in / out animations ---------------------------------------------------
    // Animations are data, not code: clip.inAnimation / clip.outAnimation .type is an id looked
    // up in ClipAnimationLoader (bundled assets/animations/*.json, see ClipAnimationAssets),
    // which gives back a ClipAnimation whose evaluate(p) returns every channel for this
    // frame. Plain Java, shared with the desktop port. This class only decides WHICH
    // progress p applies and how each channel combines with the clip's own properties
    // (see ClipAnimationFrame for the add / multiply / standalone rules).
    // The top-centre squish is applied in the fragment shader (OpenGLEditNative.UNFOLD_WARP_*)
    // as an inverse mapping with edge clamping, so the area the shrunken picture no
    // longer covers is filled with edge pixels rather than showing a gap.
    // An unknown type, or one of the wrong direction, animates nothing (the export reports
    // those up front - see OpenGLEditNative.prepareClipAnimations).

    /** The installed animation for one of a clip's two animation slots, or null (none / unknown / wrong direction). */
    private static ClipAnimation animationFor(EditingActivity.AnimationClip slot, ClipAnimation.Direction direction) {
        if (slot == null) return null;
        ClipAnimation def = ClipAnimationLoader.get(slot.type);
        return (def != null && def.getDirection() == direction) ? def : null;
    }

    /**
     * The clip's animation channel values at this output time (NEUTRAL when none is active).
     * The in window starts at the clip's first frame, the out window ends at its last; if the two
     * don't fit in the clip together both shrink proportionally, so they never overlap and at
     * most one is active at any time. The out animation is held at its end state past the clip's
     * nominal end (the outgoing clip of a transition keeps drawing there).
     */
    private ClipAnimationFrame animationFrame(EditingActivity.Clip clip, float outputTimeSeconds) {
        return animationFrame(clip, outputTimeSeconds, null);
    }

    /**
     * @param outWindow set while the clip is the outgoing side of a data-driven transition: its out animation then
     *                  runs over the transition's window (progress 0 at the window's start, 1 at its end) instead of
     *                  over its own last frames, like iOS. Without a transition (null) the rules above apply.
     */
    private ClipAnimationFrame animationFrame(EditingActivity.Clip clip, float outputTimeSeconds, TransitionTiming.Window outWindow) {
        ClipAnimation inDef = animationFor(clip.inAnimation, ClipAnimation.Direction.IN);
        ClipAnimation outDef = animationFor(clip.outAnimation, ClipAnimation.Direction.OUT);
        if (inDef == null && outDef == null) return ClipAnimationFrame.NEUTRAL;

        float inRaw = inDef != null ? clip.inAnimation.duration : 0f;
        float outRaw = outDef != null ? clip.outAnimation.duration : 0f;
        if (inDef != null) {
            float inDur = ClipAnimation.fitDuration(inRaw, outRaw, clip.duration);
            // 0 at clip start -> 1 at animation end, -1 outside the window
            float p = ClipAnimation.progress(outputTimeSeconds - clip.startTime, inDur);
            if (p >= 0f) return inDef.evaluate(p);
        }
        if (outDef != null) {
            float outDur, clipEnd;
            if (outWindow != null) {
                outDur = outWindow.duration();
                clipEnd = outWindow.end;
            } else {
                outDur = ClipAnimation.fitDuration(outRaw, inRaw, clip.duration);
                clipEnd = clip.startTime + clip.duration;
            }
            float p = ClipAnimation.progressOut(clipEnd, outputTimeSeconds, outDur);
            if (p >= 0f) return outDef.evaluate(p);
        }
        return ClipAnimationFrame.NEUTRAL;
    }

    /**
     * Per-unit text animation. When a TEXT clip's style animates per unit ("CHARACTER" / "WORD" / "LINE") and
     * its in or out animation window is open at t, returns one command per unit: the clip's own in / out
     * animation evaluated per unit, each starting a little after the previous one (stagger), the transform
     * applied about the unit's own centre on top of the clip's (keyframed) position, scale, rotation and pivot.
     * Returns null when the whole-block path should be used (not a text clip, no unit mode, outside the
     * animation windows - where every unit is neutral anyway - or no way to split the text).
     * Same maths as buildClipMvp, so units sit exactly where the whole block would put them.
     */
    private List<DrawCommand> buildTextUnitCommands(EditingActivity.Clip clip, float t, int canvasWidth, int canvasHeight,
                                                    float[] projection) {
        if (clip.type != EditingActivity.ClipType.TEXT || clip.textStyle == null || !clip.textStyle.animatesPerUnit()) return null;
        TextUnitProvider provider = textUnitProvider;
        TextMeasurer measurer = textMeasurer;
        if (provider == null || measurer == null) return null;

        ClipAnimation inDef = animationFor(clip.inAnimation, ClipAnimation.Direction.IN);
        ClipAnimation outDef = animationFor(clip.outAnimation, ClipAnimation.Direction.OUT);
        if (inDef == null && outDef == null) return null;
        float inRaw = inDef != null ? clip.inAnimation.duration : 0f;
        float outRaw = outDef != null ? clip.outAnimation.duration : 0f;
        float inDur = inDef != null ? ClipAnimation.fitDuration(inRaw, outRaw, clip.duration) : 0f;
        float outDur = outDef != null ? ClipAnimation.fitDuration(outRaw, inRaw, clip.duration) : 0f;
        float clipEnd = clip.startTime + clip.duration;
        boolean inOpen = inDef != null && inDur > 0f && t - clip.startTime >= 0f && t - clip.startTime < inDur;
        boolean outOpen = outDef != null && outDur > 0f && t >= clipEnd - outDur;
        if (!inOpen && !outOpen) return null;

        int[] size = measurer.measure(clip);
        if (size == null) return null;
        List<TextUnit> units = provider.units(clip, clip.textStyle.unitMode);
        if (units == null || units.isEmpty()) return null;
        int n = units.size();

        // Block transform: the same quantities buildClipMvp reads, with the clip-level animation left out.
        float textW = size[0], textH = size[1];
        float scaleX = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.ScaleX);
        float scaleY = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.ScaleY);
        float posX = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.PosX) + (canvasWidth - textW) / 2f;
        float posY = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.PosY) + (canvasHeight - textH) / 2f;
        float pivotX = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.PivotX);
        float pivotY = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.PivotY);
        float blockRot = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.RotInRadians);
        float cos = (float) Math.cos(blockRot), sin = (float) Math.sin(blockRot);
        float pivotCanvasX = posX + pivotX * textW;
        float pivotCanvasY = posY + pivotY * textH;

        float opacityBase = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.Opacity);
        float hueBase = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.Hue);
        float satBase = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.Saturation);
        float brightBase = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.Brightness);
        float tempBase = readAtTime(clip, t, EditingActivity.VideoProperties.ValueType.Temperature);

        float stagger = Math.max(0f, Math.min(0.95f, clip.textStyle.stagger));
        int[] rank = unitRanks(n, clip.textStyle.order, clip);

        List<DrawCommand> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            TextUnit u = units.get(i);
            float frac = n > 1 ? rank[i] / (float) (n - 1) : 0f;

            ClipAnimationFrame anim = ClipAnimationFrame.NEUTRAL;
            if (inOpen) {
                float window = inDur;
                float delay = stagger * window * frac;
                float length = Math.max(window * (1f - stagger), 1e-3f);
                float e = (t - clip.startTime) - delay;
                if (e < 0f) anim = inDef.evaluate(0f);                 // not started yet: held at the first frame
                else if (e < length) anim = inDef.evaluate(e / length);
                // else: finished, neutral
            } else {
                float window = outDur;
                float delay = stagger * window * frac;
                float length = Math.max(window * (1f - stagger), 1e-3f);
                float e = t - (clipEnd - window) - delay;
                if (e >= length) anim = outDef.evaluate(1f);           // finished: held at the last frame
                else if (e >= 0f) anim = outDef.evaluate(e / length);
                // else: not started, neutral
            }

            float ux = u.x + u.w / 2f, uy = u.y + u.h / 2f;
            float dx = (ux - pivotX * textW) * scaleX;
            float dy = (uy - pivotY * textH) * scaleY;
            float centerX = pivotCanvasX + dx * cos - dy * sin + anim.offsetX() * canvasWidth;
            float centerY = pivotCanvasY + dx * sin + dy * cos + anim.offsetY() * canvasHeight;
            float rot = blockRot + (float) Math.toRadians(anim.rotationDegrees());
            float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
            float halfW = u.w * scaleX * anim.scale() / 2f;
            float halfH = u.h * scaleY * anim.scale() / 2f;

            float[] model = new float[16];
            model[0] = halfW * c;   model[1] = halfW * s;   model[2] = 0; model[3] = 0;
            model[4] = -halfH * s;  model[5] = halfH * c;   model[6] = 0; model[7] = 0;
            model[8] = 0;           model[9] = 0;           model[10] = 1; model[11] = 0;
            model[12] = centerX;    model[13] = centerY;    model[14] = 0; model[15] = 1;
            float[] mvp = new float[16];
            multiplyMM(mvp, projection, model);

            out.add(new DrawCommand(clip, 0f, mvp,
                    opacityBase * anim.opacity(),
                    hueBase + anim.hueDegrees(),
                    satBase * anim.saturation(),
                    brightBase + anim.brightness(),
                    tempBase + anim.temperatureKelvin(),
                    0f, // blur is not applied per unit
                    anim.warpTopWidth(), anim.warpBottomWidth(), anim.warpHeight(), anim.contrast(),
                    u.text));
        }
        return out;
    }

    /** rank[i] = when unit i starts, 0 = first. Deterministic: the same clip always shuffles the same way. */
    private static int[] unitRanks(int n, String order, EditingActivity.Clip clip) {
        int[] rank = new int[n];
        String o = order == null ? "FORWARD" : order;
        if (o.equals("REVERSE")) {
            for (int i = 0; i < n; i++) rank[i] = n - 1 - i;
        } else if (o.equals("CENTER_OUT")) {
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            final float mid = (n - 1) / 2f;
            java.util.Arrays.sort(idx, (a, b) -> {
                int byDistance = Float.compare(Math.abs(a - mid), Math.abs(b - mid));
                return byDistance != 0 ? byDistance : Integer.compare(a, b);
            });
            for (int r = 0; r < n; r++) rank[idx[r]] = r;
        } else if (o.equals("RANDOM")) {
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            java.util.Collections.shuffle(java.util.Arrays.asList(idx),
                    new java.util.Random((clip.textContent == null ? 0 : clip.textContent.hashCode()) * 31L + n));
            for (int r = 0; r < n; r++) rank[idx[r]] = r;
        } else {
            for (int i = 0; i < n; i++) rank[i] = i;
        }
        return rank;
    }

    /**
     * Evaluates an effect clip at outputTimeSeconds (null: no effect object, or a style this build doesn't know, which
     * every platform leaves alone). Strength is the static {@code params["intensity"]}, or the keyframed value (K2).
     */
    private EffectCommand buildEffectCommand(EditingActivity.Clip clip, float outputTimeSeconds, int canvasWidth, int canvasHeight,
                                             float[] projection) {
        if (clip.effect == null || clip.effect.style == null) return null;
        EffectStyle style = EffectStyleLoader.get(EffectStyleLoader.canonical(clip.effect.style));
        if (style == null) return null;

        float strength = 1f;
        if (clip.effect.params != null && clip.effect.params.get("intensity") instanceof Number) {
            strength = ((Number) clip.effect.params.get("intensity")).floatValue();
        }
        // Keyframed strength (K2): when some keyframe holds it, it is read per frame like any other animated property.
        if (clip.keyframes != null && clip.keyframes.isAnimated(EditingActivity.VideoProperties.ValueType.Strength)) {
            strength = clip.keyframes.getValueAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Strength);
        }
        strength = Math.max(strength, 0f);
        float elapsed = Math.max(outputTimeSeconds - clip.startTime, 0f);
        float duration = clip.duration;

        ClipAnimationFrame look = style.hasChannels() ? style.frameAt(elapsed, duration, strength) : null;
        float[] mvp = null;
        PostFx post = null;
        boolean lookPass = false;
        if (look != null) {
            mvp = effectLookMvp(look, projection, canvasWidth, canvasHeight);
            post = PostFx.from(look, mvp, canvasWidth, canvasHeight);
            lookPass = Math.abs(look.scale() - 1f) > 1e-4f || Math.abs(look.rotationDegrees()) > 1e-3f
                    || Math.abs(look.offsetX()) > 1e-5f || Math.abs(look.offsetY()) > 1e-5f
                    || Math.abs(look.hueDegrees()) > 1e-3f || Math.abs(look.saturation() - 1f) > 1e-4f
                    || Math.abs(look.brightness()) > 1e-4f || Math.abs(look.contrast() - 1f) > 1e-4f
                    || Math.abs(look.temperatureKelvin()) > 0.5f || look.opacity() < 0.9999f;
            // (The clip-only "warp" channels have no meaning for a whole picture and no bundled effect uses them.)
        }

        List<FilterStep> steps = new ArrayList<>();
        int randomStep = (int) Math.floor(Math.max(outputTimeSeconds, 0f) * 15.0); // random patterns jump 15 times a second
        for (EffectFilterOps.Filter filter : style.getFilters()) {
            if (!SUPPORTED_EFFECT_OPS.contains(filter.getOp())) continue;
            java.util.Map<String, Double> values = new java.util.HashMap<>();
            for (EffectFilterOps.Param param : filter.getSpec().params) {
                values.put(param.name, style.filterValue(filter, param.name, elapsed, duration, strength));
            }
            steps.add(new FilterStep(filter.getOp(), filter.getString("name"), filter.hasFlag("randomSign"), randomStep, values));
        }
        return new EffectCommand(clip, style, strength, elapsed, duration, outputTimeSeconds, look, lookPass, mvp, post,
                steps, style.filterMix(strength));
    }

    /** iOS EffectRenderer.random: a hash of (step, salt) in 0..1. */
    public static float random(int step, double salt) {
        double x = Math.sin(step * 12.9898 + salt * 78.233) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    /**
     * Where the canvas-sized picture goes under an effect's look: scale and rotation about the CANVAS CENTRE, then the
     * offset (fractions of the canvas, + = right / down). Same conventions as buildClipMvp, so a look moves a picture
     * exactly like the same channels move a clip.
     */
    private static float[] effectLookMvp(ClipAnimationFrame look, float[] projection, int canvasWidth, int canvasHeight) {
        float scale = look.scale();
        float rot = (float) Math.toRadians(look.rotationDegrees());
        float cos = (float) Math.cos(rot), sin = (float) Math.sin(rot);
        float halfW = canvasWidth * scale / 2f, halfH = canvasHeight * scale / 2f;
        float[] model = new float[16];
        model[0] = halfW * cos;   model[1] = halfW * sin;
        model[4] = -halfH * sin;  model[5] = halfH * cos;
        model[10] = 1;
        model[12] = canvasWidth / 2f + look.offsetX() * canvasWidth;
        model[13] = canvasHeight / 2f + look.offsetY() * canvasHeight;
        model[15] = 1;
        float[] mvp = new float[16];
        multiplyMM(mvp, projection, model);
        return mvp;
    }

    /** Builds one clip's complete draw info at outputTimeSeconds, or null if its type isn't drawable (audio/text/effect/3D — see getUnsupportedFeatures). */
    private DrawCommand buildDrawCommand(EditingActivity.Clip clip, float outputTimeSeconds,
                                          int canvasWidth, int canvasHeight, boolean stretchToFull, float[] projection) {
        return buildDrawCommand(clip, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection, null, null);
    }

    /**
     * @param extraAnim  a data-driven transition style's channels for this frame, merged into the clip's own animation
     *                   frame (see ClipAnimationFrame.merged), or null
     * @param outWindow  the window of the data-driven transition this clip is the outgoing side of, or null (see animationFrame)
     */
    private DrawCommand buildDrawCommand(EditingActivity.Clip clip, float outputTimeSeconds,
                                          int canvasWidth, int canvasHeight, boolean stretchToFull, float[] projection,
                                          ClipAnimationFrame extraAnim, TransitionTiming.Window outWindow) {
        boolean isText = clip.type == EditingActivity.ClipType.TEXT;
        if (!isText && clip.type != EditingActivity.ClipType.VIDEO && clip.type != EditingActivity.ClipType.IMAGE) {
            return null; // audio has no picture; effects/3D: see getUnsupportedFeatures
        }
        // Text is a quad the size of its rasterised text block (not the clip's own width/height,
        // which is 0 for text, and never stretched to the canvas).
        float textW = 0f, textH = 0f;
        if (isText) {
            TextMeasurer measurer = textMeasurer;
            int[] size = measurer == null ? null : measurer.measure(clip);
            if (size == null) return null; // empty text (or no measurer): nothing to draw
            textW = size[0];
            textH = size[1];
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

        // Every channel is neutral outside the animation window (the shared NEUTRAL frame, no
        // allocation), so the overwhelming majority of frames pay nothing for this.
        ClipAnimationFrame anim = animationFrame(clip, outputTimeSeconds, outWindow);
        if (extraAnim != null) anim = anim.merged(extraAnim);

        float[] mvp = buildClipMvp(clip, outputTimeSeconds, projection, canvasWidth, canvasHeight, stretchToFull, anim, textW, textH);
        float opacity = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Opacity)
                * anim.opacity();
        float hue = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Hue)
                + anim.hueDegrees();
        float saturation = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Saturation)
                * anim.saturation();
        float brightness = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Brightness)
                + anim.brightness();
        float temperature = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.Temperature)
                + anim.temperatureKelvin();
        float blurSigmaPixels = anim.blurWidthFraction() * canvasWidth;

        DrawCommand command = new DrawCommand(clip, localSourceTime, mvp, opacity, hue, saturation, brightness, temperature, blurSigmaPixels,
                anim.warpTopWidth(), anim.warpBottomWidth(), anim.warpHeight(), anim.contrast());
        command.post = PostFx.from(anim, mvp, canvasWidth, canvasHeight);
        return command;
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

    /**
     * The clip drawn on this track at t when no transition window is open. A clip is visible from its own start to its
     * own end, EXCEPT next to a data-driven transition: the outgoing clip A is visible only until the window's end
     * (END_FIRST: gone before the cut; BEGIN_SECOND: it plays on past the cut) and the incoming clip B from the
     * window's start (before its own start: the pre-roll). First match in list order.
     */
    private EditingActivity.Clip findActiveClip(EditingActivity.Track track, float t) {
        for (int i = 0; i < track.clips.size(); i++) {
            EditingActivity.Clip clip = track.clips.get(i);
            if (clip == null) continue;
            float visibleStart = clip.startTime;
            float visibleEnd = clip.startTime + clip.duration;
            if (i > 0) {
                DataWindow incoming = dataWindowAt(track, i - 1);
                if (incoming != null) visibleStart = incoming.window.start;
            }
            DataWindow outgoing = dataWindowAt(track, i);
            if (outgoing != null) visibleEnd = outgoing.window.end;
            if (t >= visibleStart && t < visibleEnd) {
                return clip;
            }
        }
        return null;
    }

    // ---- data-driven transitions (TransitionStyleLoader / TransitionTiming) --------------

    /** A data-driven transition between track.clips[i] (A) and track.clips[i + 1] (B), already placed on the timeline. */
    private static final class DataWindow {
        final EditingActivity.Clip clipA, clipB;
        final TransitionStyle style;
        final TransitionTiming.Window window;
        DataWindow(EditingActivity.Clip clipA, EditingActivity.Clip clipB, TransitionStyle style, TransitionTiming.Window window) {
            this.clipA = clipA; this.clipB = clipB; this.style = style; this.window = window;
        }
    }

    /** What to draw inside a data-driven window: ONE of the two clips, with the style's channels for this frame. */
    private static final class DataDrivenHit {
        final EditingActivity.Clip clip;
        final ClipAnimationFrame extra;
        /** The window, when {@code clip} is the outgoing side (A); null for B. */
        final TransitionTiming.Window outWindow;
        DataDrivenHit(EditingActivity.Clip clip, ClipAnimationFrame extra, TransitionTiming.Window outWindow) {
            this.clip = clip; this.extra = extra; this.outWindow = outWindow;
        }
    }

    /** The window of the data-driven transition that ends this clip, or null (no such transition). */
    private static TransitionTiming.Window outgoingWindowOf(EditingActivity.Track track, EditingActivity.Clip clip) {
        for (int i = 0; i < track.clips.size() - 1; i++) {
            if (track.clips.get(i) != clip) continue;
            DataWindow w = dataWindowAt(track, i);
            return w == null ? null : w.window;
        }
        return null;
    }

    private static boolean isPicture(EditingActivity.Clip clip) {
        return clip != null && (clip.type == EditingActivity.ClipType.VIDEO || clip.type == EditingActivity.ClipType.IMAGE);
    }

    /**
     * The data-driven transition at the end of track.clips[i], or null: there is none, its style is not data-driven,
     * one side is not a picture (video / image), B does not start where A ends (within
     * {@link TransitionTiming#ADJACENT_TOLERANCE_SECONDS}), or it is too short to draw once limited to what fits.
     */
    private static DataWindow dataWindowAt(EditingActivity.Track track, int i) {
        if (i < 0 || i >= track.clips.size() - 1) return null;
        EditingActivity.Clip a = track.clips.get(i);
        EditingActivity.Clip b = track.clips.get(i + 1);
        if (a == null || b == null || !a.isEndTransitionEnabled() || a.endTransition == null) return null;
        EditingActivity.TransitionClip transition = a.endTransition;
        if (transition.effect == null) return null;
        TransitionStyle style = TransitionStyleLoader.get(transition.effect.style);
        if (style == null || !isPicture(a) || !isPicture(b)) return null;
        if (a.duration <= 0f || b.duration <= 0f) return null;
        if (Math.abs(b.startTime - (a.startTime + a.duration)) > TransitionTiming.ADJACENT_TOLERANCE_SECONDS) return null;
        TransitionTiming.Mode mode = transition.mode == null ? TransitionTiming.Mode.OVERLAP
                : TransitionTiming.Mode.valueOf(transition.mode.name());
        TransitionTiming.Window window = TransitionTiming.window(mode, a.startTime, a.duration, b.duration, transition.duration);
        return window == null ? null : new DataWindow(a, b, style, window);
    }

    /** The layers / mask transition whose window contains t on this track (both clips are drawn for all of it), or null. */
    private static DataWindow findBothSidedWindow(EditingActivity.Track track, float t) {
        for (int i = 0; i < track.clips.size() - 1; i++) {
            DataWindow w = dataWindowAt(track, i);
            if (w != null && w.window.contains(t) && (w.style.isLayers() || w.style.isMask())) return w;
        }
        return null;
    }

    /** The picture to draw at t if t is inside a data-driven transition window of this track, else null. */
    private static DataDrivenHit findDataDrivenHit(EditingActivity.Track track, float t) {
        for (int i = 0; i < track.clips.size() - 1; i++) {
            DataWindow w = dataWindowAt(track, i);
            if (w == null || !w.window.contains(t)) continue;
            if (w.style.isLayers() || w.style.isMask()) continue; // drawn by findBothSidedWindow
            float progress = w.window.progressAt(t);
            boolean showA = w.style.showsFrom(progress);
            return new DataDrivenHit(showA ? w.clipA : w.clipB, w.style.frameAt(progress), showA ? w.window : null);
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
                                  int canvasWidth, int canvasHeight, boolean stretchToFull, ClipAnimationFrame anim,
                                  float textW, float textH) {
        // The in-animation's scale multiplies the clip's own (about its pivot), its offset is a
        // fraction of the canvas size added to PosX/PosY, its rotation is added to RotInRadians.
        float scaleX = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.ScaleX) * anim.scale();
        float scaleY = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.ScaleY) * anim.scale();
        float posX = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.PosX) + anim.offsetX() * canvasWidth;
        float posY = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.PosY) + anim.offsetY() * canvasHeight;
        float pivotX = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.PivotX);
        float pivotY = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.PivotY);
        float rotRadians = readAtTime(clip, outputTimeSeconds, EditingActivity.VideoProperties.ValueType.RotInRadians)
                + (float) Math.toRadians(anim.rotationDegrees());

        // Stretch-to-fit: matches FFmpegEdit's scale=w=(stretchToFull ? renderWidth
        // : iw)*ScaleX:h=(stretchToFull ? renderHeight : ih)*ScaleY (FFmpegEdit.java,
        // scaleXStretchExpr/scaleYStretchExpr) - the OUTPUT canvas size replaces the
        // clip's own intrinsic size as the base ScaleX/ScaleY multiplies against.
        float baseW = stretchToFull ? canvasWidth : clip.width;
        float baseH = stretchToFull ? canvasHeight : clip.height;
        if (textW > 0f) {
            // TEXT: the text block is centred on the canvas at PosX/PosY = 0 and PosX/PosY offset it,
            // exactly like the FFmpeg export's drawtext x=(w-text_w)/2+PosX, y=(h-text_h)/2+PosY.
            baseW = textW;
            baseH = textH;
            posX += (canvasWidth - textW) / 2f;
            posY += (canvasHeight - textH) / 2f;
        }
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
