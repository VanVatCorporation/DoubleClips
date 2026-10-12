package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The order of the effect picker (iOS EffectCatalog.pickerOrder): the four effects Android always had first, then
 * motion, colour, optics and fades. A style that is not listed here (a new file) follows the listed ones, sorted by
 * name. Plain Java.
 */
public final class EffectStyleCatalog {

    private static final List<String> PICKER_ORDER = Arrays.asList(
            "glitch-pulse", "warp-zoom", "lens-flare-surge", "spin-burst",
            "camera-shake", "simple-shake", "triggered-shake", "beat-pulse", "strobe", "flash",
            "rgb-shift", "vhs", "blur", "glow", "vignette", "noir", "vintage",
            "pixelate", "halftone", "kaleidoscope", "twirl", "bulge",
            "slow-push", "drift-blur", "heartbeat", "fade-in", "fade-out");

    private EffectStyleCatalog() {}

    /** All loaded styles in picker order. */
    public static List<EffectStyle> ordered(List<EffectStyle> styles) {
        List<EffectStyle> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String id : PICKER_ORDER) {
            for (EffectStyle s : styles) {
                if (s.getId().equals(id)) {
                    out.add(s);
                    used.add(id);
                }
            }
        }
        List<EffectStyle> rest = new ArrayList<>();
        for (EffectStyle s : styles) if (!used.contains(s.getId())) rest.add(s);
        Collections.sort(rest, Comparator.comparing(EffectStyle::getName, String.CASE_INSENSITIVE_ORDER));
        out.addAll(rest);
        return out;
    }
}
