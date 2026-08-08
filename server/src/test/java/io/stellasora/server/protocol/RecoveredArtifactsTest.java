package io.stellasora.server.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.stellasora.server.TestArtifacts;
import org.junit.jupiter.api.Test;

final class RecoveredArtifactsTest {
    @Test
    void loadsAllRecoveredMessageIds() throws Exception {
        MessageCatalog catalog = MessageCatalog.load(
                TestArtifacts.messageCatalog(), new ObjectMapper());
        assertEquals(973, catalog.size());
        assertEquals("proto.IKEReq", catalog.require(1).protobufType());
        assertEquals(5, catalog.successFor(catalog.require(4)).orElseThrow().id());
        assertEquals(7305, catalog.successFor(catalog.require(7304)).orElseThrow().id());
    }

    @Test
    void buildsTheRecoveredDynamicDescriptorGraph() throws Exception {
        DescriptorRegistry descriptors = DescriptorRegistry.load(
                TestArtifacts.networkDescriptor());
        assertTrue(descriptors.fileCount() >= 295);
        assertTrue(descriptors.messageCount() >= 709);
        assertNotNull(descriptors.requireMessage("proto.IKEReq"));
        assertNotNull(descriptors.requireMessage("proto.PlayerInfo"));
        assertNotNull(descriptors.requireMessage("proto.StorySettleReq"));
    }
}
