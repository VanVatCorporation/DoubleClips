package com.vanvatcorporation.doubleclips.history;

public class PropertyChangeCommand implements Command {
    private final String name;
    private final Runnable redoAction;
    private final Runnable undoAction;

    public PropertyChangeCommand(String name, Runnable redoAction, Runnable undoAction) {
        this.name = name;
        this.redoAction = redoAction;
        this.undoAction = undoAction;
    }

    @Override
    public void execute() {
        redoAction.run();
    }

    @Override
    public void undo() {
        undoAction.run();
    }

    @Override
    public String getName() {
        return name;
    }
}
