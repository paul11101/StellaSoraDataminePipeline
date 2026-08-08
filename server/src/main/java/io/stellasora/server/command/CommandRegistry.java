package io.stellasora.server.command;

import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.persistence.PlayerRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class CommandRegistry {
    private final Map<String, RegisteredCommand> commands = new TreeMap<>();
    private final Map<String, RegisteredCommand> aliases = new TreeMap<>();
    private final CommandContext context;

    public CommandRegistry(GameDataIndex gameData, PlayerRepository repository) {
        this.context = new CommandContext(repository.state(), gameData, repository);
        register(new HelpCommand(this));
        register(new ListCommand());
        register(new GiveCommand());
        register(new StoryCommand());
        register(new SetCommand());
        register(new StateCommand());
    }

    public void register(CommandHandler handler) {
        GameCommand metadata = handler.getClass().getAnnotation(GameCommand.class);
        if (metadata == null) {
            throw new IllegalArgumentException(
                    "command handler has no @GameCommand: " + handler.getClass().getName());
        }
        String name = normalize(metadata.name());
        RegisteredCommand registered = new RegisteredCommand(metadata, handler);
        if (commands.put(name, registered) != null) {
            throw new IllegalStateException("duplicate command: " + name);
        }
        for (String alias : metadata.aliases()) {
            String normalized = normalize(alias);
            if (aliases.put(normalized, registered) != null || commands.containsKey(normalized)) {
                throw new IllegalStateException("duplicate command alias: " + normalized);
            }
        }
    }

    public CommandResult invoke(String rawCommand) {
        List<String> tokens;
        try {
            tokens = tokenize(rawCommand);
        } catch (IllegalArgumentException exception) {
            return CommandResult.error(exception.getMessage());
        }
        if (tokens.isEmpty()) {
            return CommandResult.error("command is empty; use 'help'");
        }
        String label = normalize(tokens.removeFirst());
        RegisteredCommand command = commands.get(label);
        if (command == null) {
            command = aliases.get(label);
        }
        if (command == null) {
            return CommandResult.error("unknown command: " + label);
        }
        long revisionBefore = context.player().revision;
        try {
            CommandResult result = command.handler().execute(context, List.copyOf(tokens));
            if (result.success() && context.player().revision != revisionBefore) {
                context.repository().save();
            }
            return result;
        } catch (IllegalArgumentException exception) {
            return CommandResult.error(
                    exception.getMessage() + "; usage: " + command.metadata().usage());
        } catch (Exception exception) {
            return CommandResult.error(
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RegisteredCommand command : commands.values()) {
            GameCommand metadata = command.metadata();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", metadata.name());
            row.put("aliases", List.of(metadata.aliases()));
            row.put("usage", metadata.usage());
            row.put("description", metadata.description());
            result.add(row);
        }
        return result;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaping = false;
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if (escaping) {
                current.append(value);
                escaping = false;
            } else if (value == '\\') {
                escaping = true;
            } else if (value == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(value) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(value);
            }
        }
        if (escaping || quoted) {
            throw new IllegalArgumentException("unterminated escape or quote");
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private record RegisteredCommand(GameCommand metadata, CommandHandler handler) {}

    @GameCommand(
            name = "help",
            aliases = {"?"},
            usage = "help",
            description = "列出本地服命令")
    private static final class HelpCommand implements CommandHandler {
        private final CommandRegistry registry;

        private HelpCommand(CommandRegistry registry) {
            this.registry = registry;
        }

        @Override
        public CommandResult execute(CommandContext context, List<String> arguments) {
            return CommandResult.ok("available commands", registry.describe());
        }
    }

    @GameCommand(
            name = "list",
            aliases = {"find"},
            usage = "list <character|item|story> [query]",
            description = "按 ID 或本地化名称查资料")
    private static final class ListCommand implements CommandHandler {
        @Override
        public CommandResult execute(CommandContext context, List<String> arguments) {
            require(arguments, 1);
            String kind = normalize(arguments.getFirst());
            String query = String.join(" ", arguments.subList(1, arguments.size()));
            List<GameDataIndex.Entry> result = switch (kind) {
                case "character", "char" -> context.gameData().searchCharacters(query, 50);
                case "item" -> context.gameData().searchItems(query, 50);
                case "story" -> context.gameData().searchStories(query, 50);
                default -> throw new IllegalArgumentException("unknown catalog kind: " + kind);
            };
            return CommandResult.ok("matches=" + result.size(), result);
        }
    }

    @GameCommand(
            name = "give",
            aliases = {"grant"},
            usage = "give <character ID [level]|all-characters [level]|item ID quantity>",
            description = "获取角色或物品")
    private static final class GiveCommand implements CommandHandler {
        @Override
        public CommandResult execute(CommandContext context, List<String> arguments) {
            require(arguments, 1);
            String kind = normalize(arguments.getFirst());
            return switch (kind) {
                case "character", "char" -> giveCharacter(context, arguments);
                case "all-characters", "all-chars" -> giveAllCharacters(context, arguments);
                case "item" -> giveItem(context, arguments);
                default -> throw new IllegalArgumentException("unknown give target: " + kind);
            };
        }

        private CommandResult giveCharacter(CommandContext context, List<String> arguments) {
            require(arguments, 2);
            int id = integer(arguments.get(1), "character ID");
            int level = arguments.size() >= 3 ? integer(arguments.get(2), "level") : 1;
            GameDataIndex.Entry entry = context.gameData().character(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown character ID: " + id));
            context.player().grantCharacter(id, level);
            return CommandResult.ok("granted character " + id + " " + entry.name());
        }

        private CommandResult giveAllCharacters(CommandContext context, List<String> arguments) {
            int level = arguments.size() >= 2 ? integer(arguments.get(1), "level") : 1;
            int count = 0;
            for (GameDataIndex.Entry entry : context.gameData().characters()) {
                context.player().grantCharacter(entry.id(), level);
                count++;
            }
            return CommandResult.ok("granted all characters: " + count);
        }

        private CommandResult giveItem(CommandContext context, List<String> arguments) {
            require(arguments, 3);
            int id = integer(arguments.get(1), "item ID");
            int quantity = integer(arguments.get(2), "quantity");
            if (quantity == 0) {
                throw new IllegalArgumentException("quantity must not be zero");
            }
            GameDataIndex.Entry entry = context.gameData().item(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown item ID: " + id));
            context.player().grantItem(id, quantity, context.gameData().isResourceItem(id));
            return CommandResult.ok(
                    "granted item " + id + " x" + quantity + " " + entry.name());
        }
    }

    @GameCommand(
            name = "story",
            usage = "story complete <story ID>",
            description = "修改主线剧情进度")
    private static final class StoryCommand implements CommandHandler {
        @Override
        public CommandResult execute(CommandContext context, List<String> arguments) {
            require(arguments, 2);
            if (!"complete".equals(normalize(arguments.getFirst()))) {
                throw new IllegalArgumentException("expected 'complete'");
            }
            int id = integer(arguments.get(1), "story ID");
            GameDataIndex.Entry entry = context.gameData().story(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown story ID: " + id));
            context.player().completeStory(id);
            return CommandResult.ok("completed story " + id + " " + entry.name());
        }
    }

    @GameCommand(
            name = "set",
            usage = "set world-class <level>",
            description = "修改本地玩家属性")
    private static final class SetCommand implements CommandHandler {
        @Override
        public CommandResult execute(CommandContext context, List<String> arguments) {
            require(arguments, 2);
            if (!"world-class".equals(normalize(arguments.getFirst()))) {
                throw new IllegalArgumentException("expected 'world-class'");
            }
            int value = integer(arguments.get(1), "world class");
            context.player().changeWorldClass(value);
            return CommandResult.ok("world class=" + context.player().worldClass);
        }
    }

    @GameCommand(
            name = "state",
            usage = "state",
            description = "查看本地玩家存档")
    private static final class StateCommand implements CommandHandler {
        @Override
        public CommandResult execute(CommandContext context, List<String> arguments) {
            return CommandResult.ok("player state", context.player());
        }
    }

    private static void require(List<String> arguments, int count) {
        if (arguments.size() < count) {
            throw new IllegalArgumentException("not enough arguments");
        }
    }

    private static int integer(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " is not an integer: " + value, exception);
        }
    }
}
