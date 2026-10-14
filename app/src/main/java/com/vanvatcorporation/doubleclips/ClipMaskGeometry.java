package com.vanvatcorporation.doubleclips;

/**
 * Where a mask's shape is, in a box of W x H pixels (spec section 4). It only describes the shape, as calls on a
 * {@link Painter}: the Android build paints them on an android.graphics.Canvas, tests paint them with Java2D.
 * <p>
 * m = min(W, H); centre c = (centerX * W, centerY * H); half sizes hw = max(width * m / 2, 0.5), hh = max(height * m / 2, 0.5).
 * Everything is described in a local frame centred on c and turned by the rotation (clockwise, y down). Invert is not
 * applied here (the shader flips the mask). Plain Java.
 */
public final class ClipMaskGeometry {

    private ClipMaskGeometry() { }

    /** Hard-edged (the painter anti-aliases) filled shapes; everything painted is "visible". */
    public interface Painter {
        /** Makes (cx, cy) the origin and turns the axes clockwise by rotationDegrees, for everything painted after this. */
        void transform(float cx, float cy, float rotationDegrees);

        void rect(float left, float top, float right, float bottom, float cornerRadius);

        void ellipse(float cx, float cy, float rx, float ry);

        /** x0, y0, x1, y1 ... a closed polygon. */
        void polygon(float[] xy);
    }

    /** Steps of the heart outline (spec: 96). */
    public static final int HEART_STEPS = 96;

    public static void draw(ClipMaskResolver.State s, float boxW, float boxH, Painter p) {
        float m = Math.min(boxW, boxH);
        float hw = Math.max(s.width * m / 2f, 0.5f);
        float hh = Math.max(s.height * m / 2f, 0.5f);
        p.transform(s.centerX * boxW, s.centerY * boxH, s.rotation);

        // Far enough to cover the box whatever the rotation and centre (the half plane and the band are unlimited).
        float far = 2f * (Math.abs(boxW) + Math.abs(boxH)) + 2f * Math.max(Math.abs(s.centerX * boxW), Math.abs(s.centerY * boxH));

        switch (s.shape) {
            case ClipMask.LINEAR:
                // Half plane y < 0 in the local frame (the side the rotation's "up" points to).
                p.rect(-far, -far, far, 0f, 0f);
                break;
            case ClipMask.MIRROR:
                // Band abs(y) <= hh, unlimited along x.
                p.rect(-far, -hh, far, hh, 0f);
                break;
            case ClipMask.RECTANGLE: {
                float shorter = Math.min(hw, hh);
                float r = Math.min(s.roundness * shorter, shorter);
                p.rect(-hw, -hh, hw, hh, Math.max(r, 0f));
                break;
            }
            case ClipMask.HEART:
                p.polygon(heart(hw, hh));
                break;
            case ClipMask.STAR:
                p.polygon(star(hw, hh));
                break;
            default: // circle
                p.ellipse(0f, 0f, hw, hh);
                break;
        }
    }

    /** x = 16 sin^3 t, y = 13 cos t - 5 cos 2t - 2 cos 3t - cos 4t; point = ((x / 16) * hw, -((y + 2.6) / 14.4) * hh). */
    static float[] heart(float hw, float hh) {
        float[] xy = new float[HEART_STEPS * 2];
        for (int k = 0; k < HEART_STEPS; k++) {
            double t = 2.0 * Math.PI * k / HEART_STEPS;
            double x = 16.0 * Math.pow(Math.sin(t), 3);
            double y = 13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t) - 2.0 * Math.cos(3 * t) - Math.cos(4 * t);
            xy[2 * k] = (float) ((x / 16.0) * hw);
            xy[2 * k + 1] = (float) (-((y + 2.6) / 14.4) * hh);
        }
        return xy;
    }

    /** 5 points, 10 vertices alternating outer radius 1 and inner 0.42, vertex k at -90 deg + k * 36 deg. */
    static float[] star(float hw, float hh) {
        float[] xy = new float[20];
        for (int k = 0; k < 10; k++) {
            double angle = Math.toRadians(-90.0 + k * 36.0);
            double r = k % 2 == 0 ? 1.0 : 0.42;
            xy[2 * k] = (float) (Math.cos(angle) * r * hw);
            xy[2 * k + 1] = (float) (Math.sin(angle) * r * hh);
        }
        return xy;
    }
}
