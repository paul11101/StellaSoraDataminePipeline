package io.stellasora.server.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class GameDataIndex {
    private final Map<Integer, Entry> characters;
    private final Map<Integer, Entry> items;
    private final Map<Integer, Entry> stories;

    private GameDataIndex(
            Map<Integer, Entry> characters,
            Map<Integer, Entry> items,
            Map<Integer, Entry> stories) {
        this.characters = Map.copyOf(characters);
        this.items = Map.copyOf(items);
        this.stories = Map.copyOf(stories);
    }

    public static GameDataIndex load(Path dataRoot, ObjectMapper mapper) throws IOException {
        return new GameDataIndex(
                loadTable(dataRoot.resolve("Character.json"), mapper, "/Name"),
                loadTable(dataRoot.resolve("Item.json"), mapper, "/Title"),
                loadTable(dataRoot.resolve("Story.json"), mapper, "/Title"));
    }

    public Optional<Entry> character(int id) {
        return Optional.ofNullable(characters.get(id));
    }

    public Optional<Entry> item(int id) {
        return Optional.ofNullable(items.get(id));
    }

    public Optional<Entry> story(int id) {
        return Optional.ofNullable(stories.get(id));
    }

    public Collection<Entry> characters() {
        return characters.values();
    }

    public List<Entry> searchItems(String query, int limit) {
        return search(items.values(), query, limit);
    }

    public List<Entry> searchCharacters(String query, int limit) {
        return search(characters.values(), query, limit);
    }

    public List<Entry> searchStories(String query, int limit) {
        return search(stories.values(), query, limit);
    }

    public boolean isResourceItem(int id) {
        Entry entry = items.get(id);
        return entry != null && entry.type() == 1;
    }

    public Map<String, Integer> counts() {
        return Map.of(
                "characters", characters.size(),
                "items", items.size(),
                "stories", stories.size());
    }

    private static Map<Integer, Entry> loadTable(
            Path path, ObjectMapper mapper, String localizedField) throws IOException {
        JsonNode root = mapper.readTree(path.toFile()).path("data");
        if (!root.isObject()) {
            throw new IOException("readable data table has no object data field: " + path);
        }
        Map<Integer, Entry> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : root.properties()) {
            JsonNode row = property.getValue();
            int id = row.path("Id").asInt(Integer.parseInt(property.getKey()));
            JsonNode localized = row.path("_localized").path(localizedField);
            String name = localized.path("zh_TW").asText();
            if (name.isBlank()) {
                name = localized.path("zh_CN").asText();
            }
            if (name.isBlank()) {
                name = row.path(localizedField.equals("/Title") ? "Title" : "Name").asText();
            }
            Entry entry = new Entry(id, name, row.path("Type").asInt(), row.path("Stype").asInt());
            result.put(id, entry);
        }
        return result;
    }

    private static List<Entry> search(Collection<Entry> source, String query, int limit) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Entry> result = new ArrayList<>();
        for (Entry entry : source) {
            if (normalized.isEmpty()
                    || Integer.toString(entry.id()).contains(normalized)
                    || entry.name().toLowerCase(Locale.ROOT).contains(normalized)) {
                result.add(entry);
            }
        }
        return result.stream()
                .sorted(Comparator.comparingInt(Entry::id))
                .limit(Math.max(0, limit))
                .toList();
    }

    public record Entry(int id, String name, int type, int subtype) {}
}
