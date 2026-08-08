package io.stellasora.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.stellasora.server.TestArtifacts;
import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.persistence.JsonPlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CommandRegistryTest {
    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void validatesIdsMutatesStateAndPersistsCommands() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GameDataIndex data = GameDataIndex.load(TestArtifacts.gameDataRoot(), mapper);
        java.nio.file.Path statePath = temporaryDirectory.resolve("state.json");
        JsonPlayerRepository repository =
                JsonPlayerRepository.load(statePath, mapper, data);
        CommandRegistry commands = new CommandRegistry(data, repository);

        assertTrue(commands.invoke("give character 107 80").success());
        assertEquals(80, repository.state().characters.get(107).level);
        assertTrue(commands.invoke("give item 2 1").success());
        assertEquals(10_000, repository.state().resources.get(2));
        assertTrue(commands.invoke("story complete 100").success());
        assertTrue(repository.state().storyPassed.contains(100));
        assertTrue(commands.invoke("set world-class 10").success());
        assertEquals(10, repository.state().worldClass);
        assertTrue(commands.invoke("list item 2").success());
        assertTrue(java.nio.file.Files.size(statePath) > 0);

        long persistedRevision = repository.state().revision;
        JsonPlayerRepository reloaded =
                JsonPlayerRepository.load(statePath, mapper, data);
        assertEquals(persistedRevision, reloaded.state().revision);
        assertEquals(10, reloaded.state().worldClass);
    }

    @Test
    void rejectsUnknownIdsWithoutChangingState() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GameDataIndex data = GameDataIndex.load(TestArtifacts.gameDataRoot(), mapper);
        JsonPlayerRepository repository = JsonPlayerRepository.load(
                temporaryDirectory.resolve("state.json"), mapper, data);
        CommandRegistry commands = new CommandRegistry(data, repository);
        long revision = repository.state().revision;

        CommandResult result = commands.invoke("give character 999999 80");
        assertTrue(!result.success());
        assertEquals(revision, repository.state().revision);
    }
}
