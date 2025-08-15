package com.vanvatcorporation.doubleclips.helper;

import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class AnimationHelper {
    public static View AnimateViewX(@NonNull View view, float from, float to, long durationMs, Interpolator interpolator, @Nullable Runnable endAction)
    {
        view.setTranslationX(from); // Set initial position outside the screen
        view.animate()
                .translationX(to) // Slide in from the right
                .setDuration(durationMs) // Set the duration of the animation
                .setInterpolator(interpolator)
                .withEndAction(endAction).start();
        return view;
    }
    public static View AnimateFade(@NonNull View view, float from, float to, long durationMs, Interpolator interpolator, @Nullable Runnable endAction)
    {
        view.setAlpha(from); // Set initial position outside the screen
        view.animate()
                .alpha(to)
                .setDuration(durationMs) // Set the duration of the animation
                .setInterpolator(interpolator)
                .withEndAction(endAction).start();
        return view;
    }
}
