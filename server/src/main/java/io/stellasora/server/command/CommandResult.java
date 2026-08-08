package io.stellasora.server.command;

public record CommandResult(boolean success, String message, Object data) {
    public static CommandResult ok(String message) {
        return new CommandResult(true, message, null);
    }

    public static CommandResult ok(String message, Object data) {
        return new CommandResult(true, message, data);
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, message, null);
    }
}
