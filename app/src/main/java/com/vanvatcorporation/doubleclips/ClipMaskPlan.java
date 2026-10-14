package com.vanvatcorporation.doubleclips;

/**
 * How big the mask picture is made for a box of boxW x boxH pixels and where the feather's blur is done.
 * <p>
 * The spec's feather is a Gaussian blur of sigma = feather * m * 0.5 box pixels (m = shorter side), skipped at 0.3 px or
 * less. The picture is made no bigger than {@code cap} on its long side (a speed choice, like iOS's 2048) and no bigger
 * than the blur needs: a blur of more than {@link #MAX_BLUR_SIGMA} texture pixels is done on a smaller picture instead.
 * Plain Java.
 */
public final class ClipMaskPlan {

    public static final float MAX_BLUR_SIGMA = 12f;
    public static final float MIN_SIGMA = 0.3f;

    public final int texW, texH;
    /** Texture pixels per box pixel. */
    public final float scale;
    /** Blur sigma in texture pixels, 0 = none. */
    public final float sigma;

    private ClipMaskPlan(int texW, int texH, float scale, float sigma) {
        this.texW = texW;
        this.texH = texH;
        this.scale = scale;
        this.sigma = sigma;
    }

    /** Sigma in BOX pixels for a feather fraction (spec: feather * m * 0.5). */
    public static float sigmaInBoxPixels(float feather, float boxW, float boxH) {
        return feather * Math.min(boxW, boxH) * 0.5f;
    }

    public static ClipMaskPlan of(float boxW, float boxH, float feather, int cap) {
        float w = Math.max(boxW, 1f), h = Math.max(boxH, 1f);
        float sigmaBox = sigmaInBoxPixels(feather, w, h);
        float k = Math.min(1f, cap / Math.max(w, h));
        float sigma = sigmaBox * k;
        if (sigmaBox <= MIN_SIGMA) {
            sigma = 0f;
        } else if (sigma > MAX_BLUR_SIGMA) {
            k *= MAX_BLUR_SIGMA / sigma;
            sigma = MAX_BLUR_SIGMA;
        }
        int tw = Math.max(4, Math.round(w * k));
        int th = Math.max(4, Math.round(h * k));
        return new ClipMaskPlan(tw, th, k, sigma);
    }
}
