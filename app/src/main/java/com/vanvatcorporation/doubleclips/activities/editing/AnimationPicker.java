package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import com.vanvatcorporation.doubleclips.ClipAnimation;
import com.vanvatcorporation.doubleclips.ClipAnimationAssets;
import com.vanvatcorporation.doubleclips.ClipAnimationLoader;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;

/**
 * One animation row of the clip editor (a type spinner + a duration field) for one direction.
 * The choices come from the ClipAnimationLoader registry - "none" plus every installed
 * animation of this direction - instead of a hardcoded list, and show each animation's
 * display name while storing its id in {@code AnimationClip.type}.
 *
 * Picking a real animation by hand also fills the duration field with that animation's
 * own default (e.g. 1.5 s for unfold); loading a clip into the editor never does.
 */
public final class AnimationPicker {

    /** One spinner entry: stored id + shown label. */
    public static final class Choice {
        public final String id;
        public final String label;

        Choice(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override public String toString() { return label; }
    }

    private final Spinner spinner;
    private final EditText durationField;
    private final ClipAnimation.Direction direction;
    private final ArrayAdapter<Choice> adapter;
    private boolean userIsPicking = false;

    public AnimationPicker(Context context, Spinner spinner, EditText durationField, ClipAnimation.Direction direction) {
        this.spinner = spinner;
        this.durationField = durationField;
        this.direction = direction;
        ClipAnimationAssets.loadAll(context); // built-ins + installed packs; no-op after the first call
        this.adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1);
        rebuildChoices();
        spinner.setAdapter(adapter);

        // Only a finger on the spinner counts as "the user picked this" - programmatic
        // setSelection() calls (loading a clip) also fire onItemSelected and must not
        // overwrite the clip's saved duration.
        spinner.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) userIsPicking = true;
                return false;
            }
        });
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!userIsPicking) return;
                userIsPicking = false;
                Choice c = adapter.getItem(position);
                ClipAnimation a = c == null ? null : ClipAnimationLoader.get(c.id);
                if (a != null) AnimationPicker.this.durationField.setText(String.valueOf(a.getDefaultDuration()));
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void rebuildChoices() {
        adapter.clear();
        adapter.add(new Choice("none", "None"));
        for (ClipAnimation a : ClipAnimationLoader.list(direction)) adapter.add(new Choice(a.getId(), a.getName()));
    }

    private int indexOf(String id) {
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i).id.equals(id)) return i;
        }
        return -1;
    }

    /** Re-reads the registry (after a pack was imported or removed) and keeps the current selection. */
    public void refresh() {
        Choice current = (Choice) spinner.getSelectedItem();
        String id = current == null ? "none" : current.id;
        userIsPicking = false;
        rebuildChoices();
        int idx = indexOf(id);
        if (idx < 0) {
            adapter.add(new Choice(id, id + " (not installed)"));
            idx = adapter.getCount() - 1;
        }
        spinner.setSelection(idx);
    }

    /** Shows a clip's saved animation. A saved id that isn't installed stays selectable (and is kept on save). */
    public void show(EditingActivity.AnimationClip anim) {
        userIsPicking = false;
        rebuildChoices();
        String type = (anim == null || anim.type == null || anim.type.isEmpty()) ? "none" : anim.type;
        int idx = indexOf(type);
        if (idx < 0) {
            adapter.add(new Choice(type, type + " (not installed)"));
            idx = adapter.getCount() - 1;
        }
        spinner.setSelection(idx);
        durationField.setText(String.valueOf(anim == null ? 0.5f : anim.duration));
    }

    /** Writes the chosen type and the typed duration (kept as before if the field isn't a positive number) into anim. */
    public void applyTo(EditingActivity.AnimationClip anim) {
        Choice c = (Choice) spinner.getSelectedItem();
        if (c != null) anim.type = c.id;
        try {
            float d = Float.parseFloat(durationField.getText().toString().trim());
            if (d > 0f && !Float.isInfinite(d)) anim.duration = d;
        } catch (NumberFormatException ignored) {
            // keep the previous duration
        }
    }
}
