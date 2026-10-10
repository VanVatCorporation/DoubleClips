package com.vanvatcorporation.doubleclips;

/**
 * One data-driven transition style (a file in {@code animations/transitions/<id>.json}), loaded by
 * {@link TransitionStyleLoader}. Immutable. Plain Java (no Android classes) so the desktop port can use it as-is.
 *
 * There is no cross dissolve: exactly one of the two pictures is drawn at any moment, each through the normal
 * clip pipeline with its side's channels applied on top of the clip's own properties and its In / Out
 * animation. Over the whole transition window (progress p = 0..1) clip A is drawn while p &lt; {@link #getCut()}
 * with {@link #getFrom()}'s look, and clip B from p &gt;= cut with {@link #getTo()}'s look.
 */
public final class TransitionStyle {

    private final String id;
    private final String name;
    private final String description;
    private final String author;
    private final float defaultDuration;
    private final double cut;
    private final ClipAnimation from;
    private final ClipAnimation to;

    TransitionStyle(String id, String name, String description, String author, float defaultDuration,
                    double cut, ClipAnimation from, ClipAnimation to) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.author = author;
        this.defaultDuration = defaultDuration;
        this.cut = cut;
        this.from = from;
        this.to = to;
    }

    /** The style key a transition stores in its effect (lower case, [a-z0-9_-]). */
    public String getId() { return id; }
    public String getName() { return name; }
    /** May be null. */
    public String getDescription() { return description; }
    /** May be null. */
    public String getAuthor() { return author; }
    /** Suggested duration in seconds. */
    public float getDefaultDuration() { return defaultDuration; }
    /** Window progress at which the picture switches from A to B (0.05 .. 0.95). */
    public double getCut() { return cut; }
    /** The look of A, evaluated over the WHOLE window. */
    public ClipAnimation getFrom() { return from; }
    /** The look of B, evaluated over the WHOLE window. */
    public ClipAnimation getTo() { return to; }

    /** True while clip A is the picture drawn at window progress p. */
    public boolean showsFrom(float p) { return p < cut; }

    /** The channel values of whichever side is drawn at window progress p. */
    public ClipAnimationFrame frameAt(float p) { return (showsFrom(p) ? from : to).evaluate(p); }
}
