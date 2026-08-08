package io.stellasora.server.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.stellasora.server.TestArtifacts;
import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.game.PlayerMessageFactory;
import io.stellasora.server.persistence.JsonPlayerRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtocolCoverageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void classifiesTypedHandlersAndExplicitFallbacks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DescriptorRegistry descriptors = DescriptorRegistry.load(TestArtifacts.networkDescriptor());
        MessageCatalog catalog = MessageCatalog.load(TestArtifacts.messageCatalog(), mapper);
        GameDataIndex data = GameDataIndex.load(TestArtifacts.gameDataRoot(), mapper);
        JsonPlayerRepository repository = JsonPlayerRepository.load(
                temporaryDirectory.resolve("state.json"), mapper, data);
        PacketRouter router = new PacketRouter(
                catalog,
                descriptors,
                new PacketCodec(),
                repository,
                new PlayerMessageFactory(descriptors));

        ProtocolCoverage.Snapshot coverage = router.coverage();
        assertEquals(973, coverage.summary().totalMessages());
        assertTrue(coverage.summary().totalRequests() > 200);
        assertEquals(13, coverage.summary().implementedRequests());
        assertEquals(13, coverage.summary().typedJavaRequests());
        assertEquals(
                coverage.summary().totalRequests() - 13,
                coverage.summary().fallbackRequests());
        assertEquals(
                coverage.summary().totalRequests(),
                coverage.categories().stream()
                        .mapToInt(ProtocolCoverage.CategoryCoverage::totalRequests)
                        .sum());
        assertTrue(coverage.requests().stream().anyMatch(row ->
                row.requestName().equals("story_settle_req")
                        && row.implementation().equals("typed_java")));
        assertTrue(coverage.requests().stream().anyMatch(row ->
                row.implementation().equals("fallback_empty")));
    }
}
