package com.vanvatcorporation.doubleclips.helper;

public class MathHelper {
    public static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    public static float spring(float t, float mass, float stiffness, float damping) {
        // Simplified spring formula
        return 1 - (float)Math.exp(-damping * t) * (float)Math.cos(stiffness * t);
    }
    public static float bezier(float t, float p0, float p1, float p2, float p3) {
        float u = 1 - t;
        return u*u*u*p0 + 3*u*u*t*p1 + 3*u*t*t*p2 + t*t*t*p3;
    }
    public static float damped(float t, float omega, float zeta) {
        // omega = angular frequency, zeta = damping ratio
        return 1 - (float)Math.exp(-zeta * omega * t) * (1 + zeta * omega * t);
    }

}
