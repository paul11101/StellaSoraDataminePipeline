package io.stellasora.server.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MessageCatalog {
    private final Map<Integer, Entry> byId;
    private final Map<String, Entry> byName;
    private final Map<String, Entry> successByBaseName;

    private MessageCatalog(Collection<Entry> entries) throws ProtocolException {
        Map<Integer, Entry> ids = new LinkedHashMap<>();
        Map<String, Entry> names = new LinkedHashMap<>();
        Map<String, Entry> successes = new HashMap<>();
        for (Entry entry : entries) {
            if (ids.put(entry.id(), entry) != null) {
                throw new ProtocolException("duplicate message ID: " + entry.id());
            }
            if (names.put(entry.name(), entry) != null) {
                throw new ProtocolException("duplicate message name: " + entry.name());
            }
            if ("success".equals(entry.role())) {
                successes.put(entry.baseName(), entry);
            }
        }
        byId = Map.copyOf(ids);
        byName = Map.copyOf(names);
        successByBaseName = Map.copyOf(successes);
    }

    public static MessageCatalog load(Path path, ObjectMapper mapper) throws ProtocolException {
        try {
            JsonNode root = mapper.readTree(path.toFile());
            JsonNode messages = root.path("messages");
            if (!messages.isArray()) {
                throw new ProtocolException("message catalog has no messages array: " + path);
            }
            List<Entry> entries = new ArrayList<>();
            for (JsonNode row : messages) {
                entries.add(new Entry(
                        row.path("id").asInt(),
                        requiredText(row, "name"),
                        requiredText(row, "base_name"),
                        requiredText(row, "category"),
                        requiredText(row, "direction"),
                        requiredText(row, "role"),
                        requiredText(row, "protobuf_type")));
            }
            return new MessageCatalog(entries);
        } catch (IOException exception) {
            throw new ProtocolException("unable to read message catalog: " + path, exception);
        }
    }

    public Optional<Entry> find(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Entry> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Optional<Entry> successFor(Entry request) {
        if (!"request".equals(request.role())) {
            return Optional.empty();
        }
        return Optional.ofNullable(successByBaseName.get(request.baseName()));
    }

    public Entry require(int id) throws ProtocolException {
        Entry entry = byId.get(id);
        if (entry == null) {
            throw new ProtocolException("unknown message ID: " + id);
        }
        return entry;
    }

    public int size() {
        return byId.size();
    }

    public List<Entry> entries() {
        return byId.values().stream().sorted(Comparator.comparingInt(Entry::id)).toList();
    }

    private static String requiredText(JsonNode node, String field) throws ProtocolException {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new ProtocolException("message catalog row is missing " + field + ": " + node);
        }
        return value;
    }

    public record Entry(
            int id,
            String name,
            String baseName,
            String category,
            String direction,
            String role,
            String protobufType) {}
}
