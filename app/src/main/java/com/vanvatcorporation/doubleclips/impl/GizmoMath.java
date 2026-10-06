package com.vanvatcorporation.doubleclips.impl;

import java.util.List;

/**
 * The geometry behind the on-canvas gizmo ({@link PreviewGizmoView}): a clip's box, its handles, the factor a
 * handle drag scales by, and snapping. Plain Java (no Android types) so it can be unit-tested and shared with the
 * other ports. All coordinates are canvas pixels.
 * <p>
 * Handle ids: 0-3 are the corners (TL, TR, BR, BL), 4-7 the edge midpoints (top, right, bottom, left); edge
 * {@code 4 + a} runs from corner {@code a} to corner {@code a + 1}.
 */
public final class GizmoMath {

    public static final int H_TOP = 4, H_RIGHT = 5, H_BOTTOM = 6, H_LEFT = 7;

    private GizmoMath() { }

    /**
     * Corners TL, TR, BR, BL as x0,y0,x1,y1,... of a box {@code baseW x baseH} placed at (originX, originY) and
     * offset by pos, scaled and rotated about its pivot (pivot as a fraction of the box).
     * Same maths as {@code OpenGLEdit.buildClipMvp}.
     */
    public static double[] quad(double baseW, double baseH, double originX, double originY,
                                double posX, double posY, double pivotX, double pivotY,
                                double scaleX, double scaleY, double rotDeg) {
        double scaledW = baseW * scaleX;
        double scaledH = baseH * scaleY;
        double pivotCx = posX + originX + pivotX * baseW;
        double pivotCy = posY + originY + pivotY * baseH;
        double theta = Math.toRadians(rotDeg);
        double c = Math.cos(theta), s = Math.sin(theta);
        double[] out = new double[8];
        double[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            double x = (uv[i][0] - pivotX) * scaledW;
            double y = (uv[i][1] - pivotY) * scaledH;
            out[i * 2] = pivotCx + x * c - y * s;
            out[i * 2 + 1] = pivotCy + x * s + y * c;
        }
        return out;
    }

    /** Position of handle {@code h} (corner 0-3, edge midpoint 4-7) on a quad. */
    public static double[] handlePoint(double[] q, int h) {
        if (h < H_TOP) return new double[]{q[h * 2], q[h * 2 + 1]};
        int a = h - H_TOP, b = (a + 1) % 4;
        return new double[]{(q[a * 2] + q[b * 2]) / 2, (q[a * 2 + 1] + q[b * 2 + 1]) / 2};
    }

    /** The handle across the box: the corner diagonally opposite, or the edge on the other side. */
    public static int oppositeHandle(int h) {
        return h < H_TOP ? (h + 2) % 4 : H_TOP + ((h - H_TOP + 2) % 4);
    }

    /** Which scale axes dragging {@code handle} changes: {x, y}. A corner changes both (uniform), an edge one. */
    public static boolean[] scaleAxes(int handle) {
        if (handle < H_TOP) return new boolean[]{true, true};
        boolean topOrBottom = handle == H_TOP || handle == H_BOTTOM;
        return new boolean[]{!topOrBottom, topOrBottom};
    }

    /**
     * How far along anchor -> grabbed the point (px, py) is: 1 = the handle where it started, 2 = twice as far.
     * NaN when the handle sits on its anchor.
     */
    public static double projectFactor(double[] anchor, double[] grabbed, double px, double py) {
        double vx = grabbed[0] - anchor[0], vy = grabbed[1] - anchor[1];
        double len2 = vx * vx + vy * vy;
        if (len2 < 1e-3) return Double.NaN;
        return ((px - anchor[0]) * vx + (py - anchor[1]) * vy) / len2;
    }

    /** Result of {@link #snapAlong}: the (possibly snapped) factor and the guide that explains it (NaN = none). */
    public static final class AlongSnap {
        public double factor;
        public double lineX = Double.NaN, lineY = Double.NaN;
    }

    /**
     * Scaling: the grabbed handle moves along anchor + f * (vx, vy). Finds the f (near {@code f}) that puts it
     * exactly on a snap line, if one is within {@code threshold} canvas pixels.
     */
    public static AlongSnap snapAlong(List<Double> xs, List<Double> ys, double ox, double oy, double vx, double vy,
                                      double f, double threshold, double minFactor) {
        AlongSnap r = new AlongSnap();
        r.factor = f;
        double best = Double.MAX_VALUE;
        if (Math.abs(vx) > 1e-6) {
            for (double line : xs) {
                double fl = (line - ox) / vx;
                double dist = Math.abs(fl - f) * Math.abs(vx); // how far the handle is from the line, in canvas px
                if (fl > minFactor && dist < best && dist <= threshold) {
                    best = dist; r.factor = fl; r.lineX = line; r.lineY = Double.NaN;
                }
            }
        }
        if (Math.abs(vy) > 1e-6) {
            for (double line : ys) {
                double fl = (line - oy) / vy;
                double dist = Math.abs(fl - f) * Math.abs(vy);
                if (fl > minFactor && dist < best && dist <= threshold) {
                    best = dist; r.factor = fl; r.lineY = line; r.lineX = Double.NaN;
                }
            }
        }
        return r;
    }

    /** Result of {@link #snapMove}: how far to shift, and the guides (NaN = no snap on that axis). */
    public static final class MoveSnap {
        public double dx, dy;
        public double lineX = Double.NaN, lineY = Double.NaN;
    }

    /**
     * Moving: the shift that puts the box's left / centre / right (and top / centre / bottom) of the quad on a snap
     * line, per axis, when one is within {@code threshold} canvas pixels.
     */
    public static MoveSnap snapMove(double[] q, List<Double> xs, List<Double> ys, double threshold) {
        double minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6]));
        double maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        double minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7]));
        double maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        double[] movingX = {minX, (minX + maxX) / 2, maxX};
        double[] movingY = {minY, (minY + maxY) / 2, maxY};

        MoveSnap r = new MoveSnap();
        double bestX = threshold + 1, bestY = threshold + 1;
        for (double m : movingX) for (double line : xs) {
            double d = line - m;
            if (Math.abs(d) < bestX) { bestX = Math.abs(d); r.dx = d; r.lineX = line; }
        }
        for (double m : movingY) for (double line : ys) {
            double d = line - m;
            if (Math.abs(d) < bestY) { bestY = Math.abs(d); r.dy = d; r.lineY = line; }
        }
        if (bestX > threshold) { r.dx = 0; r.lineX = Double.NaN; }
        if (bestY > threshold) { r.dy = 0; r.lineY = Double.NaN; }
        return r;
    }

    /** The lines a clip box offers to snap to: its min / centre / max on each axis. */
    public static void addBoxLines(double[] q, List<Double> xs, List<Double> ys) {
        double minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6]));
        double maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        double minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7]));
        double maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        xs.add(minX); xs.add((minX + maxX) / 2); xs.add(maxX);
        ys.add(minY); ys.add((minY + maxY) / 2); ys.add(maxY);
    }
}
