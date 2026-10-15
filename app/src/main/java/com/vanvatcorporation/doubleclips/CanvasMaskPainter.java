package com.vanvatcorporation.doubleclips;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** {@link ClipMaskGeometry} painted on a Canvas (the mask pictures of ClipMaskTextures, the shape tiles of the mask panel, the canvas outline). */
public final class CanvasMaskPainter implements ClipMaskGeometry.Painter {
    private final Canvas canvas;
    private final Paint paint;
    private final RectF rect = new RectF();
    private final Path path = new Path();

    public CanvasMaskPainter(Canvas canvas, Paint paint) {
        this.canvas = canvas;
        this.paint = paint;
    }

    @Override
    public void transform(float cx, float cy, float rotationDegrees) {
        canvas.translate(cx, cy);
        canvas.rotate(rotationDegrees); // clockwise on screen, like the spec's y-down rotation
    }

    @Override
    public void rect(float left, float top, float right, float bottom, float cornerRadius) {
        rect.set(left, top, right, bottom);
        if (cornerRadius > 0f) canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);
        else canvas.drawRect(rect, paint);
    }

    @Override
    public void ellipse(float cx, float cy, float rx, float ry) {
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        canvas.drawOval(rect, paint);
    }

    @Override
    public void polygon(float[] xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
        canvas.drawPath(path, paint);
    }
}
