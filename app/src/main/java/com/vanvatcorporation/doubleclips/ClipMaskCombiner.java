package com.vanvatcorporation.doubleclips;

/**
 * Combines the pictures of several masks into one (8-bit, 255 = the clip shows), the way iOS does: the first mask defines
 * the area, each later one ADDS to it (the lighter of the two), SUBTRACTS from it (base x (1 - picture)) or INTERSECTS with it
 * (base x picture). Plain Java, so the desktop port can share it.
 */
public final class ClipMaskCombiner {

    private ClipMaskCombiner() { }

    /** Flips a picture in place: 255 becomes 0 and the other way round. */
    public static void invert(byte[] picture) {
        for (int i = 0; i < picture.length; i++) picture[i] = (byte) (255 - (picture[i] & 0xFF));
    }

    /** Folds {@code picture} into {@code base} in place with {@code mode} (add, subtract or intersect; unknown = add). Same size. */
    public static void combine(byte[] base, byte[] picture, String mode) {
        int n = Math.min(base.length, picture.length);
        switch (ClipMask.normalizeMode(mode)) {
            case ClipMask.SUBTRACT:
                for (int i = 0; i < n; i++) {
                    int b = base[i] & 0xFF, p = picture[i] & 0xFF;
                    base[i] = (byte) ((b * (255 - p) + 127) / 255);
                }
                break;
            case ClipMask.INTERSECT:
                for (int i = 0; i < n; i++) {
                    int b = base[i] & 0xFF, p = picture[i] & 0xFF;
                    base[i] = (byte) ((b * p + 127) / 255);
                }
                break;
            default:
                for (int i = 0; i < n; i++) {
                    int b = base[i] & 0xFF, p = picture[i] & 0xFF;
                    if (p > b) base[i] = (byte) p;
                }
                break;
        }
    }
}
