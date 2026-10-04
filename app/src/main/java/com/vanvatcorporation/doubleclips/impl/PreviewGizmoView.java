package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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
    private int draggedCorner = -1;
    private double[] startQuad;
    private double startAngle;
    private double[] startCenter;
    private boolean pinching;
    private boolean pastSlop;
    private boolean changed;
    private boolean ignoreUntilUp;
    private float pinchFactor = 1f;

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

        setWillNotDraw(false);
        setClickable(false);

        pinchDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                Clip sel = host.selectedClip();
                if (host.isPlaying() || !isGizmoClip(sel) || !isActive(sel, host.currentTime())) return false;
                if (mode != Mode.NONE) finishGesture(); // a half-done move becomes its own undo step
                beginGesture(sel, Mode.SCALE, 0f, 0f, 0f, 0f, -1);
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

        float[] h = rotateHandle(s);
        float mx = (s[0] + s[2]) / 2f, my = (s[1] + s[3]) / 2f;
        canvas.drawLine(mx, my, h[0], h[1], dark);
        canvas.drawLine(mx, my, h[0], h[1], solid(light));

        float r = HANDLE_RADIUS_DP * density;
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(s[i * 2], s[i * 2 + 1], r, fill);
            canvas.drawCircle(s[i * 2], s[i * 2 + 1], r, ring);
        }
        canvas.drawCircle(h[0], h[1], r * 1.15f, fill);
        canvas.drawCircle(h[0], h[1], r * 1.15f, ring);
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
        this.draggedCorner = corner;
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
        draggedCorner = -1;
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
        host.showInfo("");
        host.clipChanged(clip);
        invalidate();
    }

    // ======================================================================
    //  Transform maths
    // ======================================================================

    /** Uniform scale from a corner handle; the opposite corner stays put. */
    private void scaleTo(float cx, float cy) {
        if (startQuad == null || draggedCorner < 0) return;
        int opposite = (draggedCorner + 2) % 4;
        double ox = startQuad[opposite * 2], oy = startQuad[opposite * 2 + 1];
        double dx0 = startQuad[draggedCorner * 2] - ox, dy0 = startQuad[draggedCorner * 2 + 1] - oy;
        double len2 = dx0 * dx0 + dy0 * dy0;
        if (len2 < 1e-3) return;
        double f = ((cx - ox) * dx0 + (cy - oy) * dy0) / len2; // projection onto the start diagonal
        f = Math.max(MIN_SCALE_FACTOR, f);

        VideoProperties v = new VideoProperties(basis);
        v.valueScaleX = basis.valueScaleX * (float) f;
        v.valueScaleY = basis.valueScaleY * (float) f;
        anchorPoint(v, opposite, ox, oy);
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

    /** Shifts PosX/PosY so that corner {@code corner} of the new quad lands on (ax, ay). */
    private void anchorPoint(VideoProperties v, int corner, double ax, double ay) {
        double[] q = quadFor(target, v);
        if (q == null) return;
        v.valuePosX += (float) (ax - q[corner * 2]);
        v.valuePosY += (float) (ay - q[corner * 2 + 1]);
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
        return c != null && (c.type == ClipType.VIDEO || c.type == ClipType.IMAGE);
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
        double scaledW = baseW * p.valueScaleX;
        double scaledH = baseH * p.valueScaleY;
        double px = p.valuePivotX, py = p.valuePivotY;
        double pivotX = p.valuePosX + px * baseW;
        double pivotY = p.valuePosY + py * baseH;
        double theta = Math.toRadians(p.valueRot);
        double c = Math.cos(theta), s = Math.sin(theta);
        double[] out = new double[8];
        double[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            double x = (uv[i][0] - px) * scaledW;
            double y = (uv[i][1] - py) * scaledH;
            out[i * 2] = pivotX + x * c - y * s;
            out[i * 2 + 1] = pivotY + x * s + y * c;
        }
        return out;
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

    /** Which handle of {@code clip} is under an overlay point: 0-3 a corner, HANDLE_ROTATE, or -1. Nearest wins. */
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
