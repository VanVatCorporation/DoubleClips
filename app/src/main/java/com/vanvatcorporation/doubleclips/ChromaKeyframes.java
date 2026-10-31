package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.VideoProperties.ValueType;

import java.util.ArrayList;

/**
 * A clip's chroma-key sliders as keyframes, and the keeping of those keyframes in step with the key list. The sliders are
 * keyframed under the key's PLACE in {@link EditingActivity.Clip#chromaKeys} ({@link ValueType#chroma}), so taking a key out of
 * the list moves every later key down one place: its keyframes have to move with it, or they would animate the wrong colour.
 * The Background panel calls these inside the same undo step as the list change. Same rules as desktop.
 */
public final class ChromaKeyframes {

    private ChromaKeyframes() { }

    /** A slider of key number {@code index} of the clip's list at a global time: the keyframed value when some keyframe holds it, else the key's own. */
    public static float valueAt(EditingActivity.Clip clip, int index, int param, float timeSeconds) {
        ChromaKey key = clip.chromaKeys.get(index);
        if (clip.keyframes != null && clip.videoProperties != null && index < ChromaKey.MAX_KEYS) {
            ValueType type = ValueType.chroma(index, param);
            if (clip.keyframes.isAnimated(type)) return clip.keyframes.getValueAtTime(clip, timeSeconds, type);
        }
        return key.get(param);
    }

    /** The clip's keys packed for the shader at a global time (their keyframed sliders resolved); null when none is switched on. */
    public static ChromaKey.Packed pack(EditingActivity.Clip clip, float timeSeconds, float boxW, float boxH) {
        return ChromaKey.pack(clip.chromaKeys, (index, param, key) -> valueAt(clip, index, param, timeSeconds), boxW, boxH);
    }

    /**
     * Call right after the key at {@code removed} was taken out of the clip's list: the keyframed sliders of the keys after it
     * move down one place (what they hold and the values they keyed), the last place is freed, and a keyframe that held
     * nothing but sliders of the removed key is deleted.
     */
    public static void keyRemoved(EditingActivity.Clip clip, int removed) {
        if (clip.keyframes == null || clip.keyframes.keyframes == null) return;
        for (EditingActivity.Keyframe k : new ArrayList<>(clip.keyframes.keyframes)) {
            for (int param = 0; param < ChromaKey.PARAM_COUNT; param++) {
                for (int place = removed; place < ChromaKey.MAX_KEYS; place++) {
                    ValueType to = ValueType.chroma(place, param);
                    if (place + 1 < ChromaKey.MAX_KEYS) {
                        ValueType from = ValueType.chroma(place + 1, param);
                        boolean holds = k.animates(from);
                        float value = k.value == null ? 0f : k.value.getValue(from);
                        k.animate(to, holds);
                        if (k.value != null) k.value.setValue(holds ? value : 0f, to);
                    } else {
                        k.animate(to, false);
                        if (k.value != null) k.value.setValue(0f, to);
                    }
                }
            }
            if (KeyframeChannels.holdsNothing(k.channels)) clip.keyframes.keyframes.remove(k);
        }
    }

    /** All keyframes of the key at {@code index} go (its sliders are plain values again); emptied keyframes are deleted. */
    public static void clearKey(EditingActivity.Clip clip, int index) {
        if (clip.keyframes == null || clip.keyframes.keyframes == null) return;
        for (EditingActivity.Keyframe k : new ArrayList<>(clip.keyframes.keyframes)) {
            for (int param = 0; param < ChromaKey.PARAM_COUNT; param++) {
                ValueType type = ValueType.chroma(index, param);
                if (k.animates(type)) k.animate(type, false);
                if (k.value != null) k.value.setValue(0f, type);
            }
            if (KeyframeChannels.holdsNothing(k.channels)) clip.keyframes.keyframes.remove(k);
        }
    }

    /** Does any keyframe key a slider of the key at {@code index}? */
    public static boolean isAnimated(EditingActivity.Clip clip, int index) {
        if (clip.keyframes == null) return false;
        for (int param = 0; param < ChromaKey.PARAM_COUNT; param++) {
            if (clip.keyframes.isAnimated(ValueType.chroma(index, param))) return true;
        }
        return false;
    }
}
