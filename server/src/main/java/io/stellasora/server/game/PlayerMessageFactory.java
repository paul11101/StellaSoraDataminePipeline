package io.stellasora.server.game;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import io.stellasora.server.protocol.DescriptorRegistry;
import io.stellasora.server.protocol.ProtocolException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import proto.PlayerData;
import proto.PlayerLogin;
import proto.PlayerPing;
import proto.Public;

/** Builds the authoritative player-state messages consumed by the game client. */
public final class PlayerMessageFactory {
    public PlayerMessageFactory(DescriptorRegistry descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
    }

    public PlayerData.PlayerInfo playerInfo(PlayerState state) {
        PlayerData.PlayerInfo.Builder player = PlayerData.PlayerInfo.newBuilder();
        populateSingularMessages(player, 2, new HashSet<>());

        Public.AccInfo.Builder account = Public.AccInfo.newBuilder()
                .setId(state.uid)
                .setNickName(state.nickname)
                .setHashtag(state.hashtag)
                .setHeadIcon(state.headIcon)
                .setCreateTime(state.createdAt);
        state.newbieStepSnapshot().forEach((groupId, stepId) -> account.addNewbies(
                Public.NewbieInfo.newBuilder().setGroupId(groupId).setStepId(stepId)));
        player.setAcc(account);

        state.resourceSnapshot().forEach((id, quantity) -> player.addRes(
                Public.Res.newBuilder().setTid(id).setQty(quantity)));
        state.itemSnapshot().forEach((id, quantity) -> player.addItems(
                Public.Item.newBuilder()
                        .setId(Integer.toUnsignedLong(id))
                        .setTid(id)
                        .setQty(quantity)));

        List<PlayerState.CharacterState> characters = state.characterList();
        for (PlayerState.CharacterState character : characters) {
            Public.Char.Builder value = Public.Char.newBuilder();
            populateSingularMessages(value, 2, new HashSet<>());
            value.setTid(character.id)
                    .setLevel(character.level)
                    .setExp(character.experience)
                    .setSkin(character.skin)
                    .setAffinityLevel(character.affinityLevel)
                    .setCreateTime(character.createTime);
            player.addChars(value);
        }

        Public.TowerFormation.Builder formation = Public.TowerFormation.newBuilder();
        if (!characters.isEmpty()) {
            Public.FormationInfo.Builder info = Public.FormationInfo.newBuilder().setNumber(1);
            characters.stream().limit(3).forEach(character -> info.addCharIds(character.id));
            formation.addInfo(info);
        }
        player.setFormation(formation);
        player.setEnergy(PlayerData.EnergyInfo.newBuilder().setEnergy(energy(state)).setCount(0));
        player.addAllBoard(state.boardSnapshot());
        player.setWorldClass(PlayerData.WorldClassInfo.newBuilder()
                .setCur(state.worldClass)
                .setStage(state.worldStage));

        Public.StoryInfo.Builder story = Public.StoryInfo.newBuilder()
                .addAllEvidences(state.storyEvidenceSnapshot())
                .setBuildId(state.storyBuildIdSnapshot());
        for (PlayerState.StoryState storyState : state.storyList()) {
            Public.Story.Builder storyRow = Public.Story.newBuilder().setIdx(storyState.id);
            addStoryChoices(storyRow, storyState.major, true);
            addStoryChoices(storyRow, storyState.personality, false);
            story.addStories(storyRow);
        }
        player.setStory(story);

        Set<Integer> tutorialRewards = state.tutorialRewardSnapshot();
        for (int levelId : state.tutorialPassedSnapshot()) {
            player.addTutorialLevels(Public.TutorialLevel.newBuilder()
                    .setLevelId(levelId)
                    .setPassed(true)
                    .setRewardReceived(tutorialRewards.contains(levelId)));
        }

        PlayerState.LastReadState savedLastRead = state.lastReadSnapshot();
        Public.LastRead.Builder lastRead = Public.LastRead.newBuilder();
        populateSingularMessages(lastRead, 2, new HashSet<>());
        lastRead.setType(savedLastRead.type)
                .setStory(Public.LastReadStory.newBuilder().setIdx(savedLastRead.storyId))
                .setStorySet(Public.LastReadStorySet.newBuilder()
                        .setChapterId(savedLastRead.storySetChapterId)
                        .setSectionId(savedLastRead.storySetSectionId))
                .setActivityStory(Public.LastReadActivityStory.newBuilder()
                        .setChapterId(savedLastRead.activityChapterId)
                        .setStoryId(savedLastRead.activityStoryId));
        player.setLastRead(lastRead);
        player.setServerTs(Instant.now().getEpochSecond());
        return player.build();
    }

    public PlayerPing.Pong pong() {
        return PlayerPing.Pong.newBuilder()
                .setServerTs(Instant.now().getEpochSecond())
                .build();
    }

    public Public.Energy energy(PlayerState state) {
        PlayerState.EnergyState saved = state.energySnapshot();
        return Public.Energy.newBuilder()
                .setPrimary(saved.primary())
                .setSecondary(saved.secondary())
                .setIsPrimary(true)
                .setUpdateTime(saved.updatedAt())
                .build();
    }

    public PlayerLogin.LoginResp login(String token) {
        return PlayerLogin.LoginResp.newBuilder().setToken(token).build();
    }

    public Public.ChangeInfo changeInfo() {
        return Public.ChangeInfo.getDefaultInstance();
    }

    public Message withNextPackage(Message message, byte[] nested) throws ProtocolException {
        Message.Builder builder = message.toBuilder();
        Descriptors.FieldDescriptor field = findField(
                builder.getDescriptorForType(), "NextPackage");
        if (field == null || field.getJavaType() != Descriptors.FieldDescriptor.JavaType.BYTE_STRING) {
            throw new ProtocolException(
                    "response has no NextPackage bytes field: "
                            + message.getDescriptorForType().getFullName());
        }
        builder.setField(field, ByteString.copyFrom(nested));
        return builder.build();
    }

    private static void addStoryChoices(
            Public.Story.Builder story, Map<Integer, Integer> choices, boolean major) {
        for (Map.Entry<Integer, Integer> choice : choices.entrySet()) {
            Public.StoryChoice value = Public.StoryChoice.newBuilder()
                    .setGroup(choice.getKey())
                    .setValue(choice.getValue())
                    .build();
            if (major) {
                story.addMajor(value);
            } else {
                story.addPersonality(value);
            }
        }
    }

    private static void populateSingularMessages(
            Message.Builder builder, int depth, Set<String> stack) {
        if (depth <= 0) {
            return;
        }
        String name = builder.getDescriptorForType().getFullName();
        if (!stack.add(name)) {
            return;
        }
        for (Descriptors.FieldDescriptor field : builder.getDescriptorForType().getFields()) {
            if (field.isRepeated()
                    || field.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                continue;
            }
            Message.Builder child = builder.newBuilderForField(field);
            populateSingularMessages(child, depth - 1, stack);
            builder.setField(field, child.build());
        }
        stack.remove(name);
    }

    private static Descriptors.FieldDescriptor findField(
            Descriptors.Descriptor descriptor, String requestedName) {
        Descriptors.FieldDescriptor exact = descriptor.findFieldByName(requestedName);
        if (exact != null) {
            return exact;
        }
        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            if (field.getName().equalsIgnoreCase(requestedName)
                    || field.getJsonName().equalsIgnoreCase(requestedName)) {
                return field;
            }
        }
        return null;
    }
}
