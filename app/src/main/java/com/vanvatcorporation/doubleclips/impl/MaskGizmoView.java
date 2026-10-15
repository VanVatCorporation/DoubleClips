package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.vanvatcorporation.doubleclips.CanvasMaskPainter;
import com.vanvatcorporation.doubleclips.ClipMask;
import com.vanvatcorporation.doubleclips.ClipMaskGeometry;
import com.vanvatcorporation.doubleclips.ClipMaskResolver;
import com.vanvatcorporation.doubleclips.MaskGizmoMath;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.editing.MaskEditController;

/**
 * Canvas editing of a clip mask: the shape's outline over the preview, in the clip's own box, with handles (spec section 6):
 * corners scale width and height together, edge midpoints resize one axis, the arrow above rotates (snapping to 90 degrees),
 * the drop below sets the feather, anywhere else moves the centre, and two fingers pinch / twist. Every gesture is one undo
 * step and the panel's number boxes follow it live (MaskEditController).
 * <p>
 * A transparent overlay like PreviewGizmoView, outside the scaled canvas pane: handles keep a constant on-screen size.
 * It is visible only while the mask panel is open (the transform gizmo steps aside then). The maths is MaskGizmoMath.
 */
public final class MaskGizmoView extends View {

    /** What the editor has to provide. */
    public interface Host {
        EditingActivity.Clip selectedClip();

        boolean isPlaying();

        /** The clip's box at the playhead, canvas pixels, corners TL, TR, BR, BL (the whole canvas for an effect clip), or null. */
        double[] boxQuad(EditingActivity.Clip clip);
    }

    private static final float HANDLE_RADIUS_DP = 6f;
    private static final float HANDLE_HIT_DP = 24f;
    /** How far the rotate arrow and the feather drop sit from the shape. */
    private static final float OFFSET_DP = 40f;
    private static final int[] ALL_HANDLES = {
            MaskGizmoMath.CORNER_TL, MaskGizmoMath.CORNER_TR, MaskGizmoMath.CORNER_BR, MaskGizmoMath.CORNER_BL,
            MaskGizmoMath.EDGE_TOP, MaskGizmoMath.EDGE_RIGHT, MaskGizmoMath.EDGE_BOTTOM, MaskGizmoMath.EDGE_LEFT,
            MaskGizmoMath.ROTATE, MaskGizmoMath.FEATHER};

    private final Host host;
    private final MaskEditController controller;
    private final View canvasView;
    private final float density;
    private final float touchSlop;

    private final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix inverse = new Matrix();
    private final float[] pt = new float[2];

    // ---- gesture state ----
    private boolean active;                 // a one-finger gesture is running
    private boolean pinching;
    private boolean ignoreUntilUp;
    private boolean pastSlop;
    private int handle = MaskGizmoMath.NONE; // NONE = move
    private ClipMaskResolver.State start;    // the mask as it was when the gesture began
    private float pressCanvasX, pressCanvasY, pressOverlayX, pressOverlayY;
    private double pinchStartDistance, pinchStartAngle;

    public MaskGizmoView(Context context, Host host, MaskEditController controller, View canvasView) {
        super(context);
        this.host = host;
        this.controller = controller;
        this.canvasView = canvasView;
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        dark.setStyle(Paint.Style.STROKE);
        dark.setColor(0x99000000);
        light.setStyle(Paint.Style.STROKE);
        light.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x99000000);
        ring.setStrokeWidth(1.2f * density);
        accent.setStyle(Paint.Style.STROKE);
        accent.setColor(0xFF000000);
        accent.setStrokeWidth(1.6f * density);
        accent.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
        setClickable(false);
    }

    /** Call whenever time, selection, the mask or the layout changed. */
    public void refresh() {
        invalidate();
    }

    // ======================================================================
    //  Drawing
    // ======================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        if (!controller.isOpen()) return;
        EditingActivity.Clip clip = host.selectedClip();
        if (clip == null) return;
        ClipMaskResolver.State s = controller.stateAtPlayhead();
        MaskGizmoMath.Box box = MaskGizmoMath.box(host.boxQuad(clip));
        if (s == null || box == null) return;

        float[] tl = toOverlay(box.ox, box.oy);
        float[] tr = toOverlay(box.toCanvas(box.w, 0)[0], box.toCanvas(box.w, 0)[1]);
        float[] bl = toOverlay(box.toCanvas(0, box.h)[0], box.toCanvas(0, box.h)[1]);
        float scale = (float) (Math.hypot(tr[0] - tl[0], tr[1] - tl[1]) / box.w); // overlay pixels per box pixel
        if (scale <= 0f) return;

        // The outline is drawn in box pixels through the box's own matrix, clipped to the box (a half plane or a band runs on beyond it).
        Matrix m = new Matrix();
        m.setPolyToPoly(new float[]{0, 0, (float) box.w, 0, 0, (float) box.h}, 0,
                new float[]{tl[0], tl[1], tr[0], tr[1], bl[0], bl[1]}, 0, 3);
        dark.setStrokeWidth(3.5f * density / scale);
        light.setStrokeWidth(1.5f * density / scale);
        light.setPathEffect(new DashPathEffect(new float[]{6f * density / scale, 4f * density / scale}, 0));
        for (Paint paint : new Paint[]{dark, light}) {
            canvas.save();
            canvas.concat(m);
            canvas.clipRect(0f, 0f, (float) box.w, (float) box.h);
            ClipMaskGeometry.draw(s, (float) box.w, (float) box.h, new CanvasMaskPainter(canvas, paint));
            canvas.restore();
        }

        // The stems and handles are drawn in overlay pixels again: back to on-screen widths.
        dark.setStrokeWidth(3.5f * density);
        light.setStrokeWidth(1.5f * density);
        light.setPathEffect(null);
        double offset = OFFSET_DP * density / scale;
        float r = HANDLE_RADIUS_DP * density;
        for (int id : ALL_HANDLES) {
            if (!MaskGizmoMath.hasHandle(s.shape, id)) continue;
            if (id == MaskGizmoMath.ROTATE || id == MaskGizmoMath.FEATHER) {
                float[] from = overlayPoint(box, MaskGizmoMath.handleInBox(s, box, id, 0));
                float[] to = overlayPoint(box, MaskGizmoMath.handleInBox(s, box, id, offset));
                canvas.drawLine(from[0], from[1], to[0], to[1], dark);
                canvas.drawLine(from[0], from[1], to[0], to[1], light);
            }
        }
        for (int id : ALL_HANDLES) {
            if (!MaskGizmoMath.hasHandle(s.shape, id)) continue;
            float[] p = overlayPoint(box, MaskGizmoMath.handleInBox(s, box, id, offset));
            float radius = id >= MaskGizmoMath.ROTATE ? r * 1.2f : (id >= MaskGizmoMath.EDGE_TOP ? r * 0.85f : r);
            canvas.drawCircle(p[0], p[1], radius, fill);
            canvas.drawCircle(p[0], p[1], radius, ring);
            if (id == MaskGizmoMath.ROTATE) {
                canvas.drawArc(p[0] - radius * 0.5f, p[1] - radius * 0.5f, p[0] + radius * 0.5f, p[1] + radius * 0.5f, 40f, 280f, false, accent);
            } else if (id == MaskGizmoMath.FEATHER) {
                path.reset(); // a drop: a point on top, a round belly
                path.moveTo(p[0], p[1] - radius * 0.55f);
                path.lineTo(p[0] - radius * 0.38f, p[1] + radius * 0.05f);
                path.lineTo(p[0] + radius * 0.38f, p[1] + radius * 0.05f);
                path.close();
                canvas.drawPath(path, accent);
                canvas.drawCircle(p[0], p[1] + radius * 0.2f, radius * 0.38f, accent);
            }
        }
    }

    private float[] overlayPoint(MaskGizmoMath.Box box, double[] boxPoint) {
        double[] c = box.toCanvas(boxPoint[0], boxPoint[1]);
        return toOverlay(c[0], c[1]);
    }

    // ======================================================================
    //  Touch
    // ======================================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ignoreUntilUp = false;
                return onDown(e);
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!pinching && e.getPointerCount() == 2) startPinch(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching) {
                    if (e.getPointerCount() >= 2) onPinchMove(e);
                } else if (active && !ignoreUntilUp) {
                    onMove(e.getX(), e.getY());
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (pinching) endPinch();
                return true;
            case MotionEvent.ACTION_UP:
                if (active) controller.canvasGestureEnd();
                if (pinching) endPinch();
                active = false;
                ignoreUntilUp = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (active || pinching) controller.canvasGestureCancel();
                active = false;
                pinching = false;
                ignoreUntilUp = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private boolean onDown(MotionEvent e) {
        if (!controller.isOpen() || host.isPlaying()) return false;
        EditingActivity.Clip clip = host.selectedClip();
        ClipMaskResolver.State s = controller.stateAtPlayhead();
        MaskGizmoMath.Box box = clip == null ? null : MaskGizmoMath.box(host.boxQuad(clip));
        float[] c = toCanvas(e.getX(), e.getY());
        if (s == null || box == null || c == null) return false;

        start = s;
        pressOverlayX = e.getX();
        pressOverlayY = e.getY();
        pressCanvasX = c[0];
        pressCanvasY = c[1];
        handle = handleAt(s, box, e.getX(), e.getY());
        pastSlop = false;
        active = true;
        controller.canvasGestureBegin();
        return true;
    }

    private void onMove(float ox, float oy) {
        if (!pastSlop) {
            if (Math.hypot(ox - pressOverlayX, oy - pressOverlayY) < touchSlop) return;
            pastSlop = true;
        }
        EditingActivity.Clip clip = host.selectedClip();
        MaskGizmoMath.Box box = clip == null ? null : MaskGizmoMath.box(host.boxQuad(clip));
        float[] c = toCanvas(ox, oy);
        if (box == null || c == null || start == null) return;

        MaskGizmoMath.Edit edit;
        switch (handle) {
            case MaskGizmoMath.CORNER_TL: case MaskGizmoMath.CORNER_TR: case MaskGizmoMath.CORNER_BR: case MaskGizmoMath.CORNER_BL:
                edit = MaskGizmoMath.scaleCorner(start, box, handle, c[0], c[1]);
                break;
            case MaskGizmoMath.EDGE_TOP: case MaskGizmoMath.EDGE_RIGHT: case MaskGizmoMath.EDGE_BOTTOM: case MaskGizmoMath.EDGE_LEFT:
                edit = MaskGizmoMath.resizeEdge(start, box, handle, c[0], c[1]);
                break;
            case MaskGizmoMath.ROTATE:
                edit = MaskGizmoMath.rotateTo(start, box, c[0], c[1]);
                break;
            case MaskGizmoMath.FEATHER:
                edit = MaskGizmoMath.feather(start, box, pressCanvasX, pressCanvasY, c[0], c[1]);
                break;
            default:
                edit = MaskGizmoMath.move(start, box, pressCanvasX, pressCanvasY, c[0], c[1]);
                break;
        }
        controller.canvasGestureSet(edit);
        invalidate();
    }

    // ---- pinch (scale) and twist (rotate), about the shape's centre ----

    private void startPinch(MotionEvent e) {
        if (active) {
            controller.canvasGestureEnd(); // what the first finger did so far is its own undo step
            active = false;
        }
        ClipMaskResolver.State s = controller.stateAtPlayhead();
        if (s == null || host.isPlaying()) {
            ignoreUntilUp = true;
            return;
        }
        start = s;
        pinchStartDistance = Math.max(1.0, distance(e));
        pinchStartAngle = angle(e);
        pinching = true;
        ignoreUntilUp = true; // the rest of this touch belongs to the pinch
        controller.canvasGestureBegin();
    }

    private void onPinchMove(MotionEvent e) {
        double factor = distance(e) / pinchStartDistance;
        double twist = Math.toDegrees(angle(e) - pinchStartAngle);
        MaskGizmoMath.Edit edit = MaskGizmoMath.pinch(start, factor, twist);
        if (ClipMask.LINEAR.equals(start.shape)) {
            edit.width = null; // a half plane has no size
            edit.height = null;
        } else if (ClipMask.MIRROR.equals(start.shape)) {
            edit.width = null; // a mirror band only has a thickness
        }
        controller.canvasGestureSet(edit);
        invalidate();
    }

    private void endPinch() {
        pinching = false;
        controller.canvasGestureEnd();
        invalidate();
    }

    private static double distance(MotionEvent e) {
        return Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
    }

    private static double angle(MotionEvent e) {
        return Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)); // clockwise on screen, like the mask's rotation
    }

    // ---- hit testing ----

    /** The handle under an overlay point (the nearest within reach), or NONE (= move the centre). */
    private int handleAt(ClipMaskResolver.State s, MaskGizmoMath.Box box, float ox, float oy) {
        float[] tl = toOverlay(box.ox, box.oy);
        float[] tr = toOverlay(box.toCanvas(box.w, 0)[0], box.toCanvas(box.w, 0)[1]);
        float scale = (float) (Math.hypot(tr[0] - tl[0], tr[1] - tl[1]) / box.w);
        double offset = OFFSET_DP * density / Math.max(scale, 1e-6f);
        float reach = HANDLE_HIT_DP * density;
        int best = MaskGizmoMath.NONE;
        float bestDistance = reach;
        for (int id : ALL_HANDLES) {
            if (!MaskGizmoMath.hasHandle(s.shape, id)) continue;
            float[] p = overlayPoint(box, MaskGizmoMath.handleInBox(s, box, id, offset));
            float d = (float) Math.hypot(p[0] - ox, p[1] - oy);
            if (d <= bestDistance) {
                bestDistance = d;
                best = id;
            }
        }
        return best;
    }

    // ---- canvas <-> overlay pixels (the pane's matrix, like PreviewGizmoView) ----

    private float[] toOverlay(double x, double y) {
        pt[0] = (float) x;
        pt[1] = (float) y;
        canvasView.getMatrix().mapPoints(pt);
        return new float[]{pt[0] + canvasView.getLeft() - getLeft(), pt[1] + canvasView.getTop() - getTop()};
    }

    private float[] toCanvas(float ox, float oy) {
        Matrix m = canvasView.getMatrix();
        if (!m.invert(inverse)) return null;
        float[] p = {ox - canvasView.getLeft() + getLeft(), oy - canvasView.getTop() + getTop()};
        inverse.mapPoints(p);
        return p;
    }
}
