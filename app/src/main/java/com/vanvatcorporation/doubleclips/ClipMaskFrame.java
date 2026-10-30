package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A clip's masks for one frame: each resolved shape (in the clip's order) with how it combines with the ones before it, plus
 * the size, in canvas pixels, of the box they work in (the clip as drawn, scaled and before it is rotated and moved; for an
 * effect clip the whole canvas). Carried by a DrawCommand / EffectCommand to whoever draws it. Plain Java.
 */
public final class ClipMaskFrame {
    /** The first mask (the only one for most clips). */
    public final ClipMaskResolver.State state;
    /** Every mask, in order; never empty. */
    public final List<ClipMaskResolver.State> states;
    /** How each mask combines with the ones before it ({@link ClipMask#ADD} ...), same size as {@link #states}; the first one's is not used. */
    public final List<String> modes;
    public final float boxW, boxH;

    public ClipMaskFrame(ClipMaskResolver.State state, float boxW, float boxH) {
        this(Collections.singletonList(state), Collections.singletonList(ClipMask.ADD), boxW, boxH);
    }

    public ClipMaskFrame(List<ClipMaskResolver.State> states, List<String> modes, float boxW, float boxH) {
        this.states = Collections.unmodifiableList(new ArrayList<>(states));
        this.modes = Collections.unmodifiableList(new ArrayList<>(modes));
        this.state = this.states.get(0);
        this.boxW = boxW;
        this.boxH = boxH;
    }

    /** True when the shader has to flip the mask picture: only a lone mask is flipped there, with several each one is flipped before they are combined. */
    public boolean shaderInvert() {
        return states.size() == 1 && state.invert;
    }
}
