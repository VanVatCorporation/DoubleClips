package com.vanvatcorporation.doubleclips.commands.base;

import java.lang.reflect.Field;
import java.util.List;

public class CommandUtils {



    public interface Command {
        void execute();
        void undo();
        
        /**
         * Return a human-readable description of this command.
         * Useful for debugging and potential undo history UI.
         */
//        default String toString() {
//            return "Command";
//        }
    }



    public static class GenericCommand implements Command {
        private Runnable undoRunnable;
        private Runnable redoRunnable;
        public GenericCommand(Runnable undoRunnable, Runnable redoRunnable) {
            this.undoRunnable = undoRunnable;
            this.redoRunnable = redoRunnable;
        }
        public void execute() { redoRunnable.run(); }
        public void undo() { undoRunnable.run(); }
    }

    public static class DeltaCommand<T> implements Command {
        private Object target;
        private String property;
        private T oldValue;
        private T newValue;

        public DeltaCommand(Object target, String property, T oldValue, T newValue) {
            this.target = target;
            this.property = property;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        @Override
        public void execute() {
            setProperty(target, property, newValue);
        }

        @Override
        public void undo() {
            setProperty(target, property, oldValue);
        }

        private void setProperty(Object target, String property, Object value) {
            try {
                Field field = target.getClass().getDeclaredField(property);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set property: " + property, e);
            }
        }
    }



    public class CompositeCommand implements Command {
        private List<Command> commands;
        public CompositeCommand(List<Command> commands) { this.commands = commands; }
        public void execute() { commands.forEach(Command::execute); }
        public void undo() { commands.forEach(Command::undo); }
    }



}
