package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

/**
 * Command to modify a clip property using reflection.
 * Generic command for simple property changes.
 */
public class ClipPropertyCommand<T> implements CommandUtils.Command {
    private EditingActivity activity;
    private EditingActivity.Clip clip;
    private String propertyName;
    private T oldValue;
    private T newValue;
    private PropertySetter<T> setter;
    private boolean wasSet = false;

    /**
     * Interface for setting property values
     */
    public interface PropertySetter<T> {
        void set(EditingActivity.Clip clip, T value);
    }

    public ClipPropertyCommand(EditingActivity activity, EditingActivity.Clip clip, 
                               String propertyName, T oldValue, T newValue, PropertySetter<T> setter) {
        this.activity = activity;
        this.clip = clip;
        this.propertyName = propertyName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.setter = setter;
    }

    @Override
    public void execute() {
        setter.set(clip, newValue);
        
        // Revalidate clip view to reflect changes
        if (clip.viewRef != null) {
            activity.revalidationClipView(clip);
        }
        
        wasSet = true;
    }

    @Override
    public void undo() {
        if (wasSet) {
            setter.set(clip, oldValue);
            
            // Revalidate clip view to reflect changes
            if (clip.viewRef != null) {
                activity.revalidationClipView(clip);
            }
            
            wasSet = false;
        }
    }

    @Override
    public String toString() {
        return "Change " + propertyName + ": " + clip.getClipName();
    }
}
