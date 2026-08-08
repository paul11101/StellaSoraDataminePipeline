package io.stellasora.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.game.PlayerState;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class JsonPlayerRepository implements PlayerRepository {
    private final Path path;
    private final ObjectMapper mapper;
    private final PlayerState state;

    private JsonPlayerRepository(Path path, ObjectMapper mapper, PlayerState state) {
        this.path = path;
        this.mapper = mapper;
        this.state = state;
    }

    public static JsonPlayerRepository load(
            Path path, ObjectMapper mapper, GameDataIndex dataIndex) throws IOException {
        PlayerState state;
        if (Files.isRegularFile(path)) {
            state = mapper.readValue(path.toFile(), PlayerState.class);
            state.normalize();
        } else {
            state = PlayerState.createDefault(dataIndex);
        }
        JsonPlayerRepository repository = new JsonPlayerRepository(path, mapper, state);
        if (!Files.isRegularFile(path)) {
            repository.save();
        }
        return repository;
    }

    @Override
    public PlayerState state() {
        return state;
    }

    @Override
    public synchronized void save() throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
        try {
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
