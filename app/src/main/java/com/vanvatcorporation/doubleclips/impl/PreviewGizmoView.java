package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.Clip;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.ClipType;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.EasingType;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.Keyframe;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.Track;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.VideoProperties;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.VideoProperties.ValueType;
import com.vanvatcorporation.doubleclips.constants.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * On-canvas editing for the GPU preview (Android counterpart of the desktop PreviewGizmo and iOS
 * PreviewInteractionLayer):
 * <ul>
 *   <li>touching a clip selects it; dragging inside the selected clip moves it</li>
 *   <li>the four corner handles scale it uniformly, keeping the opposite corner where it is</li>
 *   <li>the four edge handles (shown when the edge is long enough to grab) stretch one axis, in the clip's own
 *       rotated frame, keeping the opposite edge where it is</li>
 *   <li>moving and scaling snap to the canvas edges / centre and to the edges / centre of the other visible
 *       clips, within a few screen pixels, with pink guide lines (switch it off in the preview settings)</li>
 *   <li>the round handle above the top edge rotates it around its visual centre, snapping to 90&deg;</li>
 *   <li>a two-finger pinch scales it about its centre</li>
 * </ul>
 * Every gesture is ONE undo step. If the clip has keyframes the gesture edits the keyframe at the
 * playhead, inserting one first (from the current interpolated look) when there is none - otherwise
 * interpolation would overwrite the change.
 * <p>
 * The view is a transparent overlay that sits OUTSIDE the scaled canvas pane, so handles keep a
 * constant on-screen size and can be grabbed even when they lie beyond the canvas edge. All clip
 * maths is in canvas pixels (the pane's own coordinate system); the pane's view matrix converts
 * to and from overlay pixels. Only VIDEO and IMAGE clips are handled; text and 3D keep their own
 * per-clip gestures, and touches that land on one of those views are passed through.
 */
public final class PreviewGizmoView extends View {

    /** What the editor has to provide. */
    public interface Host {
        EditingActivity.Timeline timeline();
        float currentTime();
        boolean isPlaying();
        boolean isMultiSelect();
        /** The single selected clip, or null. */
        Clip selectedClip();
        /** Select exactly this clip without moving the playhead. */
        void selectClip(Clip clip);
        /** Runs {@code redo} now and records one undo step. */
        void commit(String name, Runnable redo, Runnable undo);
        /** Cheap re-render of the preview at the current time (live dragging). */
        void requestRender();
        /** A clip's data changed outside a live drag (commit, undo, redo, cancel): redraw and refresh. */
        void clipChanged(Clip clip);
        void addKeyframe(Clip clip, Keyframe keyframe);
        void removeKeyframe(Clip clip, Keyframe keyframe);
        /** True if one of the other preview views (text, 3D) is under this canvas point and wants the touch. */
        boolean claimedByOtherView(float canvasX, float canvasY);
        /** Short readout while a gesture is running. */
        void showInfo(String text);
    }

    private static final float HANDLE_RADIUS_DP = 6f;
    private static final float HANDLE_HIT_DP = 24f;
    private static final float ROTATE_OFFSET_DP = 36f;
    private static final float MIN_SCALE_FACTOR = 0.02f;
    private static final int HANDLE_ROTATE = 100;
    /** Handle ids: 0-3 are the corners (TL, TR, BR, BL), 4-7 the edge midpoints (top, right, bottom, left). */
    private static final int H_TOP = GizmoMath.H_TOP;
    /** Edge handles only appear when the edge is at least this long on screen (they'd sit on the corners otherwise). */
    private static final float EDGE_MIN_DP = 64f;
    private static final float EDGE_HIT_DP = 20f;
    private static final float EDGE_PILL_LENGTH_DP = 20f;
    private static final float EDGE_PILL_THICKNESS_DP = 7f;
    /** Snapping pulls within this many SCREEN dp, so it feels the same at any preview zoom. */
    private static final float SNAP_DP = 8f;

    private enum Mode { NONE, MOVE, SCALE, ROTATE }

    private final Host host;
    private final View canvasView;
    private final int canvasW, canvasH;
    private final boolean stretch;
    private final int frameRate;
    private final float density;
    private final float touchSlop;

    private final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix inverse = new Matrix();
    private final float[] pt = new float[2];

    private final ScaleGestureDetector pinchDetector;

    // ---- gesture state ----
    private Mode mode = Mode.NONE;
    private Clip target;
    private VideoProperties basis;     // values the gesture started from (static or keyframe)
    private VideoProperties live;      // values currently shown
    private int keyIndex = -1;         // keyframe being edited, or -1
    private Snapshot before;
    private float pressCanvasX, pressCanvasY, pressOverlayX, pressOverlayY;
    private int draggedHandle = -1;
    private double[] startQuad;
    private double startAngle;
    private double[] startCenter;
    private boolean pinching;
    private boolean pastSlop;
    private boolean changed;
    private boolean ignoreUntilUp;
    private float pinchFactor = 1f;

    // Snapping: the lines a gesture can snap to (canvas edges/centre + other visible clips), fixed at press,
    // and the guides currently shown (NaN = none).
    private boolean snapEnabled = true;
    private final List<Double> snapXs = new ArrayList<>();
    private final List<Double> snapYs = new ArrayList<>();
    private double guideX = Double.NaN, guideY = Double.NaN;
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PreviewGizmoView(Context context, Host host, View canvasView, int canvasW, int canvasH, boolean stretch, int frameRate) {
        super(context);
        this.host = host;
        this.canvasView = canvasView;
        this.canvasW = Math.max(1, canvasW);
        this.canvasH = Math.max(1, canvasH);
        this.stretch = stretch;
        this.frameRate = frameRate;
        this.density = context.getResources().getDisplayMetrics().density;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        dark.setStyle(Paint.Style.STROKE);
        dark.setColor(0x99000000);
        dark.setStrokeWidth(3.5f * density);
        light.setStyle(Paint.Style.STROKE);
        light.setColor(0xFFFFFFFF);
        light.setStrokeWidth(1.5f * density);
        light.setPathEffect(new DashPathEffect(new float[]{6f * density, 4f * density}, 0));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x99000000);
        ring.setStrokeWidth(1.2f * density);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setColor(0xFFFF2D95);
        guidePaint.setStrokeWidth(1.5f * density);

        setWillNotDraw(false);
        setClickable(false);

        pinchDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                Clip sel = host.selectedClip();
                if (host.isPlaying() || !isGizmoClip(sel) || !isActive(sel, host.currentTime())) return false;
                if (mode != Mode.NONE) finishGesture(); // a half-done move becomes its own undo step
                beginGesture(sel, Mode.SCALE, 0f, 0f, 0f, 0f, -1);
                clearGuides(); // a pinch doesn't snap
                pinching = true;
                pinchFactor = 1f;
                ignoreUntilUp = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (!pinching || mode != Mode.SCALE) return false;
                pinchFactor = Math.max(MIN_SCALE_FACTOR, pinchFactor * d.getScaleFactor());
                scaleAboutCenter(pinchFactor);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (pinching) {
                    pinching = false;
                    finishGesture();
                }
            }
        });
    }

    /** Snap moves and scales to the canvas and to other clips (preview setting "Snap to guides"). */
    public void setSnapEnabled(boolean enabled) {
        this.snapEnabled = enabled;
        if (!enabled) clearGuides();
    }

    /** Call whenever time, selection or layout changes. Cheap: the box is recomputed in onDraw. */
    public void refresh() {
        invalidate();
    }

    // ======================================================================
    //  Drawing
    // ======================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        Clip clip = mode != Mode.NONE ? target : host.selectedClip();
        if (!isGizmoClip(clip) || host.isMultiSelect() || !isActive(clip, host.currentTime())) return;
        VideoProperties p = (mode != Mode.NONE && live != null) ? live : resolve(clip, host.currentTime());
        double[] quad = quadFor(clip, p);
        if (quad == null) return;

        float[] s = overlayCorners(quad);
        path.reset();
        path.moveTo(s[0], s[1]);
        path.lineTo(s[2], s[3]);
        path.lineTo(s[4], s[5]);
        path.lineTo(s[6], s[7]);
        path.close();
        canvas.drawPath(path, dark);
        canvas.drawPath(path, light);

        if (mode != Mode.NONE) drawGuides(canvas);

        float[] h = rotateHandle(s);
        float mx = (s[0] + s[2]) / 2f, my = (s[1] + s[3]) / 2f;
        canvas.drawLine(mx, my, h[0], h[1], dark);
        canvas.drawLine(mx, my, h[0], h[1], solid(light));

        drawEdgePills(canvas, s);

        float r = HANDLE_RADIUS_DP * density;
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(s[i * 2], s[i * 2 + 1], r, fill);
            canvas.drawCircle(s[i * 2], s[i * 2 + 1], r, ring);
        }
        canvas.drawCircle(h[0], h[1], r * 1.15f, fill);
        canvas.drawCircle(h[0], h[1], r * 1.15f, ring);
    }

    /** A short pill on the middle of each edge that is long enough, lying along the edge. */
    private void drawEdgePills(Canvas canvas, float[] s) {
        float half = EDGE_PILL_LENGTH_DP * density / 2f, thick = EDGE_PILL_THICKNESS_DP * density / 2f;
        for (int i = 0; i < 4; i++) {
            if (!edgeVisible(s, i)) continue;
            int j = (i + 1) % 4;
            float mx = (s[i * 2] + s[j * 2]) / 2f, my = (s[i * 2 + 1] + s[j * 2 + 1]) / 2f;
            float deg = (float) Math.toDegrees(Math.atan2(s[j * 2 + 1] - s[i * 2 + 1], s[j * 2] - s[i * 2]));
            canvas.save();
            canvas.translate(mx, my);
            canvas.rotate(deg);
            canvas.drawRoundRect(-half, -thick, half, thick, thick, thick, fill);
            canvas.drawRoundRect(-half, -thick, half, thick, thick, thick, ring);
            canvas.restore();
        }
    }

    /** Edge {@code i} (corner i to corner i+1) is long enough on screen to carry a handle. */
    private boolean edgeVisible(float[] s, int i) {
        int j = (i + 1) % 4;
        return Math.hypot(s[j * 2] - s[i * 2], s[j * 2 + 1] - s[i * 2 + 1]) >= EDGE_MIN_DP * density;
    }

    /** Snap guides: a line across the whole canvas through the coordinate the gesture is snapped to. */
    private void drawGuides(Canvas canvas) {
        if (!Double.isNaN(guideX)) {
            float[] a = toOverlay(guideX, 0), b = toOverlay(guideX, canvasH);
            canvas.drawLine(a[0], a[1], b[0], b[1], guidePaint);
        }
        if (!Double.isNaN(guideY)) {
            float[] a = toOverlay(0, guideY), b = toOverlay(canvasW, guideY);
            canvas.drawLine(a[0], a[1], b[0], b[1], guidePaint);
        }
    }

    private final Paint solidLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The light stroke without its dash effect (for the rotate stem). */
    private Paint solid(Paint from) {
        solidLine.setStyle(Paint.Style.STROKE);
        solidLine.setColor(from.getColor());
        solidLine.setStrokeWidth(from.getStrokeWidth());
        return solidLine;
    }

    /** The rotate handle sits ROTATE_OFFSET beyond the middle of the top edge, away from the centre. */
    private float[] rotateHandle(float[] s) {
        float mx = (s[0] + s[2]) / 2f, my = (s[1] + s[3]) / 2f;
        float cx = (s[0] + s[4]) / 2f, cy = (s[1] + s[5]) / 2f;
        float dx = mx - cx, dy = my - cy;
        float len = (float) Math.hypot(dx, dy);
        float off = ROTATE_OFFSET_DP * density;
        if (len < 1e-3f) return new float[]{mx, my - off};
        return new float[]{mx + dx / len * off, my + dy / len * off};
    }

    // ======================================================================
    //  Touch
    // ======================================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinchDetector.onTouchEvent(e);
        int action = e.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            ignoreUntilUp = false;
            return onDown(e);
        }
        if (pinching || e.getPointerCount() > 1) return true; // the pinch detector owns it
        if (ignoreUntilUp) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoreUntilUp = false;
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (mode != Mode.NONE) onMove(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if (mode != Mode.NONE) finishGesture();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mode != Mode.NONE) cancel();
                return true;
            default:
                return true;
        }
    }

    private boolean onDown(MotionEvent e) {
        if (host.isPlaying() || host.isMultiSelect() || mode != Mode.NONE || host.timeline() == null) return false;
        float time = host.currentTime();
        float ox = e.getX(), oy = e.getY();
        float[] c = toCanvas(ox, oy);
        if (c == null) return false;

        Clip sel = host.selectedClip();
        Mode startMode = Mode.NONE;
        int corner = -1;
        Clip chosen = null;

        if (isGizmoClip(sel) && isActive(sel, time)) {
            int handle = handleAt(sel, ox, oy);
            if (handle == HANDLE_ROTATE) {
                startMode = Mode.ROTATE;
                chosen = sel;
            } else if (handle >= 0) {
                startMode = Mode.SCALE;
                corner = handle;
                chosen = sel;
            }
        }
        if (chosen == null) {
            // Text / 3D views live above the GL surface: let them have their own touches.
            if (host.claimedByOtherView(c[0], c[1])) return false;
            List<Clip> hits = hitsAt(c[0], c[1]);
            if (hits.isEmpty()) {
                // Nothing under the finger. Keep the touch if a clip is selected so a pinch can start.
                return isGizmoClip(sel) && isActive(sel, time);
            }
            // Dragging inside the selected clip never grabs one stacked above it.
            chosen = (sel != null && hits.contains(sel)) ? sel : hits.get(0);
            startMode = Mode.MOVE;
            if (chosen != sel) host.selectClip(chosen);
        }
        beginGesture(chosen, startMode, c[0], c[1], ox, oy, corner);
        return true;
    }

    private void onMove(float ox, float oy) {
        if (pinching) return;
        float[] c = toCanvas(ox, oy);
        if (c == null) return;
        switch (mode) {
            case MOVE: {
                if (!pastSlop) {
                    if (Math.hypot(ox - pressOverlayX, oy - pressOverlayY) < touchSlop) return; // still a tap
                    pastSlop = true;
                }
                VideoProperties v = new VideoProperties(basis);
                v.valuePosX = basis.valuePosX + (c[0] - pressCanvasX);
                v.valuePosY = basis.valuePosY + (c[1] - pressCanvasY);
                if (snapEnabled) snapMove(v); else clearGuides();
                show(v);
                break;
            }
            case SCALE:
                scaleTo(c[0], c[1]);
                break;
            case ROTATE:
                rotateTo(c[0], c[1]);
                break;
            default:
                break;
        }
    }

    // ======================================================================
    //  Gesture lifecycle
    // ======================================================================

    private void beginGesture(Clip clip, Mode startMode, float canvasX, float canvasY, float overlayX, float overlayY, int corner) {
        this.target = clip;
        this.mode = startMode;
        this.pressCanvasX = canvasX;
        this.pressCanvasY = canvasY;
        this.pressOverlayX = overlayX;
        this.pressOverlayY = overlayY;
        this.draggedHandle = corner;
        this.changed = false;
        this.pastSlop = false;
        this.before = Snapshot.of(clip);

        float time = host.currentTime();
        VideoProperties resolved = resolve(clip, time);
        keyIndex = -1;
        if (clip.keyframes != null && !clip.keyframes.keyframes.isEmpty()) {
            Keyframe at = clip.keyframes.getKeyframeAtTime(clip, time);
            if (at != null) {
                keyIndex = clip.keyframes.keyframes.indexOf(at);
                basis = new VideoProperties(at.value);
            } else {
                basis = resolved; // a keyframe is inserted from this look on the first real change
            }
        } else {
            basis = new VideoProperties(clip.videoProperties);
        }
        live = new VideoProperties(basis);

        clearGuides();
        if (snapEnabled) collectSnapLines(clip);
        startQuad = quadFor(clip, resolved);
        if (startQuad != null) {
            startCenter = new double[]{(startQuad[0] + startQuad[4]) / 2, (startQuad[1] + startQuad[5]) / 2};
            startAngle = Math.atan2(canvasY - startCenter[1], canvasX - startCenter[0]);
        }
        invalidate();
    }

    /** Applies in-progress values to the clip; the first real change also inserts the keyframe if one is needed. */
    private void show(VideoProperties v) {
        if (target == null) return;
        if (!changed && sameTransform(v, basis)) {
            live = v;
            invalidate();
            return;
        }
        if (!changed) {
            changed = true;
            if (target.keyframes != null && !target.keyframes.keyframes.isEmpty() && keyIndex < 0) {
                if (!insertKeyframe()) { // could not place a keyframe here: drop the gesture
                    cancel();
                    return;
                }
            }
        }
        live = v;
        writeProperties(target, v, keyIndex);
        host.requestRender();
        host.showInfo(String.format(Locale.US, "Pos X: %.0f | Pos Y: %.0f\nScale X: %.2f | Scale Y: %.2f | Rot: %.1f",
                v.valuePosX, v.valuePosY, v.valueScaleX, v.valueScaleY, v.valueRot));
        invalidate();
    }

    /** Adds a keyframe at the playhead from the current interpolated look (gesture-owned; undo removes it). */
    private boolean insertKeyframe() {
        Clip clip = target;
        float localTime = Math.max(0f, Math.min(host.currentTime() - clip.startTime, clip.duration));
        EasingType easing = EasingType.LINEAR; // keep the curve the new keyframe splits
        for (Keyframe k : clip.keyframes.keyframes) {
            if (k.getLocalTime() <= localTime) easing = k.easing;
        }
        Keyframe kf = new Keyframe(localTime, new VideoProperties(basis), easing);
        host.addKeyframe(clip, kf);
        keyIndex = clip.keyframes.keyframes.indexOf(kf);
        return keyIndex >= 0;
    }

    private void finishGesture() {
        if (mode == Mode.NONE || target == null) {
            mode = Mode.NONE;
            return;
        }
        final Clip clip = target;
        final Snapshot start = before;
        final boolean wasChanged = changed;
        mode = Mode.NONE;
        target = null;
        live = null;
        draggedHandle = -1;
        clearGuides();
        host.showInfo("");

        if (!wasChanged) {
            invalidate();
            return;
        }
        final Snapshot end = Snapshot.of(clip); // the clip already holds the final values
        host.commit("Transform: " + clip.getClipName(),
                () -> { end.restore(clip, host); host.clipChanged(clip); },
                () -> { start.restore(clip, host); host.clipChanged(clip); });
        invalidate();
    }

    /** Cancel / system interruption: put everything back. */
    public void cancel() {
        if (mode == Mode.NONE || target == null) {
            mode = Mode.NONE;
            return;
        }
        Clip clip = target;
        Snapshot start = before;
        mode = Mode.NONE;
        target = null;
        live = null;
        pinching = false;
        start.restore(clip, host);
        clearGuides();
        host.showInfo("");
        host.clipChanged(clip);
        invalidate();
    }

    // ======================================================================
    //  Transform maths
    // ======================================================================

    /**
     * Scale from a handle. A corner scales both axes uniformly; an edge handle stretches just that edge's axis
     * (in the clip's own rotated frame). The opposite handle stays where it is. Every case is one formula: the
     * grabbed handle moves along anchor + f * (grabbed - anchor).
     */
    private void scaleTo(float cx, float cy) {
        if (startQuad == null || draggedHandle < 0) return;
        int opposite = GizmoMath.oppositeHandle(draggedHandle);
        double[] grabbed = GizmoMath.handlePoint(startQuad, draggedHandle);
        double[] anchor = GizmoMath.handlePoint(startQuad, opposite);
        // How far along anchor -> grabbed handle the finger is: 1 = unchanged, 2 = twice as big.
        double f = GizmoMath.projectFactor(anchor, grabbed, cx, cy);
        if (Double.isNaN(f)) return;
        f = Math.max(MIN_SCALE_FACTOR, f);
        if (snapEnabled) f = Math.max(MIN_SCALE_FACTOR, snapAlong(anchor, grabbed, f));
        else clearGuides();

        boolean[] axes = GizmoMath.scaleAxes(draggedHandle); // a corner scales both axes, an edge just its own
        VideoProperties v = new VideoProperties(basis);
        if (axes[0]) v.valueScaleX = basis.valueScaleX * (float) f;
        if (axes[1]) v.valueScaleY = basis.valueScaleY * (float) f;
        anchorPoint(v, opposite, anchor[0], anchor[1]);
        show(v);
    }

    /** Pinch: uniform scale that keeps the clip's centre where it is. */
    private void scaleAboutCenter(float factor) {
        if (startQuad == null) return;
        VideoProperties v = new VideoProperties(basis);
        v.valueScaleX = basis.valueScaleX * factor;
        v.valueScaleY = basis.valueScaleY * factor;
        double[] q = quadFor(target, v);
        if (q == null) return;
        double cx = (q[0] + q[4]) / 2, cy = (q[1] + q[5]) / 2;
        v.valuePosX += (float) (startCenter[0] - cx);
        v.valuePosY += (float) (startCenter[1] - cy);
        show(v);
    }

    /** Shifts PosX/PosY so that handle {@code handle} (corner or edge midpoint) of the new quad lands on (ax, ay). */
    private void anchorPoint(VideoProperties v, int handle, double ax, double ay) {
        double[] q = quadFor(target, v);
        if (q == null) return;
        double[] at = handlePoint(q, handle);
        v.valuePosX += (float) (ax - at[0]);
        v.valuePosY += (float) (ay - at[1]);
    }

    // ---- handles ----

    private static double[] handlePoint(double[] q, int h) { return GizmoMath.handlePoint(q, h); }

    // ---- snapping ----

    private void clearGuides() {
        guideX = Double.NaN;
        guideY = Double.NaN;
    }

    /** Shows these guides; a guide that newly appears (or jumps to another line) gives a light tick. */
    private void setGuides(double gx, double gy) {
        boolean fresh = (!Double.isNaN(gx) && gx != guideX) || (!Double.isNaN(gy) && gy != guideY);
        guideX = gx;
        guideY = gy;
        if (fresh) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** On-screen pixels per canvas pixel (the preview is scaled to fit its pane). */
    private double screenScale() {
        float[] v = {1f, 0f};
        canvasView.getMatrix().mapVectors(v);
        double d = Math.hypot(v[0], v[1]);
        return d > 1e-6 ? d : 1;
    }

    /** The lines a gesture can snap to: the canvas edges and centre, plus every other visible clip's edges and centre. */
    private void collectSnapLines(Clip exclude) {
        snapXs.clear();
        snapYs.clear();
        snapXs.add(0.0);
        snapXs.add(canvasW / 2.0);
        snapXs.add((double) canvasW);
        snapYs.add(0.0);
        snapYs.add(canvasH / 2.0);
        snapYs.add((double) canvasH);

        EditingActivity.Timeline tl = host.timeline();
        float t = host.currentTime();
        if (tl == null) return;
        for (Track track : tl.tracks) {
            if (track == null) continue;
            for (Clip clip : track.clips) {
                if (clip == null || clip == exclude || !isGizmoClip(clip) || !isActive(clip, t)) continue;
                double[] q = quadFor(clip, resolve(clip, t));
                if (q != null) GizmoMath.addBoxLines(q, snapXs, snapYs);
            }
        }
    }

    /**
     * Moving: nudges PosX/PosY so the clip's left/centre/right (and top/centre/bottom) land on a snap line
     * when within a few screen pixels.
     */
    private void snapMove(VideoProperties v) {
        double[] q = quadFor(target, v);
        if (q == null) return;
        GizmoMath.MoveSnap m = GizmoMath.snapMove(q, snapXs, snapYs, SNAP_DP * density / screenScale());
        v.valuePosX += (float) m.dx;
        v.valuePosY += (float) m.dy;
        setGuides(m.lineX, m.lineY);
    }

    /** Scaling: the factor (near {@code f}) that puts the grabbed handle exactly on a snap line, with that guide shown. */
    private double snapAlong(double[] anchor, double[] grabbed, double f) {
        GizmoMath.AlongSnap r = GizmoMath.snapAlong(snapXs, snapYs, anchor[0], anchor[1],
                grabbed[0] - anchor[0], grabbed[1] - anchor[1], f, SNAP_DP * density / screenScale(), MIN_SCALE_FACTOR);
        setGuides(r.lineX, r.lineY);
        return r.factor;
    }

    /** Rotation about the clip's visual centre (the pivot is left alone), with 90-degree snapping. */
    private void rotateTo(float cx, float cy) {
        if (startQuad == null) return;
        double angle = Math.atan2(cy - startCenter[1], cx - startCenter[0]);
        double deltaDeg = Math.toDegrees(angle - startAngle);

        float rot = basis.valueRot + (float) deltaDeg;
        rot = ((rot + 360f) % 720f + 720f) % 720f - 360f; // the project's [-360, 360) wrap
        float snap = Constants.CANVAS_ROTATE_SNAP_DEGREE;
        float nearest = Math.round(rot / snap) * snap;
        if (Math.abs(rot - nearest) <= Constants.CANVAS_ROTATE_SNAP_THRESHOLD_DEGREE) rot = nearest;

        VideoProperties v = new VideoProperties(basis);
        v.valueRot = rot;
        double[] q = quadFor(target, v);
        if (q != null) {
            double qx = (q[0] + q[4]) / 2, qy = (q[1] + q[5]) / 2;
            v.valuePosX += (float) (startCenter[0] - qx);
            v.valuePosY += (float) (startCenter[1] - qy);
        }
        show(v);
    }

    private static boolean sameTransform(VideoProperties a, VideoProperties b) {
        return a.valuePosX == b.valuePosX && a.valuePosY == b.valuePosY && a.valueRot == b.valueRot
                && a.valueScaleX == b.valueScaleX && a.valueScaleY == b.valueScaleY;
    }

    /** Writes just the transform channels into the static properties or into one keyframe. */
    private static void writeProperties(Clip clip, VideoProperties v, int keyIndex) {
        VideoProperties dst = (keyIndex >= 0 && keyIndex < clip.keyframes.keyframes.size())
                ? clip.keyframes.keyframes.get(keyIndex).value
                : clip.videoProperties;
        dst.valuePosX = v.valuePosX;
        dst.valuePosY = v.valuePosY;
        dst.valueRot = v.valueRot;
        dst.valueScaleX = v.valueScaleX;
        dst.valueScaleY = v.valueScaleY;
    }

    // ======================================================================
    //  Geometry (same maths as OpenGLEdit.buildClipMvp)
    // ======================================================================

    private static boolean isGizmoClip(Clip c) {
        return c != null && (c.type == ClipType.VIDEO || c.type == ClipType.IMAGE || c.type == ClipType.TEXT);
    }

    private static boolean isActive(Clip c, float t) {
        return t >= c.startTime && t < c.startTime + c.duration;
    }

    /**
     * The clip's full look at {@code t}: keyframe-interpolated when it has keyframes, static otherwise.
     * Every property is resolved (not just the transform) because a keyframe inserted by a gesture is
     * copied from this, and must not reset opacity, colour and the rest to their static values.
     */
    private static VideoProperties resolve(Clip clip, float t) {
        VideoProperties p = new VideoProperties(clip.videoProperties);
        if (clip.keyframes != null && !clip.keyframes.keyframes.isEmpty()) {
            for (ValueType type : ValueType.values()) {
                if (type == ValueType.RotInRadians) continue; // derived from Rot
                p.setValue(clip.keyframes.getValueAtTime(clip, t, type), type);
            }
        }
        return p;
    }

    /** Canvas-pixel corners TL, TR, BR, BL as x0,y0,x1,y1,..., or null when the clip has no box. */
    private double[] quadFor(Clip clip, VideoProperties p) {
        double baseW = stretch ? canvasW : (clip.width > 0 ? clip.width : canvasW);
        double baseH = stretch ? canvasH : (clip.height > 0 ? clip.height : canvasH);
        double originX = 0, originY = 0;
        if (clip.type == ClipType.TEXT) {
            // Same placement as OpenGLEdit.buildClipMvp: the text block is centred, PosX/PosY offset it.
            int[] size = com.vanvatcorporation.doubleclips.TextRasterizer.measure(clip);
            if (size == null) return null;
            baseW = size[0];
            baseH = size[1];
            originX = (canvasW - baseW) / 2.0;
            originY = (canvasH - baseH) / 2.0;
        }
        return GizmoMath.quad(baseW, baseH, originX, originY, p.valuePosX, p.valuePosY,
                p.valuePivotX, p.valuePivotY, p.valueScaleX, p.valueScaleY, p.valueRot);
    }

    /** Convex-quad hit test (works for mirrored and negative scales too). */
    private static boolean contains(float px, float py, double[] q) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1];
        }
        if (Math.abs(area) < 1) return false; // collapsed (scale 0)
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double cross = (q[j * 2] - q[i * 2]) * (py - q[i * 2 + 1]) - (q[j * 2 + 1] - q[i * 2 + 1]) * (px - q[i * 2]);
            int sg = cross > 0 ? 1 : (cross < 0 ? -1 : 0);
            if (sg == 0) continue;
            if (sign == 0) sign = sg; else if (sign != sg) return false;
        }
        return true;
    }

    /** Gizmo-able clips under a canvas point, topmost first (later tracks are drawn over earlier ones). */
    private List<Clip> hitsAt(float cx, float cy) {
        List<Clip> hits = new ArrayList<>();
        EditingActivity.Timeline tl = host.timeline();
        float t = host.currentTime();
        if (tl == null) return hits;
        for (int ti = tl.tracks.size() - 1; ti >= 0; ti--) {
            Track track = tl.tracks.get(ti);
            if (track == null) continue;
            for (int ci = track.clips.size() - 1; ci >= 0; ci--) {
                Clip clip = track.clips.get(ci);
                if (clip == null || !isGizmoClip(clip) || !isActive(clip, t)) continue;
                double[] q = quadFor(clip, resolve(clip, t));
                if (q != null && contains(cx, cy, q)) hits.add(clip);
            }
        }
        return hits;
    }

    /** Which handle of {@code clip} is under an overlay point: 0-3 a corner, 4-7 an edge, HANDLE_ROTATE, or -1. Nearest wins. */
    private int handleAt(Clip clip, float ox, float oy) {
        double[] quad = quadFor(clip, resolve(clip, host.currentTime()));
        if (quad == null) return -1;
        float[] s = overlayCorners(quad);
        float hit = HANDLE_HIT_DP * density;
        int best = -1;
        double bestDist = hit;
        float[] h = rotateHandle(s);
        double d = Math.hypot(ox - h[0], oy - h[1]);
        if (d <= bestDist) { bestDist = d; best = HANDLE_ROTATE; }
        for (int i = 0; i < 4; i++) {
            d = Math.hypot(ox - s[i * 2], oy - s[i * 2 + 1]);
            if (d <= bestDist) { bestDist = d; best = i; }
        }
        float edgeHit = EDGE_HIT_DP * density;
        for (int i = 0; i < 4; i++) {
            if (!edgeVisible(s, i)) continue;
            int j = (i + 1) % 4;
            d = Math.hypot(ox - (s[i * 2] + s[j * 2]) / 2f, oy - (s[i * 2 + 1] + s[j * 2 + 1]) / 2f);
            if (d <= edgeHit && d < bestDist) { bestDist = d; best = H_TOP + i; }
        }
        return best;
    }

    // ======================================================================
    //  Coordinates: canvas pixels <-> overlay pixels (via the pane's own view matrix)
    // ======================================================================

    private float[] overlayCorners(double[] quad) {
        float[] out = new float[8];
        Matrix m = canvasView.getMatrix();
        for (int i = 0; i < 4; i++) {
            pt[0] = (float) quad[i * 2];
            pt[1] = (float) quad[i * 2 + 1];
            m.mapPoints(pt);
            out[i * 2] = pt[0] + canvasView.getLeft() - getLeft();
            out[i * 2 + 1] = pt[1] + canvasView.getTop() - getTop();
        }
        return out;
    }

    /** Canvas pixels -> overlay pixels. */
    private float[] toOverlay(double x, double y) {
        pt[0] = (float) x;
        pt[1] = (float) y;
        canvasView.getMatrix().mapPoints(pt);
        return new float[]{pt[0] + canvasView.getLeft() - getLeft(), pt[1] + canvasView.getTop() - getTop()};
    }

    /** Overlay point -> canvas pixels, or null if the pane's matrix can't be inverted. */
    private float[] toCanvas(float ox, float oy) {
        Matrix m = canvasView.getMatrix();
        if (!m.invert(inverse)) return null;
        float[] p = {ox - canvasView.getLeft() + getLeft(), oy - canvasView.getTop() + getTop()};
        inverse.mapPoints(p);
        return p;
    }

    // ======================================================================
    //  Undo snapshot
    // ======================================================================

    /**
     * Everything a gesture can touch on one clip: its static properties and its keyframe list with
     * each keyframe's value (copied, so later edits can't leak in). Keyframe objects keep their
     * identity - the timeline's knot views hold references to them. Restoring adds/removes through
     * the host so the knot views follow.
     */
    private static final class Snapshot {
        final VideoProperties statics;
        final List<Keyframe> keys;
        final List<VideoProperties> values;

        private Snapshot(VideoProperties statics, List<Keyframe> keys, List<VideoProperties> values) {
            this.statics = statics;
            this.keys = keys;
            this.values = values;
        }

        static Snapshot of(Clip clip) {
            List<Keyframe> keys = new ArrayList<>(clip.keyframes.keyframes);
            List<VideoProperties> values = new ArrayList<>();
            for (Keyframe k : keys) values.add(new VideoProperties(k.value));
            return new Snapshot(new VideoProperties(clip.videoProperties), keys, values);
        }

        void restore(Clip clip, Host host) {
            copyInto(clip.videoProperties, statics);
            for (Keyframe current : new ArrayList<>(clip.keyframes.keyframes)) {
                if (!keys.contains(current)) host.removeKeyframe(clip, current);
            }
            for (Keyframe k : keys) {
                if (!clip.keyframes.keyframes.contains(k)) host.addKeyframe(clip, k);
            }
            for (int i = 0; i < keys.size(); i++) copyInto(keys.get(i).value, values.get(i));
        }

        private static void copyInto(VideoProperties dst, VideoProperties src) {
            dst.valuePosX = src.valuePosX;
            dst.valuePosY = src.valuePosY;
            dst.valueRot = src.valueRot;
            dst.valueScaleX = src.valueScaleX;
            dst.valueScaleY = src.valueScaleY;
            dst.valueOpacity = src.valueOpacity;
            dst.valueSpeed = src.valueSpeed;
            dst.valueVolume = src.valueVolume;
            dst.valueHue = src.valueHue;
            dst.valueSaturation = src.valueSaturation;
            dst.valueBrightness = src.valueBrightness;
            dst.valueTemperature = src.valueTemperature;
            dst.valuePivotX = src.valuePivotX;
            dst.valuePivotY = src.valuePivotY;
        }
    }
}
