package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

/**
 * One finished on-canvas gesture (move / scale / rotate) as a single undo step.
 * {@code redo} puts the clip into its final state, {@code undo} back into the state at the
 * start of the gesture. Both are idempotent (they restore a snapshot), so running {@code redo}
 * right after the gesture - when the clip already holds those values - is harmless.
 */
public class TransformGestureCommand implements CommandUtils.Command {
    private final String description;
    private final Runnable redo;
    private final Runnable undo;

    public TransformGestureCommand(String description, Runnable redo, Runnable undo) {
        this.description = description;
        this.redo = redo;
        this.undo = undo;
    }

    @Override
    public void execute() {
        redo.run();
    }

    @Override
    public void undo() {
        undo.run();
    }

    @Override
    public String toString() {
        return description;
    }
}
