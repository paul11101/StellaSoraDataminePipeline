package io.stellasora.server.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import io.stellasora.server.TestArtifacts;
import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.game.PlayerMessageFactory;
import io.stellasora.server.persistence.JsonPlayerRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StoryProgressionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsStoryChoicesBuildAndLastReadPosition() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DescriptorRegistry descriptors = DescriptorRegistry.load(TestArtifacts.networkDescriptor());
        MessageCatalog catalog = MessageCatalog.load(TestArtifacts.messageCatalog(), mapper);
        GameDataIndex data = GameDataIndex.load(TestArtifacts.gameDataRoot(), mapper);
        Path statePath = temporaryDirectory.resolve("state.json");
        JsonPlayerRepository repository =
                JsonPlayerRepository.load(statePath, mapper, data);
        PlayerMessageFactory messages = new PlayerMessageFactory(descriptors);
        PacketRouter router = new PacketRouter(
                catalog, descriptors, new PacketCodec(), repository, messages);
        Session session = new SessionManager().create(
                new byte[CryptoSuite.KEY_BYTES],
                CipherSuite.AES_256_GCM,
                new byte[65],
                new byte[65]);

        DynamicMessage.Builder apply = descriptors.builder("proto.StoryApplyReq");
        descriptors.setIfPresent(apply, "Idx", 100);
        descriptors.setIfPresent(apply, "BuildId", 9001L);
        PacketResponse applyResponse = router.route(
                session, packet(7301, apply.build()));
        assertEquals(7302, applyResponse.messageId());

        DynamicMessage.Builder major = descriptors.builder("proto.StoryOptions");
        descriptors.setIfPresent(major, "Group", 11);
        descriptors.setIfPresent(major, "Choice", 22);
        descriptors.setIfPresent(major, "Factor", 0);
        DynamicMessage.Builder personality = descriptors.builder("proto.StoryOptions");
        descriptors.setIfPresent(personality, "Group", 33);
        descriptors.setIfPresent(personality, "Choice", 44);
        descriptors.setIfPresent(personality, "Factor", 5);
        DynamicMessage.Builder settledStory = descriptors.builder("proto.StorySettle");
        descriptors.setIfPresent(settledStory, "Idx", 100);
        addRepeated(descriptors, settledStory, "Major", major.build());
        addRepeated(descriptors, settledStory, "Personality", personality.build());
        DynamicMessage.Builder settle = descriptors.builder("proto.StorySettleReq");
        addRepeated(descriptors, settle, "List", settledStory.build());
        addRepeated(descriptors, settle, "Evidences", 500);
        PacketResponse settleResponse = router.route(
                session, packet(7304, settle.build()));
        assertEquals(7305, settleResponse.messageId());

        DynamicMessage.Builder lastStory = descriptors.builder("proto.LastReadStory");
        descriptors.setIfPresent(lastStory, "Idx", 100);
        DynamicMessage.Builder lastRead = descriptors.builder("proto.LastRead");
        descriptors.setIfPresent(lastRead, "Type", 1);
        descriptors.setIfPresent(lastRead, "Story", lastStory.build());
        PacketResponse lastReadResponse = router.route(
                session, packet(8325, lastRead.build()));
        assertEquals(8326, lastReadResponse.messageId());

        assertTrue(repository.state().storyPassed.contains(100));
        assertTrue(repository.state().storyEvidences.contains(500));
        assertEquals(22, repository.state().storyChoices.get(100).major.get(11));
        assertEquals(44, repository.state().storyChoices.get(100).personality.get(33));
        assertEquals(9001L, repository.state().storyBuildId);
        assertPlayerInfo(descriptors, messages.playerInfo(repository.state()));

        JsonPlayerRepository reloaded = JsonPlayerRepository.load(statePath, mapper, data);
        assertPlayerInfo(descriptors, new PlayerMessageFactory(descriptors).playerInfo(reloaded.state()));
    }

    private static Packet packet(int messageId, DynamicMessage message) {
        return new Packet(messageId, 1, 0L, message.toByteArray(), true);
    }

    private static void addRepeated(
            DescriptorRegistry descriptors,
            DynamicMessage.Builder builder,
            String fieldName,
            Object value) {
        Descriptors.FieldDescriptor field =
                descriptors.findField(builder.getDescriptorForType(), fieldName);
        builder.addRepeatedField(field, value);
    }

    private static void assertPlayerInfo(
            DescriptorRegistry descriptors, Message playerInfo) {
        Message account = nested(descriptors, playerInfo, "Acc");
        Descriptors.FieldDescriptor newbies =
                descriptors.findField(account.getDescriptorForType(), "Newbies");
        assertEquals(1, account.getRepeatedFieldCount(newbies));
        Message newbie = assertInstanceOf(
                Message.class, account.getRepeatedField(newbies, 0));
        assertEquals(1, field(descriptors, newbie, "GroupId"));
        assertEquals(-1, field(descriptors, newbie, "StepId"));

        Message storyInfo = nested(descriptors, playerInfo, "Story");
        assertEquals(9001L, field(descriptors, storyInfo, "BuildId"));
        Descriptors.FieldDescriptor stories =
                descriptors.findField(storyInfo.getDescriptorForType(), "Stories");
        assertEquals(1, storyInfo.getRepeatedFieldCount(stories));
        Message story = assertInstanceOf(
                Message.class, storyInfo.getRepeatedField(stories, 0));
        assertEquals(100, field(descriptors, story, "Idx"));
        assertChoice(descriptors, story, "Major", 11, 22);
        assertChoice(descriptors, story, "Personality", 33, 44);

        Message lastRead = nested(descriptors, playerInfo, "LastRead");
        assertEquals(1, field(descriptors, lastRead, "Type"));
        Message lastStory = nested(descriptors, lastRead, "Story");
        assertEquals(100, field(descriptors, lastStory, "Idx"));
    }

    private static void assertChoice(
            DescriptorRegistry descriptors,
            Message story,
            String fieldName,
            int expectedGroup,
            int expectedValue) {
        Descriptors.FieldDescriptor choices =
                descriptors.findField(story.getDescriptorForType(), fieldName);
        assertEquals(1, story.getRepeatedFieldCount(choices));
        Message choice = assertInstanceOf(
                Message.class, story.getRepeatedField(choices, 0));
        assertEquals(expectedGroup, field(descriptors, choice, "Group"));
        assertEquals(expectedValue, field(descriptors, choice, "Value"));
    }

    private static Message nested(
            DescriptorRegistry descriptors, Message message, String fieldName) {
        return assertInstanceOf(Message.class, field(descriptors, message, fieldName));
    }

    private static Object field(
            DescriptorRegistry descriptors, Message message, String fieldName) {
        Descriptors.FieldDescriptor field =
                descriptors.findField(message.getDescriptorForType(), fieldName);
        return message.getField(field);
    }
}
