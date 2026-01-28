package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Stack;

public class CommandManager {
    private final int MAX_HISTORY = 100;
    private Deque<CommandUtils.Command> undoStack = new ArrayDeque<>();
    private Deque<CommandUtils.Command> redoStack = new ArrayDeque<>();

    public void executeCommand(CommandUtils.Command cmd) {
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
            CommandUtils.Command cmd = undoStack.pop();
            cmd.undo();
            redoStack.push(cmd);
        }
    }

    public boolean isRedoAvailable() { return !redoStack.isEmpty(); }
    public void redo() {
        if (isRedoAvailable()) {
            CommandUtils.Command cmd = redoStack.pop();
            cmd.execute();
            undoStack.push(cmd);
        }
    }
}

