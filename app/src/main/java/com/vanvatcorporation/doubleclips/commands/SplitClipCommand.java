package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

/**
 * Command to split a clip at a specific time.
 * Uses BatchCommand internally to group the trim and add operations.
 */
public class SplitClipCommand implements CommandUtils.Command {
    private EditingActivity activity;
    private EditingActivity.Clip originalClip;
    private EditingActivity.Clip secondaryClip;
    private EditingActivity.Track track;
    private float splitTime;
    
    // Original state before split
    private float originalEndClipTrim;
    private float originalStartClipTrim;
    private float originalDuration;
    
    private boolean wasSplit = false;

    public SplitClipCommand(EditingActivity activity, EditingActivity.Clip clip, float globalSplitTime) {
        this.activity = activity;
        this.originalClip = clip;
        this.splitTime = globalSplitTime;
        this.track = activity.timeline.tracks.get(clip.trackIndex);
        
        // Capture original state
        this.originalEndClipTrim = clip.endClipTrim;
        this.originalStartClipTrim = clip.startClipTrim;
        this.originalDuration = clip.duration;
    }

    @Override
    public void execute() {
        if (!wasSplit) {
            float translatedLocalCurrentTime = originalClip.getLocalClipTime(splitTime);
            
            // Create secondary clip (right side of split)
            secondaryClip = new EditingActivity.Clip(originalClip);
            secondaryClip.startTime = splitTime;
            
            float oldStartClipTrim = originalClip.startClipTrim;
            
            // A reversed video plays its used range backwards: the LEFT part is the source's later half (it keeps the end trim and now
            // starts at the cut), the right part plays what lies before the cut.
            if (originalClip.playsBackwards()) {
                float cutSource = (originalClip.originalDuration - originalEndClipTrim) - translatedLocalCurrentTime;
                originalClip.startClipTrim = cutSource;
                originalClip.setDuration(originalClip.originalDuration - originalClip.endClipTrim - originalClip.startClipTrim);

                secondaryClip.startClipTrim = oldStartClipTrim;
                secondaryClip.endClipTrim = originalClip.originalDuration - cutSource;
            } else {
                // Modify primary clip (left side of split)
                originalClip.endClipTrim = originalClip.originalDuration - (translatedLocalCurrentTime + oldStartClipTrim);
                originalClip.setDuration(originalClip.originalDuration - originalClip.endClipTrim - originalClip.startClipTrim);

                // Modify secondary clip
                secondaryClip.startClipTrim = translatedLocalCurrentTime + oldStartClipTrim;
                secondaryClip.endClipTrim = originalEndClipTrim;
            }
            secondaryClip.setDuration(secondaryClip.originalDuration - secondaryClip.endClipTrim - secondaryClip.startClipTrim);
            
            // Add secondary clip to track
            track.addClip(secondaryClip);
            activity.addClipToTrackUi(track.viewRef, secondaryClip);
            
            // Revalidate original clip view
            activity.revalidationClipView(originalClip);
            
            wasSplit = true;
        }
    }

    @Override
    public void undo() {
        if (wasSplit) {
            // Remove secondary clip
            track.removeClip(secondaryClip);
            if (track.viewRef != null && secondaryClip.viewRef != null) {
                track.viewRef.removeView(secondaryClip.viewRef);
            }
            
            // Restore original clip state
            originalClip.endClipTrim = originalEndClipTrim;
            originalClip.startClipTrim = originalStartClipTrim;
            originalClip.setDuration(originalDuration);
            
            // Revalidate original clip view
            activity.revalidationClipView(originalClip);
            
            wasSplit = false;
        }
    }

    @Override
    public String toString() {
        return "Split Clip: " + originalClip.getClipName();
    }
}
