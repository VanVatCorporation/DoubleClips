package com.vanvatcorporation.doubleclips;

/**
 * Gaussian blur of an 8-bit mask, as three box blurs per axis (the usual close approximation). Edge pixels are repeated
 * rather than faded to zero: a shape that runs off the box (a half plane, a band) must not soften at the box's own
 * edge, where it simply continues. Plain Java.
 */
public final class MaskBlur {

    private MaskBlur() { }

    /** Blurs {@code data} (w x h, row after row) in place; sigma in pixels, 0.3 or less does nothing. */
    public static void blur(byte[] data, int w, int h, float sigma) {
        if (sigma <= 0.3f || w < 1 || h < 1) return;
        int[] sizes = boxSizes(sigma, 3);
        byte[] tmp = new byte[data.length];
        for (int size : sizes) {
            int r = (size - 1) / 2;
            if (r < 1) continue;
            boxH(data, tmp, w, h, r);
            boxV(tmp, data, w, h, r);
        }
    }

    /** Box widths (odd) whose repeated blur approximates a Gaussian of this sigma (Kovesi / Ivan Kutskir). */
    static int[] boxSizes(float sigma, int n) {
        double ideal = Math.sqrt(12.0 * sigma * sigma / n + 1.0);
        int wl = (int) Math.floor(ideal);
        if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0);
        int m = (int) Math.round(mIdeal);
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) sizes[i] = i < m ? wl : wu;
        return sizes;
    }

    private static void boxH(byte[] src, byte[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w;
            int first = src[base] & 0xFF;
            int sum = r * first;
            for (int j = 0; j <= r; j++) sum += src[base + Math.min(j, w - 1)] & 0xFF;
            for (int x = 0; x < w; x++) {
                dst[base + x] = (byte) ((sum + div / 2) / div);
                int add = src[base + Math.min(x + r + 1, w - 1)] & 0xFF;
                int sub = src[base + Math.max(x - r, 0)] & 0xFF;
                sum += add - sub;
            }
        }
    }

    private static void boxV(byte[] src, byte[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int first = src[x] & 0xFF;
            int sum = r * first;
            for (int j = 0; j <= r; j++) sum += src[Math.min(j, h - 1) * w + x] & 0xFF;
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (byte) ((sum + div / 2) / div);
                int add = src[Math.min(y + r + 1, h - 1) * w + x] & 0xFF;
                int sub = src[Math.max(y - r, 0) * w + x] & 0xFF;
                sum += add - sub;
            }
        }
    }
}
