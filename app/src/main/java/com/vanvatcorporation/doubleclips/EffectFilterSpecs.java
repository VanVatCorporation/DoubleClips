package com.vanvatcorporation.doubleclips;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GENERATED from the iOS tables ({@code EffectFilterSpecs.swift} and {@code TransitionMaskSpecs.swift}, which iOS
 * generates from its filter registry): which {@code filters} ops an effect style may use and which {@code mask} shapes a
 * transition style may use, with every parameter's default, neutral value, range and whether the strength slider scales
 * it. Regenerate from the iOS files when they change; do not edit by hand. Drawing code lives in the renderers.
 * Plain Java (no Android classes).
 */
final class EffectFilterSpecs {

    private EffectFilterSpecs() {}

    /** Effect {@code filters} ops. */
    static final Map<String, EffectFilterOps.Spec> FILTERS;
    /** Transition {@code mask} shapes. */
    static final Map<String, EffectFilterOps.Spec> MASKS;

    private static EffectFilterOps.Param P(String name, double def, double neutral, double min, double max, boolean scaled) {
        return new EffectFilterOps.Param(name, def, neutral, min, max, scaled);
    }

    private static EffectFilterOps.StringOption S(String name, String[] allowed, String def) {
        return new EffectFilterOps.StringOption(name, allowed, def);
    }

    private static EffectFilterOps.Spec spec(EffectFilterOps.Param[] params, String[] flags, EffectFilterOps.StringOption[] strings) {
        return new EffectFilterOps.Spec(params, flags, strings);
    }

    static {
        Map<String, EffectFilterOps.Spec> filters = new LinkedHashMap<>();
        filters.put("blur", spec(new EffectFilterOps.Param[]{P("radius", 0.0, 0.0, 0.0, 0.2, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("colorControls", spec(new EffectFilterOps.Param[]{P("brightness", 0.0, 0.0, -1.0, 1.0, true), P("contrast", 1.0, 1.0, 0.0, 4.0, true), P("saturation", 1.0, 1.0, 0.0, 4.0, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("photoEffect", spec(new EffectFilterOps.Param[]{}, new String[]{}, new EffectFilterOps.StringOption[]{S("name", new String[]{"noir", "chrome", "fade", "instant", "mono", "process", "tonal", "transfer"}, "noir")}));
        filters.put("sepia", spec(new EffectFilterOps.Param[]{P("intensity", 1.0, 0.0, 0.0, 1.0, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("vignette", spec(new EffectFilterOps.Param[]{P("intensity", 1.0, 0.0, 0.0, 3.0, true), P("radius", 1.4, 1.4, 0.0, 3.0, false)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("bloom", spec(new EffectFilterOps.Param[]{P("radius", 0.025, 0.0, 0.0, 0.2, true), P("intensity", 1.0, 0.6, 0.0, 1.2, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("pixellate", spec(new EffectFilterOps.Param[]{P("scale", 0.012, 0.0, 0.0, 0.2, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("dotScreen", spec(new EffectFilterOps.Param[]{P("width", 0.006, 0.0, 0.0, 0.1, true), P("angle", 0.5, 0.5, -7.0, 7.0, false), P("sharpness", 0.7, 0.7, 0.0, 1.0, false)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("kaleidoscope", spec(new EffectFilterOps.Param[]{P("count", 6.0, 6.0, 1.0, 24.0, false), P("angle", 0.0, 0.0, -100.0, 100.0, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("twirl", spec(new EffectFilterOps.Param[]{P("radius", 0.45, 0.45, 0.05, 1.0, false), P("angle", 0.0, 0.0, -12.0, 12.0, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("bump", spec(new EffectFilterOps.Param[]{P("radius", 0.45, 0.45, 0.05, 1.0, false), P("scale", 0.0, 0.0, -3.0, 3.0, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("rgbShift", spec(new EffectFilterOps.Param[]{P("dx", 0.01, 0.0, 0.0, 0.1, true)}, new String[]{"randomSign"}, new EffectFilterOps.StringOption[]{}));
        filters.put("tearSlices", spec(new EffectFilterOps.Param[]{P("amount", 1.0, 0.0, 0.0, 3.0, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("crossProcess", spec(new EffectFilterOps.Param[]{}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        filters.put("vhs", spec(new EffectFilterOps.Param[]{P("amount", 1.0, 0.0, 0.0, 2.0, true)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        // Added by hand to match iOS (see EffectFilterSpecs.swift): the picture repeated in a cols x rows grid, each copy cropped to
        // the cell's shape. Not scaled by the strength slider.
        filters.put("tile_grid", spec(new EffectFilterOps.Param[]{P("cols", 2.0, 1.0, 1.0, 8.0, false), P("rows", 1.0, 1.0, 1.0, 8.0, false)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        FILTERS = Collections.unmodifiableMap(filters);

        Map<String, EffectFilterOps.Spec> masks = new LinkedHashMap<>();
        masks.put("wipe", spec(new EffectFilterOps.Param[]{P("reveal", 0.0, 0.0, 0.0, 1.0, false), P("angle", 180.0, 0.0, -360.0, 360.0, false), P("softness", 0.0, 0.0, 0.0, 0.5, false)}, new String[]{"normalized"}, new EffectFilterOps.StringOption[]{}));
        masks.put("circle", spec(new EffectFilterOps.Param[]{P("reveal", 0.0, 0.0, 0.0, 1.0, false), P("cx", 0.5, 0.5, 0.0, 1.0, false), P("cy", 0.5, 0.5, 0.0, 1.0, false), P("softness", 0.0, 0.0, 0.0, 1.0, false)}, new String[]{"invert"}, new EffectFilterOps.StringOption[]{}));
        masks.put("noise", spec(new EffectFilterOps.Param[]{P("reveal", 0.0, 0.0, 0.0, 1.0, false), P("softness", 0.001, 0.001, 0.001, 0.5, false), P("grain", 1.0, 1.0, 1.0, 64.0, false)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        masks.put("slices", spec(new EffectFilterOps.Param[]{P("reveal", 0.0, 0.0, 0.0, 1.0, false), P("angle", 0.0, 0.0, -360.0, 360.0, false), P("count", 10.0, 10.0, 1.0, 64.0, false)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        masks.put("clock", spec(new EffectFilterOps.Param[]{P("reveal", 0.0, 0.0, 0.0, 1.0, false), P("start", 0.0, 0.0, -360.0, 360.0, false)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        // Added by hand to match iOS (see TransitionMaskSpecs.swift): "rect" = a centred rectangle that is `reveal` of the canvas in both
        // directions (crop transitions); "distance" = B shows where A and B differ least first (needs both pictures).
        masks.put("rect", spec(new EffectFilterOps.Param[]{P("reveal", 0.0, 0.0, 0.0, 1.0, false), P("softness", 0.0, 0.0, 0.0, 0.5, false)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        masks.put("distance", spec(new EffectFilterOps.Param[]{P("reveal", 0.0, 0.0, 0.0, 1.0, false), P("softness", 0.04, 0.04, 0.001, 0.5, false)}, new String[]{}, new EffectFilterOps.StringOption[]{}));
        MASKS = Collections.unmodifiableMap(masks);
    }
}
