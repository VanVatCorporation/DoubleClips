package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Stack;

public class CommandManager {
    private final int MAX_HISTORY = 100;
    private Deque<CommandUtils.DeltaCommand<?>> undoStack = new ArrayDeque<>();
    private Deque<CommandUtils.DeltaCommand<?>> redoStack = new ArrayDeque<>();

    void executeCommand(CommandUtils.DeltaCommand<?> cmd) {
        cmd.execute();
        undoStack.push(cmd);
        redoStack.clear();

        if (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast(); // prune oldest
        }
    }


    public boolean isUndoAvailable() { return !undoStack.isEmpty(); }
    public void undo() {
        if (isUndoAvailable()) {
            CommandUtils.DeltaCommand<?> cmd = undoStack.pop();
            cmd.undo();
            redoStack.push(cmd);
        }
    }

    public boolean isRedoAvailable() { return !redoStack.isEmpty(); }
    public void redo() {
        if (isRedoAvailable()) {
            CommandUtils.DeltaCommand<?> cmd = redoStack.pop();
            cmd.execute();
            undoStack.push(cmd);
        }
    }
}

