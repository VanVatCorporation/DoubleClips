package com.vanvatcorporation.doubleclips.history;

public interface Command {
    void execute();
    void undo();
    String getName();
}
