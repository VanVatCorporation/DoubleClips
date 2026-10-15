package com.vanvatcorporation.doubleclips.activities.editing;

import android.view.View;

import com.google.gson.JsonObject;
import com.vanvatcorporation.doubleclips.ClipMask;
import com.vanvatcorporation.doubleclips.ClipMaskResolver;
import com.vanvatcorporation.doubleclips.MaskGizmoMath;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edits a clip's mask from the mask panel: what a change means (spec sections 5 and 6) and its undo step.
 * <ul>
 *   <li>Editing a value that has keyframes adds or changes its keyframe at the playhead (frame-snapped; a keyframe within
 *       0.01 s is "the keyframe there"); a value without keyframes changes the static value only.</li>
 *   <li>A diamond turns a value's animation on at the playhead, or takes its keyframe there off; removing a value's last
 *       keyframe leaves it at that value.</li>
 *   <li>Every gesture (a drag, a typed number, a tile, Reset, Invert, a diamond) is ONE undo step.</li>
 * </ul>
 * Works on picture clips (video, image, text) and effect clips.
 */
public final class MaskEditController {

    /** What the editor provides. */
    public interface Host {
        EditingActivity.Clip selectedClip();

        float currentTime();

        int frameRate();

        ClipMaskResolver.Easing easing();

        /** Re-render the preview at the current time. */
        void requestRender();

        /** Runs the command and records one undo step. */
        void execute(CommandUtils.Command command);

        /** The clip's mask changed (an edit, undo or redo). */
        void maskChanged(EditingActivity.Clip clip);

        void message(String text);
    }

    private static final String[] PARAMS = {
            ClipMaskResolver.X, ClipMaskResolver.Y, ClipMaskResolver.WIDTH, ClipMaskResolver.HEIGHT,
            ClipMaskResolver.ROTATION, ClipMaskResolver.FEATHER, ClipMaskResolver.ROUNDNESS};
    /** An existing keyframe this close to a time is "the keyframe at that time". */
    private static final float KEY_MATCH_SECONDS = 0.01f;

    private final MaskEditSpecificAreaScreen panel;
    private final Host host;
    private Snapshot gestureBefore;

    public MaskEditController(MaskEditSpecificAreaScreen panel, Host host) {
        this.panel = panel;
        this.host = host;
        panel.onOpen.add(this::refresh);
        panel.setListener(new MaskEditSpecificAreaScreen.Listener() {
            @Override public void onShape(String shapeOrNull) { pickShape(shapeOrNull); }
            @Override public void onValueStart() { begin(); }
            @Override public void onValueChanging(String param, float value) { liveValue(param, value); }
            @Override public void onValueCommit(String param, float value) { commitValue(param, value); }
            @Override public void onDiamond(String param) { toggleKey(param); }
            @Override public void onReset() { reset(); }
            @Override public void onInvert(boolean invert) { setInvert(invert); }
        });
    }

    public static boolean supports(EditingActivity.Clip clip) {
        if (clip == null || clip.type == null) return false;
        switch (clip.type) {
            case VIDEO: case IMAGE: case TEXT: case EFFECT: return true;
            default: return false;
        }
    }

    public boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    /** The toolbar's Mask button. */
    public void open() {
        EditingActivity.Clip clip = host.selectedClip();
        if (clip == null) {
            host.message("You need to pick a clip first!");
            return;
        }
        if (!supports(clip)) {
            host.message("Masks work on picture, text and effect clips.");
            return;
        }
        panel.open();
    }

    /** The selection changed: follow it, or close when the new clip can't have a mask. */
    public void onSelectionChanged() {
        if (!isOpen()) return;
        if (!supports(host.selectedClip())) {
            panel.close();
            return;
        }
        refresh();
    }

    /** The playhead moved: the values and diamonds are those at the new time (not while a drag is running). */
    public void onPlayheadMoved() {
        if (!isOpen() || gestureBefore != null) return;
        refresh();
    }

    /** Shows the selected clip's mask at the playhead. */
    public void refresh() {
        if (!isOpen()) return;
        showInPanel();
    }

    /** The selected clip's mask resolved at the playhead (what the canvas outline draws), or null without a mask. */
    public ClipMaskResolver.State stateAtPlayhead() {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.mask == null) return null;
        return ClipMaskResolver.resolve(clip.mask, clip.maskKeys, localTime(clip), host.easing());
    }

    // ---- canvas gestures (MaskGizmoView): one undo step each, several values at once -------------------

    public void canvasGestureBegin() {
        begin();
    }

    /** Applies what the gesture has changed so far (only those values), live: no undo step yet. */
    public void canvasGestureSet(MaskGizmoMath.Edit edit) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.mask == null) return;
        if (edit.x != null) applyValue(clip, ClipMaskResolver.X, edit.x);
        if (edit.y != null) applyValue(clip, ClipMaskResolver.Y, edit.y);
        if (edit.width != null) applyValue(clip, ClipMaskResolver.WIDTH, edit.width);
        if (edit.height != null) applyValue(clip, ClipMaskResolver.HEIGHT, edit.height);
        if (edit.rotation != null) applyValue(clip, ClipMaskResolver.ROTATION, edit.rotation);
        if (edit.feather != null) applyValue(clip, ClipMaskResolver.FEATHER, edit.feather);
        host.requestRender();
        showInPanel(); // the number boxes follow the canvas
    }

    public void canvasGestureEnd() {
        EditingActivity.Clip clip = host.selectedClip();
        if (supports(clip) && gestureBefore != null) finish(clip);
        else gestureBefore = null;
    }

    /** The gesture was interrupted: back to how the mask was when it began. */
    public void canvasGestureCancel() {
        EditingActivity.Clip clip = host.selectedClip();
        Snapshot before = gestureBefore;
        gestureBefore = null;
        if (before != null && supports(clip)) {
            before.applyTo(clip);
            host.maskChanged(clip);
            host.requestRender();
        }
    }

    private void showInPanel() {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        if (clip.mask == null) {
            panel.show(null, false, new float[PARAMS.length], new ClipEditSpecificAreaScreen.KeyMode[PARAMS.length], false);
            return;
        }
        float local = localTime(clip);
        ClipMaskResolver.State state = ClipMaskResolver.resolve(clip.mask, clip.maskKeys, local, host.easing());
        float[] values = new float[PARAMS.length];
        ClipEditSpecificAreaScreen.KeyMode[] modes = new ClipEditSpecificAreaScreen.KeyMode[PARAMS.length];
        for (int i = 0; i < PARAMS.length; i++) {
            values[i] = panelValueOf(state, PARAMS[i]);
            modes[i] = !isAnimated(clip, PARAMS[i]) ? ClipEditSpecificAreaScreen.KeyMode.OFF
                    : (keyIndexAt(clip, PARAMS[i], local) >= 0 ? ClipEditSpecificAreaScreen.KeyMode.ON_KEY
                    : ClipEditSpecificAreaScreen.KeyMode.ANIMATED);
        }
        panel.show(state.shape, state.invert, values, modes, insideClip(clip));
    }

    // ---- panel events --------------------------------------------------------------------------

    private void pickShape(String shape) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        if (shape != null && clip.mask != null && shape.equals(clip.mask.shape)) return; // the tile that is already chosen: nothing to do
        gestureBefore = Snapshot.of(clip);
        if (shape == null) {
            clip.mask = null;
            clip.maskKeys = null;
            dropUnreadableRaw(clip);
        } else if (clip.mask == null) {
            clip.mask = ClipMask.of(shape);
            clip.maskKeys = null;
            dropUnreadableRaw(clip);
        } else {
            clip.mask.switchShape(shape); // keeps centre, rotation, feather and invert; the new shape's size
        }
        finish(clip);
    }

    private void reset() {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.mask == null) return;
        gestureBefore = Snapshot.of(clip);
        clip.mask = ClipMask.of(clip.mask.shape); // the default mask of the same shape, keyframes cleared
        clip.maskKeys = null;
        finish(clip);
    }

    private void setInvert(boolean invert) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.mask == null || clip.mask.invert == invert) return;
        gestureBefore = Snapshot.of(clip);
        clip.mask.invert = invert;
        finish(clip);
    }

    private void begin() {
        EditingActivity.Clip clip = host.selectedClip();
        gestureBefore = supports(clip) ? Snapshot.of(clip) : null;
    }

    private void liveValue(String param, float value) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        applyValue(clip, param, value);
        host.requestRender();
    }

    private void commitValue(String param, float value) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        if (gestureBefore == null) begin();
        applyValue(clip, param, value);
        finish(clip);
    }

    private void toggleKey(String param) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.mask == null) return;
        if (!insideClip(clip)) {
            host.message("Move the playhead onto the clip to place a keyframe.");
            return;
        }
        gestureBefore = Snapshot.of(clip);
        float local = snappedLocal(clip);
        int index = keyIndexAt(clip, param, local);
        if (index >= 0) {
            Float removed = removeParam(clip, index, param);
            // No longer animated: the value stays where it was.
            if (removed != null && !isAnimated(clip, param)) {
                setStatic(clip.mask, param, removed / ClipMaskResolver.panelPerFraction(param));
            }
        } else {
            ClipMaskResolver.State now = ClipMaskResolver.resolve(clip.mask, clip.maskKeys, local, host.easing());
            setKey(clip, param, local, panelValueOf(now, param));
        }
        finish(clip);
    }

    // ---- the edit itself -----------------------------------------------------------------------

    private void applyValue(EditingActivity.Clip clip, String param, float panelValue) {
        if (clip.mask == null) return;
        float v = Math.max(ClipMaskResolver.panelMin(param), Math.min(ClipMaskResolver.panelMax(param), panelValue));
        if (isAnimated(clip, param)) {
            if (!insideClip(clip)) return; // a keyframe can't be placed outside the clip
            setKey(clip, param, snappedLocal(clip), v);
        } else {
            setStatic(clip.mask, param, v / ClipMaskResolver.panelPerFraction(param));
        }
    }

    private static void setStatic(ClipMask mask, String param, float fraction) {
        switch (param) {
            case ClipMaskResolver.X: mask.centerX = fraction; break;
            case ClipMaskResolver.Y: mask.centerY = fraction; break;
            case ClipMaskResolver.WIDTH: mask.width = fraction; break;
            case ClipMaskResolver.HEIGHT: mask.height = fraction; break;
            case ClipMaskResolver.ROTATION: mask.rotation = fraction; break;
            case ClipMaskResolver.FEATHER: mask.feather = fraction; break;
            default: mask.roundness = fraction; break;
        }
    }

    /** A value in the panel's units from the resolved mask. */
    private static float panelValueOf(ClipMaskResolver.State s, String param) {
        switch (param) {
            case ClipMaskResolver.X: return s.centerX * 100f;
            case ClipMaskResolver.Y: return s.centerY * 100f;
            case ClipMaskResolver.WIDTH: return s.width * 100f;
            case ClipMaskResolver.HEIGHT: return s.height * 100f;
            case ClipMaskResolver.ROTATION: return s.rotation;
            case ClipMaskResolver.FEATHER: return s.feather * 200f;
            default: return s.roundness * 100f;
        }
    }

    /** Puts {@code value} (panel units) into the keyframe at {@code time}: the one already there (it starts to hold this value too) or a new one that holds only it. */
    private static void setKey(EditingActivity.Clip clip, String param, float time, float value) {
        if (clip.maskKeys == null) clip.maskKeys = new ArrayList<>();
        ClipMask.Key key = null;
        for (ClipMask.Key k : clip.maskKeys) {
            if (Math.abs(k.time - time) <= KEY_MATCH_SECONDS) {
                key = k;
                break;
            }
        }
        if (key == null) {
            key = new ClipMask.Key();
            key.time = time;
            key.easing = "LINEAR"; // new keyframes are linear
            clip.maskKeys.add(key);
            Collections.sort(clip.maskKeys, (a, b) -> Float.compare(a.time, b.time));
        }
        key.values.put(param, value);
    }

    /** Takes {@code param} off the keyframe at {@code index} (the keyframe goes when it holds nothing else); returns the value it held. */
    private static Float removeParam(EditingActivity.Clip clip, int index, String param) {
        ClipMask.Key key = clip.maskKeys.get(index);
        Float removed = key.values.remove(param);
        if (key.values.isEmpty()) clip.maskKeys.remove(index);
        if (clip.maskKeys.isEmpty()) clip.maskKeys = null;
        return removed;
    }

    private static boolean isAnimated(EditingActivity.Clip clip, String param) {
        if (clip.maskKeys == null) return false;
        for (ClipMask.Key k : clip.maskKeys) if (k.values != null && k.values.get(param) != null) return true;
        return false;
    }

    /** Index of the keyframe at {@code local} that holds {@code param}, or -1. */
    private static int keyIndexAt(EditingActivity.Clip clip, String param, float local) {
        if (clip.maskKeys == null) return -1;
        for (int i = 0; i < clip.maskKeys.size(); i++) {
            ClipMask.Key k = clip.maskKeys.get(i);
            if (k.values != null && k.values.get(param) != null && Math.abs(k.time - local) <= KEY_MATCH_SECONDS) return i;
        }
        return -1;
    }

    /** A mask this build couldn't read stays in the clip's unknown keys; once the user chooses a mask here it must not come back. */
    private static void dropUnreadableRaw(EditingActivity.Clip clip) {
        JsonObject unknown = clip.getUnknownKeys();
        if (unknown == null) return;
        unknown.remove("mask");
        unknown.remove("maskKeys");
    }

    // ---- time ----------------------------------------------------------------------------------

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
            host.execute(new MaskCommand(clip, before, after, host));
        }
        host.maskChanged(clip);
        refresh();
        host.requestRender();
    }

    /** The mask and its keyframes at one moment (copies). */
    private static final class Snapshot {
        final ClipMask mask;
        final List<ClipMask.Key> keys;
        final String fingerprint;

        private Snapshot(ClipMask mask, List<ClipMask.Key> keys) {
            this.mask = mask;
            this.keys = keys;
            fingerprint = (mask == null ? "-" : ClipMask.toJson(mask).toString()) + "|"
                    + (keys == null || keys.isEmpty() ? "-" : ClipMask.keysToJson(keys).toString());
        }

        static Snapshot of(EditingActivity.Clip clip) {
            return new Snapshot(clip.mask == null ? null : new ClipMask(clip.mask), ClipMask.copyKeys(clip.maskKeys));
        }

        void applyTo(EditingActivity.Clip clip) {
            clip.mask = mask == null ? null : new ClipMask(mask);
            clip.maskKeys = ClipMask.copyKeys(keys);
        }
    }

    private static final class MaskCommand implements CommandUtils.Command {
        private final EditingActivity.Clip clip;
        private final Snapshot before, after;
        private final Host host;

        MaskCommand(EditingActivity.Clip clip, Snapshot before, Snapshot after, Host host) {
            this.clip = clip;
            this.before = before;
            this.after = after;
            this.host = host;
        }

        @Override
        public void execute() {
            after.applyTo(clip);
            host.maskChanged(clip);
            host.requestRender();
        }

        @Override
        public void undo() {
            before.applyTo(clip);
            host.maskChanged(clip);
            host.requestRender();
        }
    }
}
