package com.vanvatcorporation.doubleclips.history;

import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;

public class HistoryManager {
    private final Stack<Command> undoStack = new Stack<>();
    private final Stack<Command> redoStack = new Stack<>();

    private final AtomicBoolean canUndo = new AtomicBoolean(false);
    private final AtomicBoolean canRedo = new AtomicBoolean(false);

    public void execute(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear();
        updateProperties();
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        Command command = undoStack.pop();
        command.undo();
        redoStack.push(command);
        updateProperties();
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        Command command = redoStack.pop();
        command.execute();
        undoStack.push(command);
        updateProperties();
    }

    private void updateProperties() {
        canUndo.set(!undoStack.isEmpty());
        canRedo.set(!redoStack.isEmpty());
    }

    public AtomicBoolean canUndoProperty() { return canUndo; }
    public AtomicBoolean canRedoProperty() { return canRedo; }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        updateProperties();
    }
}
