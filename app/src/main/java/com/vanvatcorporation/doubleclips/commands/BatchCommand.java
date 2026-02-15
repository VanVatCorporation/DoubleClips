package com.vanvatcorporation.doubleclips.commands;

import com.vanvatcorporation.doubleclips.commands.base.CommandUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Command that groups multiple commands together.
 * Useful for complex operations like splitting clips or deleting tracks.
 */
public class BatchCommand implements CommandUtils.Command {
    private List<CommandUtils.Command> commands;
    private String description;

    public BatchCommand(String description) {
        this.description = description;
        this.commands = new ArrayList<>();
    }

    /**
     * Add a command to this batch
     */
    public void addCommand(CommandUtils.Command cmd) {
        commands.add(cmd);
    }

    @Override
    public void execute() {
        // Execute all commands in order
        for (CommandUtils.Command cmd : commands) {
            cmd.execute();
        }
    }

    @Override
    public void undo() {
        // Undo all commands in reverse order
        for (int i = commands.size() - 1; i >= 0; i--) {
            commands.get(i).undo();
        }
    }

    @Override
    public String toString() {
        return description + " (" + commands.size() + " operations)";
    }

    public int getCommandCount() {
        return commands.size();
    }
}
