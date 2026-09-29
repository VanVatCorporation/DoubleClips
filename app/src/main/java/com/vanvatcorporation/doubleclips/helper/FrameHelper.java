package com.vanvatcorporation.doubleclips.helper;

public class FrameHelper {
    public static float calculateToNearestFrame(float time, int framePerSecond) {
        if (framePerSecond <= 0) return 0;

        // 1. Find which frame index we are closest to
        // Example: 3.14 * 30 = 94.2. Round(94.2) = 94.
        long closestFrameIndex = Math.round(time * framePerSecond);

        // 2. Convert that frame index back into seconds
        // Example: 94 / 30.0 = 3.1333...
        double snappedTime = (double) closestFrameIndex / framePerSecond;

        return ((float) snappedTime);
    }
}
