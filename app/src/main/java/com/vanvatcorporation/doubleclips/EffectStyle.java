package com.vanvatcorporation.doubleclips;

/**
 * One data-driven effect style (a file in {@code animations/effects/<id>.json}), loaded by {@link EffectStyleLoader}.
 * Immutable. Plain Java (no Android classes) so the desktop port can use it as-is.
 *
 * An effect clip is an adjustment layer: it processes everything drawn on the tracks BEFORE it, for as long as the clip
 * lasts. A style describes that processing with the same channels and curves as a clip animation; the renderer applies
 * the resulting {@link ClipAnimationFrame} to the whole picture so far (not to one clip).
 */
public final class EffectStyle {

    /** Author shown for a built-in style whose file names none (same string iOS uses). */
    public static final String BUILTIN_AUTHOR = "@doubleclips";

    private final String id;
    private final String name;
    private final String description;
    private final String author;
    private final String intensityLabel;
    private final float intensityMin, intensityMax;
    private final double period;
    private final double span;
    private final ClipAnimation animation;

    EffectStyle(String id, String name, String description, String author, String intensityLabel,
                float intensityMin, float intensityMax, double period, double span, ClipAnimation animation) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.author = author;
        this.intensityLabel = intensityLabel;
        this.intensityMin = intensityMin;
        this.intensityMax = intensityMax;
        this.period = period;
        this.span = span;
        this.animation = animation;
    }

    /** The style key an effect clip stores (lower case, [a-z0-9_-]). */
    public String getId() { return id; }
    public String getName() { return name; }
    /** May be null. */
    public String getDescription() { return description; }
    public String getAuthor() { return author; }
    /** What the strength slider means ("Strength", "Turns", "Zoom speed"...), or null when there is nothing to scale. */
    public String getIntensityLabel() { return intensityLabel; }
    public boolean hasIntensity() { return intensityLabel != null; }
    public float getIntensityMin() { return intensityMin; }
    public float getIntensityMax() { return intensityMax; }
    /** Seconds per loop; 0 = the curves run once (see {@link #getSpan()}). */
    public double getPeriod() { return period; }
    /** Seconds the curves take to run once, independent of the clip's length (held at the end); 0 = across the clip. */
    public double getSpan() { return span; }
    public ClipAnimation getAnimation() { return animation; }

    /**
     * Curve progress (0..1) at {@code elapsedSeconds} into an effect clip lasting {@code clipDurationSeconds}:
     * looped every period, or seconds over span (held at 1), or the clip's own progress.
     */
    public float progressAt(double elapsedSeconds, double clipDurationSeconds) {
        double e = Math.max(elapsedSeconds, 0.0);
        if (period > 0.0) return (float) ((e % period) / period);
        if (span > 0.0) return (float) Math.min(e / span, 1.0);
        if (clipDurationSeconds <= 0.0) return 0f;
        return (float) Math.min(e / clipDurationSeconds, 1.0);
    }

    /**
     * The whole-picture look at this moment of an effect clip: the curves at {@link #progressAt}, then pushed away
     * from neutral by {@code strength} (1 = the file's own look; only meaningful when {@link #hasIntensity()}).
     */
    public ClipAnimationFrame frameAt(double elapsedSeconds, double clipDurationSeconds, float strength) {
        ClipAnimationFrame look = animation.evaluate(progressAt(elapsedSeconds, clipDurationSeconds));
        return hasIntensity() ? look.scaledDeviation(strength) : look;
    }
}
