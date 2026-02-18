package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Stack;

public class CommandManager {
    private final int MAX_HISTORY = 100;
    private Deque<CommandUtils.Command> undoStack = new ArrayDeque<>();
    private Deque<CommandUtils.Command> redoStack = new ArrayDeque<>();
    private StateChangeListener stateChangeListener = null;
    private CommandActionListener commandActionListener = null;

    /**
     * Interface for listening to undo/redo state changes.
     * Used to update UI button states.
     */
    public interface StateChangeListener {
        void onStateChanged(boolean canUndo, boolean canRedo);
    }

    /**
     * Interface for listening to command actions (execute, undo, redo).
     * Used to display toast notifications.
     */
    public interface CommandActionListener {
        void onCommandExecuted(CommandUtils.Command command);
        void onCommandUndone(CommandUtils.Command command);
        void onCommandRedone(CommandUtils.Command command);
    }

    /**
     * Set listener for command actions
     */
    public void setCommandActionListener(CommandActionListener listener) {
        this.commandActionListener = listener;
    }

    /**
     * Set listener for state changes
     */
    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    /**
     * Execute a command and add it to the undo stack.
     * Clears the redo stack as new actions invalidate redo history.
     */
    public void executeCommand(CommandUtils.Command cmd) {
        cmd.execute();
        undoStack.push(cmd);
        redoStack.clear();

        if (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast(); // prune oldest
        }

        if (commandActionListener != null) {
            commandActionListener.onCommandExecuted(cmd);
        }
        notifyStateChanged();
    }

    
    public boolean isUndoAvailable() { return !undoStack.isEmpty(); }
    
    /**
     * Undo the last command
     */
    public void undo() {
        if (isUndoAvailable()) {
            CommandUtils.Command cmd = undoStack.pop();
            cmd.undo();
            redoStack.push(cmd);
            if (commandActionListener != null) {
                commandActionListener.onCommandUndone(cmd);
            }
            notifyStateChanged();
        }
    }

    public boolean isRedoAvailable() { return !redoStack.isEmpty(); }
    
    /**
     * Redo the last undone command
     */
    public void redo() {
        if (isRedoAvailable()) {
            CommandUtils.Command cmd = redoStack.pop();
            cmd.execute();
            undoStack.push(cmd);
            if (commandActionListener != null) {
                commandActionListener.onCommandRedone(cmd);
            }
            notifyStateChanged();
        }
    }

    /**
     * Clear all command history
     */
    public void clearHistory() {
        undoStack.clear();
        redoStack.clear();
        notifyStateChanged();
    }

    /**
     * Get the number of commands in undo stack
     */
    public int getUndoCount() {
        return undoStack.size();
    }

    /**
     * Get the number of commands in redo stack
     */
    public int getRedoCount() {
        return redoStack.size();
    }

    /**
     * Notify listener of state changes
     */
    private void notifyStateChanged() {
        if (stateChangeListener != null) {
            stateChangeListener.onStateChanged(isUndoAvailable(), isRedoAvailable());
        }
    }
}

