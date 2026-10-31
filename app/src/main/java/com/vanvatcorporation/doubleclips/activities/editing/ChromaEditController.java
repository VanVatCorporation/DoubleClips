package com.vanvatcorporation.doubleclips.activities.editing;

import android.view.View;

import com.vanvatcorporation.doubleclips.ChromaKey;
import com.vanvatcorporation.doubleclips.ChromaKeyframes;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.VideoProperties.ValueType;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Background panel's logic (see ChromaEditSpecificAreaScreen): chroma keys on the selected VIDEO or IMAGE clip.
 * <ul>
 *   <li>A slider is a plain value on the key until one of its diamonds is set; from then on it is keyframed (the clip's own
 *       keyframes, channel "chromaIntensity0" ...), and dragging it writes the keyframe at the playhead. Taking the last
 *       keyframe off leaves the value where it was.</li>
 *   <li>Every gesture (a drag, a typed number, a switch, a colour, a diamond, adding or removing a key) is ONE undo step, which
 *       covers the keys AND their keyframes.</li>
 *   <li>The eyedropper takes the colour from the preview with the clip's own keys switched off for that read.</li>
 * </ul>
 */
public class ChromaEditController {

    public interface Host {
        EditingActivity.Clip selectedClip();
        float currentTime();
        int frameRate();
        /** Easing of a new keyframe. */
        EditingActivity.EasingType easing();
        void requestRender();
        void execute(CommandUtils.Command command);
        /** The keys or their keyframes changed (a gesture ended, undo, redo): the timeline's diamonds follow. */
        void chromaChanged(EditingActivity.Clip clip);
        void message(String text);
        /** Waits for a tap on the preview and reports its colour; on the UI thread. */
        void startColorPick(EditingActivity.Clip clip, ColorPick pick);
        void cancelColorPick();
    }

    /** The result of an eyedropper tap. */
    public interface ColorPick {
        void onPicked(int argb);
        void onCancelled();
    }

    private final ChromaEditSpecificAreaScreen panel;
    private final Host host;
    private Snapshot gestureBefore;
    /** The card whose eyedropper is waiting for a tap, or -1. */
    private int pickingKey = -1;

    public ChromaEditController(ChromaEditSpecificAreaScreen panel, Host host) {
        this.panel = panel;
        this.host = host;
        panel.onOpen.add(this::refresh);
        panel.onClose.add(this::cancelPick);
        panel.setListener(new ChromaEditSpecificAreaScreen.Listener() {
            @Override public void onAddKey() { addKey(); }
            @Override public void onRemoveKey(int key) { removeKey(key); }
            @Override public void onEnabled(int key, boolean enabled) { setEnabled(key, enabled); }
            @Override public void onColor(int key, int argb) { setColor(key, argb); }
            @Override public void onEyedropper(int key) { toggleEyedropper(key); }
            @Override public void onValueStart() { begin(); }
            @Override public void onValueChanging(int key, int param, float value) { liveValue(key, param, value); }
            @Override public void onValueCommit(int key, int param, float value) { commitValue(key, param, value); }
            @Override public void onDiamond(int key, int param) { toggleKeyframe(key, param); }
        });
    }

    /** Chroma keys work on picture clips: video and image. */
    public static boolean supports(EditingActivity.Clip clip) {
        return clip != null && (clip.type == EditingActivity.ClipType.VIDEO || clip.type == EditingActivity.ClipType.IMAGE);
    }

    public boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    /** The toolbar's Background button. */
    public void open() {
        EditingActivity.Clip clip = host.selectedClip();
        if (clip == null) {
            host.message("You need to pick a clip first!");
            return;
        }
        if (!supports(clip)) {
            host.message("Chroma keys work on video and image clips.");
            return;
        }
        panel.open();
    }

    /** Another clip was picked: the panel follows, or closes when the new one has no background to remove. */
    public void onSelectionChanged() {
        cancelPick();
        if (!isOpen()) return;
        if (!supports(host.selectedClip())) {
            panel.close();
            return;
        }
        refresh();
    }

    /** The playhead moved: the sliders show their keyframed values at the new time. */
    public void onPlayheadMoved() {
        if (!isOpen() || gestureBefore != null) return;
        refresh();
    }

    public void refresh() {
        if (!isOpen()) return;
        showInPanel();
    }

    private void showInPanel() {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        List<ChromaEditSpecificAreaScreen.KeyState> states = new ArrayList<>();
        if (clip.chromaKeys != null) {
            float global = clip.startTime + localTime(clip);
            float local = localTime(clip);
            for (int i = 0; i < clip.chromaKeys.size() && i < ChromaKey.MAX_KEYS; i++) {
                ChromaKey key = clip.chromaKeys.get(i);
                ChromaEditSpecificAreaScreen.KeyState state = new ChromaEditSpecificAreaScreen.KeyState();
                state.enabled = key.enabled;
                state.argb = key.argb();
                for (int p = 0; p < ChromaKey.PARAM_COUNT; p++) {
                    ValueType type = ValueType.chroma(i, p);
                    state.values[p] = ChromaKeyframes.valueAt(clip, i, p, global);
                    state.modes[p] = clip.keyframes == null || !clip.keyframes.isAnimated(type) ? ClipEditSpecificAreaScreen.KeyMode.OFF
                            : (clip.keyframes.keyframeIndex(local, type) >= 0 ? ClipEditSpecificAreaScreen.KeyMode.ON_KEY
                            : ClipEditSpecificAreaScreen.KeyMode.ANIMATED);
                }
                states.add(state);
            }
        }
        panel.show(states, insideClip(clip), pickingKey);
    }

    // ---- panel events ------------------------------------------------------------------------------

    private void addKey() {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        if (clip.chromaKeys != null && clip.chromaKeys.size() >= ChromaKey.MAX_KEYS) {
            host.message("A clip can have up to " + ChromaKey.MAX_KEYS + " chroma keys.");
            return;
        }
        gestureBefore = Snapshot.of(clip);
        if (clip.chromaKeys == null) clip.chromaKeys = new ArrayList<>();
        clip.chromaKeys.add(new ChromaKey());
        finish(clip);
    }

    private void removeKey(int index) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || !validKey(clip, index)) return;
        cancelPick();
        gestureBefore = Snapshot.of(clip);
        clip.chromaKeys.remove(index);
        ChromaKeyframes.clearKey(clip, index);      // its keyframes go ...
        ChromaKeyframes.keyRemoved(clip, index);    // ... and the later keys' move down a place with them
        if (clip.chromaKeys.isEmpty()) clip.chromaKeys = null;
        finish(clip);
    }

    private void setEnabled(int index, boolean enabled) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || !validKey(clip, index) || clip.chromaKeys.get(index).enabled == enabled) return;
        gestureBefore = Snapshot.of(clip);
        clip.chromaKeys.get(index).enabled = enabled;
        finish(clip);
    }

    private void setColor(int index, int argb) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || !validKey(clip, index)) return;
        String hex = ChromaKey.toHex(argb);
        if (hex.equalsIgnoreCase(clip.chromaKeys.get(index).color)) return;
        gestureBefore = Snapshot.of(clip);
        clip.chromaKeys.get(index).color = hex;
        finish(clip);
    }

    private void toggleEyedropper(int index) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || !validKey(clip, index)) return;
        if (pickingKey == index) {
            cancelPick();
            return;
        }
        pickingKey = index;
        host.startColorPick(clip, new ColorPick() {
            @Override public void onPicked(int argb) {
                int key = pickingKey;
                pickingKey = -1;
                EditingActivity.Clip now = host.selectedClip();
                if (now == clip && key >= 0) {
                    setColor(key, argb);
                } else {
                    refresh();
                }
            }

            @Override public void onCancelled() {
                pickingKey = -1;
                refresh();
            }
        });
        refresh();
    }

    /** Stops waiting for an eyedropper tap, if it was. */
    private void cancelPick() {
        if (pickingKey < 0) return;
        pickingKey = -1;
        host.cancelColorPick();
        refresh();
    }

    private void begin() {
        EditingActivity.Clip clip = host.selectedClip();
        gestureBefore = supports(clip) ? Snapshot.of(clip) : null;
    }

    private void liveValue(int index, int param, float value) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || !validKey(clip, index)) return;
        applyValue(clip, index, param, value);
        host.requestRender();
    }

    private void commitValue(int index, int param, float value) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || !validKey(clip, index)) return;
        if (gestureBefore == null) begin();
        applyValue(clip, index, param, value);
        finish(clip);
    }

    /** The diamond: a keyframe of this slider at the playhead is taken off, or one is put there with the value it has now. */
    private void toggleKeyframe(int index, int param) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || !validKey(clip, index)) return;
        if (!insideClip(clip)) {
            host.message("Move the playhead onto the clip to place a keyframe.");
            return;
        }
        gestureBefore = Snapshot.of(clip);
        ValueType type = ValueType.chroma(index, param);
        float local = snappedLocal(clip);
        if (clip.keyframes.keyframeIndex(local, type) >= 0) {
            Float removed = clip.keyframes.removeKey(type, local);
            // No longer animated: the value stays where it was.
            if (removed != null && !clip.keyframes.isAnimated(type)) clip.chromaKeys.get(index).set(param, removed);
        } else {
            float now = ChromaKeyframes.valueAt(clip, index, param, clip.startTime + local);
            clip.keyframes.setKey(type, local, now, clip, host.frameRate(), host.easing());
        }
        finish(clip);
    }

    // ---- the edit itself ---------------------------------------------------------------------------

    private void applyValue(EditingActivity.Clip clip, int index, int param, float value) {
        float v = Math.max(0f, Math.min(ChromaKey.SCALE_MAX, value));
        ValueType type = ValueType.chroma(index, param);
        if (clip.keyframes != null && clip.keyframes.isAnimated(type)) {
            if (!insideClip(clip)) return; // a keyframe can't be placed outside the clip
            clip.keyframes.setKey(type, snappedLocal(clip), v, clip, host.frameRate(), host.easing());
        } else {
            clip.chromaKeys.get(index).set(param, v);
        }
    }

    private static boolean validKey(EditingActivity.Clip clip, int index) {
        return clip.chromaKeys != null && index >= 0 && index < clip.chromaKeys.size();
    }

    private float localTime(EditingActivity.Clip clip) {
        return Math.min(Math.max(host.currentTime() - clip.startTime, 0f), clip.duration);
    }

    private boolean insideClip(EditingActivity.Clip clip) {
        float t = host.currentTime();
        return t >= clip.startTime - 0.001f && t <= clip.startTime + clip.duration + 0.001f;
    }

    /** The playhead's clip-local time on the project's frame grid. */
    private float snappedLocal(EditingActivity.Clip clip) {
        float local = localTime(clip);
        int fps = host.frameRate();
        if (fps > 0) local = Math.round(local * fps) / (float) fps;
        return Math.min(Math.max(local, 0f), clip.duration);
    }

    // ---- undo ----------------------------------------------------------------------------------

    private void finish(EditingActivity.Clip clip) {
        Snapshot before = gestureBefore;
        gestureBefore = null;
        Snapshot after = Snapshot.of(clip);
        if (before != null && !before.fingerprint.equals(after.fingerprint)) {
            host.execute(new ChromaCommand(clip, before, after, host));
        }
        host.chromaChanged(clip);
        refresh();
        host.requestRender();
    }

    /** The clip's chroma keys and its keyframes at one moment (copies): a slider's keyframes live in the clip's own. */
    private static final class Snapshot {
        final List<ChromaKey> keys;
        final List<EditingActivity.Keyframe> keyframes;
        final String fingerprint;

        private Snapshot(List<ChromaKey> keys, List<EditingActivity.Keyframe> keyframes) {
            this.keys = keys;
            this.keyframes = keyframes;
            StringBuilder sb = new StringBuilder();
            if (keys != null) {
                for (ChromaKey k : keys) {
                    sb.append(k.enabled).append('|').append(k.color).append('|').append(k.intensity).append('|').append(k.shadow)
                            .append('|').append(k.feather).append('|').append(k.cleanUpEdge).append(';');
                }
            }
            sb.append('#');
            if (keyframes != null) {
                for (EditingActivity.Keyframe k : keyframes) {
                    sb.append(k.getLocalTime()).append('|').append(k.channels).append('|').append(k.easing).append('|')
                            .append(k.value == null ? "-" : Arrays.toString(k.value.valueChroma)).append(';');
                }
            }
            fingerprint = sb.toString();
        }

        static Snapshot of(EditingActivity.Clip clip) {
            List<EditingActivity.Keyframe> copy = new ArrayList<>();
            if (clip.keyframes != null && clip.keyframes.keyframes != null) {
                for (EditingActivity.Keyframe k : clip.keyframes.keyframes) copy.add(k.copy());
            }
            return new Snapshot(ChromaKey.copyAll(clip.chromaKeys), copy);
        }

        void applyTo(EditingActivity.Clip clip) {
            List<ChromaKey> keyCopy = ChromaKey.copyAll(keys);
            clip.chromaKeys = keyCopy == null || keyCopy.isEmpty() ? null : keyCopy;
            if (clip.keyframes != null) {
                List<EditingActivity.Keyframe> list = new ArrayList<>();
                for (EditingActivity.Keyframe k : keyframes) list.add(k.copy());
                clip.keyframes.keyframes = list;
            }
        }
    }

    private static final class ChromaCommand implements CommandUtils.Command {
        private final EditingActivity.Clip clip;
        private final Snapshot before, after;
        private final Host host;

        ChromaCommand(EditingActivity.Clip clip, Snapshot before, Snapshot after, Host host) {
            this.clip = clip;
            this.before = before;
            this.after = after;
            this.host = host;
        }

        @Override
        public void execute() {
            after.applyTo(clip);
            host.chromaChanged(clip);
            host.requestRender();
        }

        @Override
        public void undo() {
            before.applyTo(clip);
            host.chromaChanged(clip);
            host.requestRender();
        }
    }
}
