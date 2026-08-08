package io.stellasora.server.command;

import java.util.List;

@FunctionalInterface
public interface CommandHandler {
    CommandResult execute(CommandContext context, List<String> arguments) throws Exception;
}
