package com.vanvatcorporation.doubleclips.activities.editing;

import android.view.View;

import com.vanvatcorporation.doubleclips.ClipMotionBlur;
import com.vanvatcorporation.doubleclips.ClipOpticalFlow;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.VideoProperties.ValueType;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The Motion panel's logic (see MotionEditSpecificAreaScreen): optical flow and motion blur on the selected VIDEO clip.
 * <ul>
 *   <li>The two switches, the optical flow frame rate, the blur Direction and Speed (times) are plain values of the clip.</li>
 *   <li>Blur and Blend are the clip's {@code valueMotionBlur} / {@code valueMotionBlend}: plain until one of their diamonds is
 *       set; from then on they are keyframed (the clip's own keyframes, channels "motionBlur" / "motionBlend") and dragging one
 *       writes the keyframe at the playhead. Taking the last keyframe off leaves the value where it was.</li>
 *   <li>Every gesture (a drag, a typed number, a switch, a diamond) is ONE undo step that puts back the clip's two switches and
 *       settings, its plain slider values and its keyframes.</li>
 * </ul>
 * Same behaviour as the iOS MotionPanel.
 */
public class MotionEditController {

    public interface Host {
        EditingActivity.Clip selectedClip();
        float currentTime();
        int frameRate();
        /** Easing of a new keyframe. */
        EditingActivity.EasingType easing();
        void requestRender();
        void execute(CommandUtils.Command command);
        /** The motion settings or their keyframes changed (a gesture ended, undo, redo): the timeline's diamonds follow. */
        void motionChanged(EditingActivity.Clip clip);
        void message(int stringRes);
    }

    private static final ValueType[] SLIDERS = {ValueType.MotionBlur, ValueType.MotionBlend};

    private final MotionEditSpecificAreaScreen panel;
    private final Host host;
    private Snapshot gestureBefore;

    public MotionEditController(MotionEditSpecificAreaScreen panel, Host host) {
        this.panel = panel;
        this.host = host;
        panel.onOpen.add(this::refresh);
        panel.setListener(new MotionEditSpecificAreaScreen.Listener() {
            @Override public void onOpticalFlow(boolean on) { setOpticalFlow(on); }
            @Override public void onFps(float fps) { setFps(fps); }
            @Override public void onMotionBlur(boolean on) { setMotionBlur(on); }
            @Override public void onDirection(ClipMotionBlur.Direction direction) { setDirection(direction); }
            @Override public void onPasses(int passes) { setPasses(passes); }
            @Override public void onValueStart() { begin(); }
            @Override public void onValueChanging(int param, float value) { liveValue(param, value); }
            @Override public void onValueCommit(int param, float value) { commitValue(param, value); }
            @Override public void onDiamond(int param) { toggleKeyframe(param); }
        });
    }

    /** Motion works on video clips (it needs frames to follow). */
    public static boolean supports(EditingActivity.Clip clip) {
        return clip != null && clip.type == EditingActivity.ClipType.VIDEO;
    }

    public boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    /** The toolbar's Motion button. */
    public void open() {
        EditingActivity.Clip clip = host.selectedClip();
        if (clip == null) {
            host.message(R.string.you_need_to_pick_a_clip_first);
            return;
        }
        if (!supports(clip)) {
            host.message(R.string.motion_video_only);
            return;
        }
        panel.open();
    }

    /** Another clip was picked: the panel follows, or closes when the new one is not a video clip. */
    public void onSelectionChanged() {
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
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;

        MotionEditSpecificAreaScreen.State s = new MotionEditSpecificAreaScreen.State();
        s.projectFps = host.frameRate();
        s.flowOn = clip.hasOpticalFlow();
        s.fps = clip.opticalFlow != null ? clip.opticalFlow.effectiveFps() : ClipOpticalFlow.DEFAULT_FPS;
        s.exceedsProject = clip.opticalFlow != null && clip.opticalFlow.exceedsProject(host.frameRate());
        s.blurOn = clip.hasMotionBlur();
        s.direction = clip.motionBlur != null ? clip.motionBlur.direction() : ClipMotionBlur.Direction.BOTH;
        s.passes = clip.motionBlur != null ? clip.motionBlur.effectivePasses() : 1;
        float local = localTime(clip);
        for (int p = 0; p < 2; p++) {
            ValueType type = SLIDERS[p];
            s.values[p] = Math.round(valueAt(clip, type, clip.startTime + local));
            s.modes[p] = clip.keyframes == null || !clip.keyframes.isAnimated(type) ? ClipEditSpecificAreaScreen.KeyMode.OFF
                    : (clip.keyframes.keyframeIndex(local, type) >= 0 ? ClipEditSpecificAreaScreen.KeyMode.ON_KEY
                    : ClipEditSpecificAreaScreen.KeyMode.ANIMATED);
        }
        s.diamondsEnabled = insideClip(clip);
        panel.show(s);
    }

    // ---- panel events ------------------------------------------------------------------------------

    private void setOpticalFlow(boolean on) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.hasOpticalFlow() == on) return;
        gestureBefore = Snapshot.of(clip);
        if (clip.opticalFlow == null) clip.opticalFlow = new ClipOpticalFlow();
        clip.opticalFlow.enabled = on;
        finish(clip);
    }

    /** {@code fps} is NaN when the typed text was not a usable rate: the box then shows what is kept. */
    private void setFps(float fps) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.opticalFlow == null || Float.isNaN(fps)
                || Math.abs(fps - clip.opticalFlow.effectiveFps()) <= 1e-4f) {
            refresh();
            return;
        }
        gestureBefore = Snapshot.of(clip);
        clip.opticalFlow.fps = fps;
        finish(clip);
    }

    private void setMotionBlur(boolean on) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.hasMotionBlur() == on) return;
        gestureBefore = Snapshot.of(clip);
        if (clip.motionBlur == null) clip.motionBlur = new ClipMotionBlur();
        clip.motionBlur.enabled = on;
        finish(clip);
    }

    private void setDirection(ClipMotionBlur.Direction direction) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.motionBlur == null || clip.motionBlur.direction() == direction) return;
        gestureBefore = Snapshot.of(clip);
        clip.motionBlur.setDirection(direction);
        finish(clip);
    }

    /** {@code passes} is -1 when the typed text was not a whole number from 1 up: the box then shows what is kept. */
    private void setPasses(int passes) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip) || clip.motionBlur == null || passes < 1 || passes == clip.motionBlur.effectivePasses()) {
            refresh();
            return;
        }
        gestureBefore = Snapshot.of(clip);
        clip.motionBlur.passes = passes;
        finish(clip);
    }

    private void begin() {
        EditingActivity.Clip clip = host.selectedClip();
        gestureBefore = supports(clip) ? Snapshot.of(clip) : null;
    }

    private void liveValue(int param, float value) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        applyValue(clip, param, value);
        host.requestRender();
    }

    private void commitValue(int param, float value) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        if (gestureBefore == null) begin();
        applyValue(clip, param, value);
        finish(clip);
    }

    /** The diamond: a keyframe of this slider at the playhead is taken off, or one is put there with the value it has now. */
    private void toggleKeyframe(int param) {
        EditingActivity.Clip clip = host.selectedClip();
        if (!supports(clip)) return;
        if (!insideClip(clip)) {
            host.message(R.string.motion_playhead_outside);
            return;
        }
        gestureBefore = Snapshot.of(clip);
        ValueType type = SLIDERS[param];
        float local = snappedLocal(clip);
        if (clip.keyframes.keyframeIndex(local, type) >= 0) {
            Float removed = clip.keyframes.removeKey(type, local);
            // No longer animated: the value stays where it was.
            if (removed != null && !clip.keyframes.isAnimated(type)) clip.videoProperties.setValue(removed, type);
        } else {
            float now = valueAt(clip, type, clip.startTime + local);
            clip.keyframes.setKey(type, local, now, clip, host.frameRate(), host.easing());
        }
        finish(clip);
    }

    // ---- the edit itself ---------------------------------------------------------------------------

    private void applyValue(EditingActivity.Clip clip, int param, float value) {
        float v = Math.max(0f, Math.min(ClipMotionBlur.SCALE_MAX, Float.isNaN(value) ? 0f : Math.round(value)));
        ValueType type = SLIDERS[param];
        if (clip.keyframes != null && clip.keyframes.isAnimated(type)) {
            if (!insideClip(clip)) return; // a keyframe can't be placed outside the clip
            clip.keyframes.setKey(type, snappedLocal(clip), v, clip, host.frameRate(), host.easing());
        } else {
            clip.videoProperties.setValue(v, type);
        }
    }

    /** A slider at a global time: the keyframed value when some keyframe holds it, else the clip's own. */
    static float valueAt(EditingActivity.Clip clip, ValueType type, float timeSeconds) {
        if (clip.keyframes != null && clip.keyframes.isAnimated(type)) return clip.keyframes.getValueAtTime(clip, timeSeconds, type);
        return clip.videoProperties.getValue(type);
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
            host.execute(new MotionCommand(clip, before, after, host));
        }
        host.motionChanged(clip);
        refresh();
        host.requestRender();
    }

    /**
     * What one undo step puts back: the clip's two switches and settings, its plain Blur / Blend values and its keyframes
     * (copies; a slider's keyframes live in the clip's own).
     */
    private static final class Snapshot {
        final ClipOpticalFlow flow;
        final ClipMotionBlur blur;
        final float plainBlur, plainBlend;
        final List<EditingActivity.Keyframe> keyframes;
        final String fingerprint;

        private Snapshot(ClipOpticalFlow flow, ClipMotionBlur blur, float plainBlur, float plainBlend,
                         List<EditingActivity.Keyframe> keyframes) {
            this.flow = flow;
            this.blur = blur;
            this.plainBlur = plainBlur;
            this.plainBlend = plainBlend;
            this.keyframes = keyframes;
            StringBuilder sb = new StringBuilder();
            sb.append(flow == null ? "-" : flow.enabled + "|" + flow.fps).append('#');
            sb.append(blur == null ? "-" : blur.enabled + "|" + blur.direction + "|" + blur.passes).append('#');
            sb.append(plainBlur).append('|').append(plainBlend).append('#');
            if (keyframes != null) {
                for (EditingActivity.Keyframe k : keyframes) {
                    sb.append(k.getLocalTime()).append('|').append(k.channels).append('|').append(k.easing).append('|');
                    sb.append(k.value == null ? "-" : k.value.valueMotionBlur + "," + k.value.valueMotionBlend).append(';');
                }
            }
            fingerprint = sb.toString();
        }

        static Snapshot of(EditingActivity.Clip clip) {
            List<EditingActivity.Keyframe> copy = new ArrayList<>();
            if (clip.keyframes != null && clip.keyframes.keyframes != null) {
                for (EditingActivity.Keyframe k : clip.keyframes.keyframes) copy.add(k.copy());
            }
            return new Snapshot(clip.opticalFlow == null ? null : new ClipOpticalFlow(clip.opticalFlow),
                    clip.motionBlur == null ? null : new ClipMotionBlur(clip.motionBlur),
                    clip.videoProperties.valueMotionBlur, clip.videoProperties.valueMotionBlend, copy);
        }

        void applyTo(EditingActivity.Clip clip) {
            clip.opticalFlow = flow == null ? null : new ClipOpticalFlow(flow);
            clip.motionBlur = blur == null ? null : new ClipMotionBlur(blur);
            clip.videoProperties.valueMotionBlur = plainBlur;
            clip.videoProperties.valueMotionBlend = plainBlend;
            if (clip.keyframes != null) {
                List<EditingActivity.Keyframe> list = new ArrayList<>();
                for (EditingActivity.Keyframe k : keyframes) list.add(k.copy());
                clip.keyframes.keyframes = list;
            }
        }
    }

    private static final class MotionCommand implements CommandUtils.Command {
        private final EditingActivity.Clip clip;
        private final Snapshot before, after;
        private final Host host;

        MotionCommand(EditingActivity.Clip clip, Snapshot before, Snapshot after, Host host) {
            this.clip = clip;
            this.before = before;
            this.after = after;
            this.host = host;
        }

        @Override
        public void execute() {
            after.applyTo(clip);
            host.motionChanged(clip);
            host.requestRender();
        }

        @Override
        public void undo() {
            before.applyTo(clip);
            host.motionChanged(clip);
            host.requestRender();
        }
    }
}
