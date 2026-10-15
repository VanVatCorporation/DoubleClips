package com.vanvatcorporation.doubleclips;

/**
 * The maths of editing a clip mask on the canvas (spec section 6, "Canvas"), without any drawing or touch code.
 * <p>
 * Everything works in the clip's BOX: the clip as drawn, scaled and not yet rotated, in canvas pixels, whose corners the
 * editor knows as a quad TL, TR, BR, BL. A mask's centre and sizes are fractions of that box ({@link ClipMaskResolver.State});
 * handles sit on the shape in a frame centred on the mask and turned by its rotation (clockwise, y down).
 * <ul>
 *   <li>corners scale width and height together about the centre; edge midpoints resize one axis, symmetric about the
 *       centre (a linear mask has none, a mirror only the top and bottom ones)</li>
 *   <li>the arrow above the shape rotates it, snapping to multiples of 90 within 3 degrees</li>
 *   <li>the drop below the shape sets the feather: the drag along the shape's down axis, divided by the box's shorter side</li>
 *   <li>anywhere else moves the centre</li>
 *   <li>pinch scales, twist rotates</li>
 * </ul>
 * Results are {@link Edit}s in the PANEL's units, ready for MaskEditController. Plain Java.
 */
public final class MaskGizmoMath {

    private MaskGizmoMath() { }

    /** Handle ids: 0-3 the corners (TL, TR, BR, BL), 4-7 the edge midpoints (top, right, bottom, left), then rotate and feather. */
    public static final int NONE = -1, CORNER_TL = 0, CORNER_TR = 1, CORNER_BR = 2, CORNER_BL = 3,
            EDGE_TOP = 4, EDGE_RIGHT = 5, EDGE_BOTTOM = 6, EDGE_LEFT = 7, ROTATE = 8, FEATHER = 9;

    public static final float MIN_SCALE_FACTOR = 0.02f;
    public static final float ROTATION_SNAP_DEGREES = 3f;

    /** The box in canvas pixels: TL corner, unit vectors along its width and height, and its size. */
    public static final class Box {
        public final double ox, oy, ux, uy, vx, vy, w, h;

        private Box(double ox, double oy, double ux, double uy, double vx, double vy, double w, double h) {
            this.ox = ox;
            this.oy = oy;
            this.ux = ux;
            this.uy = uy;
            this.vx = vx;
            this.vy = vy;
            this.w = w;
            this.h = h;
        }

        /** Box pixels -> canvas pixels. */
        public double[] toCanvas(double bx, double by) {
            return new double[]{ox + bx * ux + by * vx, oy + bx * uy + by * vy};
        }

        /** Canvas pixels -> box pixels (the axes are orthogonal: a clip is only ever scaled and rotated). */
        public double[] toBox(double cx, double cy) {
            double dx = cx - ox, dy = cy - oy;
            return new double[]{dx * ux + dy * uy, dx * vx + dy * vy};
        }

        /** A canvas-pixel difference as a box-pixel difference. */
        public double[] toBoxDelta(double dx, double dy) {
            return new double[]{dx * ux + dy * uy, dx * vx + dy * vy};
        }

        public double shorterSide() {
            return Math.min(w, h);
        }
    }

    /** The box of a quad TL, TR, BR, BL (x0,y0,x1,y1,...), or null when it has no area. */
    public static Box box(double[] quad) {
        if (quad == null || quad.length < 8) return null;
        double ux = quad[2] - quad[0], uy = quad[3] - quad[1];
        double vx = quad[6] - quad[0], vy = quad[7] - quad[1];
        double w = Math.hypot(ux, uy), h = Math.hypot(vx, vy);
        if (w < 1e-3 || h < 1e-3) return null;
        return new Box(quad[0], quad[1], ux / w, uy / w, vx / h, vy / h, w, h);
    }

    /** What a gesture changes, in panel units (x 50 = centre of the box); null = not touched by it. */
    public static final class Edit {
        public Float x, y, width, height, rotation, feather;
    }

    // ---- the shape's size in box pixels -------------------------------------------------------------

    public static double halfWidth(ClipMaskResolver.State s, Box box) {
        return Math.max(s.width * box.shorterSide() / 2.0, 0.5);
    }

    public static double halfHeight(ClipMaskResolver.State s, Box box) {
        return Math.max(s.height * box.shorterSide() / 2.0, 0.5);
    }

    // ---- handles ------------------------------------------------------------------------------------

    /** Whether this shape has the handle at all (a half plane has no size; a mirror's band only has a thickness). */
    public static boolean hasHandle(String shape, int handle) {
        switch (handle) {
            case ROTATE: case FEATHER:
                return true;
            case EDGE_TOP: case EDGE_BOTTOM:
                return !ClipMask.LINEAR.equals(shape);
            case CORNER_TL: case CORNER_TR: case CORNER_BR: case CORNER_BL:
            case EDGE_LEFT: case EDGE_RIGHT:
                return !ClipMask.LINEAR.equals(shape) && !ClipMask.MIRROR.equals(shape);
            default:
                return false;
        }
    }

    /** A handle's position relative to the mask's centre, in the mask's own (unrotated) frame, in box pixels. */
    public static double[] handleLocal(String shape, int handle, double hw, double hh, double offset) {
        boolean linear = ClipMask.LINEAR.equals(shape);
        switch (handle) {
            case CORNER_TL: return new double[]{-hw, -hh};
            case CORNER_TR: return new double[]{hw, -hh};
            case CORNER_BR: return new double[]{hw, hh};
            case CORNER_BL: return new double[]{-hw, hh};
            case EDGE_TOP: return new double[]{0, -hh};
            case EDGE_RIGHT: return new double[]{hw, 0};
            case EDGE_BOTTOM: return new double[]{0, hh};
            case EDGE_LEFT: return new double[]{-hw, 0};
            case ROTATE: return new double[]{0, -(linear ? 0 : hh) - offset};
            case FEATHER: return new double[]{0, (linear ? 0 : hh) + offset};
            default: return new double[]{0, 0};
        }
    }

    /** A handle's position in box pixels (turned by the mask's rotation, moved to its centre). */
    public static double[] handleInBox(ClipMaskResolver.State s, Box box, int handle, double offset) {
        double[] local = handleLocal(s.shape, handle, halfWidth(s, box), halfHeight(s, box), offset);
        double[] r = rotate(local[0], local[1], s.rotation);
        return new double[]{s.centerX * box.w + r[0], s.centerY * box.h + r[1]};
    }

    /** Clockwise rotation on a y-down screen. */
    static double[] rotate(double x, double y, double degrees) {
        double a = Math.toRadians(degrees), c = Math.cos(a), sn = Math.sin(a);
        return new double[]{x * c - y * sn, x * sn + y * c};
    }

    // ---- gestures (all take the mask as it was when the finger went down) ----------------------------

    /** Anywhere else: the centre follows the finger. */
    public static Edit move(ClipMaskResolver.State s0, Box box, double pressX, double pressY, double nowX, double nowY) {
        double[] d = box.toBoxDelta(nowX - pressX, nowY - pressY);
        Edit e = new Edit();
        e.x = (float) ((s0.centerX * box.w + d[0]) / box.w * 100.0);
        e.y = (float) ((s0.centerY * box.h + d[1]) / box.h * 100.0);
        return e;
    }

    /** A corner: width and height together, about the centre (anchor + f x (grabbed - anchor), the anchor being the centre). */
    public static Edit scaleCorner(ClipMaskResolver.State s0, Box box, int corner, double nowX, double nowY) {
        double hw = halfWidth(s0, box), hh = halfHeight(s0, box);
        double[] g = rotate(handleLocal(s0.shape, corner, hw, hh, 0)[0], handleLocal(s0.shape, corner, hw, hh, 0)[1], s0.rotation);
        double[] now = box.toBox(nowX, nowY);
        double fx = now[0] - s0.centerX * box.w, fy = now[1] - s0.centerY * box.h;
        double len2 = g[0] * g[0] + g[1] * g[1];
        double f = len2 < 1e-9 ? 1.0 : (fx * g[0] + fy * g[1]) / len2;
        f = Math.max(MIN_SCALE_FACTOR, f);
        Edit e = new Edit();
        e.width = (float) (s0.width * f * 100.0);
        e.height = (float) (s0.height * f * 100.0);
        return e;
    }

    /** An edge midpoint: that axis only, symmetric about the centre (a mirror's top and bottom set its thickness). */
    public static Edit resizeEdge(ClipMaskResolver.State s0, Box box, int edge, double nowX, double nowY) {
        double[] now = box.toBox(nowX, nowY);
        double[] local = rotate(now[0] - s0.centerX * box.w, now[1] - s0.centerY * box.h, -s0.rotation);
        double m = box.shorterSide();
        Edit e = new Edit();
        if (edge == EDGE_LEFT || edge == EDGE_RIGHT) e.width = (float) (Math.abs(local[0]) * 2.0 / m * 100.0);
        else e.height = (float) (Math.abs(local[1]) * 2.0 / m * 100.0);
        return e;
    }

    /** The arrow: points the mask's "up" at the finger; snaps to multiples of 90 within 3 degrees. */
    public static Edit rotateTo(ClipMaskResolver.State s0, Box box, double nowX, double nowY) {
        double[] now = box.toBox(nowX, nowY);
        double fx = now[0] - s0.centerX * box.w, fy = now[1] - s0.centerY * box.h;
        if (Math.hypot(fx, fy) < 1e-6) return new Edit();
        Edit e = new Edit();
        e.rotation = (float) snapRotation(Math.toDegrees(Math.atan2(fx, -fy)));
        return e;
    }

    /** Degrees in -180..180, snapped to the nearest multiple of 90 when within 3 degrees of it. */
    public static double snapRotation(double degrees) {
        double d = normalize(degrees);
        double nearest = Math.round(d / 90.0) * 90.0;
        if (Math.abs(d - nearest) <= ROTATION_SNAP_DEGREES) d = nearest;
        return normalize(d);
    }

    /** -180..180 (180 stays 180). */
    public static double normalize(double degrees) {
        double d = degrees % 360.0;
        if (d > 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** The drop: how far the finger moved along the shape's down axis since it went down, divided by the box's shorter side, added to the feather. */
    public static Edit feather(ClipMaskResolver.State s0, Box box, double pressX, double pressY, double nowX, double nowY) {
        double[] d = box.toBoxDelta(nowX - pressX, nowY - pressY);
        double a = Math.toRadians(s0.rotation);
        double along = d[0] * -Math.sin(a) + d[1] * Math.cos(a); // local down (0,1) turned by the rotation
        Edit e = new Edit();
        e.feather = (float) ((s0.feather + along / box.shorterSide()) * 200.0);
        return e;
    }

    /** Two fingers: spread scales width and height about the centre, a twist rotates. */
    public static Edit pinch(ClipMaskResolver.State s0, double factor, double twistDegrees) {
        double f = Math.max(MIN_SCALE_FACTOR, factor);
        Edit e = new Edit();
        e.width = (float) (s0.width * f * 100.0);
        e.height = (float) (s0.height * f * 100.0);
        e.rotation = (float) normalize(s0.rotation + twistDegrees);
        return e;
    }
}
