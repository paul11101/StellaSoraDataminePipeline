package io.stellasora.server.game.system;

import io.stellasora.server.game.PlayerMessageFactory;
import io.stellasora.server.protocol.PacketRouter;
import io.stellasora.server.protocol.ProtocolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import proto.Public;
import proto.StoryApply;
import proto.StorySetInfo;
import proto.StorySetRewardReceive;
import proto.StorySett;

/** Main story, story-set rewards, and tutorial-stage progression. */
public final class StorySystem implements GameSystem {
    private final PlayerMessageFactory messages;

    public StorySystem(PlayerMessageFactory messages) {
        this.messages = messages;
    }

    @Override
    public void register(PacketRouter router) throws ProtocolException {
        router.registerTyped(
                "story_apply_req",
                StoryApply.StoryApplyReq.getDefaultInstance(),
                Public.Nil.getDefaultInstance(),
                (context, request) -> {
                    context.player().startStory(request.getBuildId());
                    return Public.Nil.getDefaultInstance();
                });
        router.registerTyped(
                "story_settle_req",
                StorySett.StorySettleReq.getDefaultInstance(),
                Public.ChangeInfo.getDefaultInstance(),
                (context, request) -> {
                    for (StorySett.StorySettle story : request.getListList()) {
                        context.player().settleStory(
                                story.getIdx(),
                                choices(story.getMajorList()),
                                choices(story.getPersonalityList()));
                    }
                    request.getEvidencesList().forEach(context.player()::addEvidence);
                    return messages.changeInfo();
                });
        router.registerTyped(
                "tutorial_level_settle_req",
                Public.UI32.getDefaultInstance(),
                Public.Nil.getDefaultInstance(),
                (context, request) -> {
                    context.player().passTutorial(request.getValue());
                    return Public.Nil.getDefaultInstance();
                });
        router.registerTyped(
                "tutorial_level_reward_receive_req",
                Public.UI32.getDefaultInstance(),
                Public.ChangeInfo.getDefaultInstance(),
                (context, request) -> {
                    context.player().rewardTutorial(request.getValue());
                    return messages.changeInfo();
                });
        router.registerTyped(
                "story_set_info_req",
                Public.Nil.getDefaultInstance(),
                StorySetInfo.StorySetInfoResp.getDefaultInstance(),
                (context, request) -> storySetInfo(context.player().storySetRewardSnapshot()));
        router.registerTyped(
                "story_set_reward_receive_req",
                StorySetRewardReceive.StorySetRewardReceiveReq.getDefaultInstance(),
                Public.ChangeInfo.getDefaultInstance(),
                (context, request) -> {
                    context.player().rewardStorySet(
                            request.getChapterId(), request.getSectionId());
                    return messages.changeInfo();
                });
    }

    private static Map<Integer, Integer> choices(List<StorySett.StoryOptions> values) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(value.getGroup(), value.getChoice()));
        return result;
    }

    private static StorySetInfo.StorySetInfoResp storySetInfo(Iterable<String> savedRewards) {
        Map<Integer, List<Integer>> rewards = new LinkedHashMap<>();
        for (String value : savedRewards) {
            String[] parts = value.split(":", 2);
            if (parts.length == 2) {
                rewards.computeIfAbsent(Integer.parseInt(parts[0]), ignored -> new ArrayList<>())
                        .add(Integer.parseInt(parts[1]));
            }
        }
        StorySetInfo.StorySetInfoResp.Builder response =
                StorySetInfo.StorySetInfoResp.newBuilder();
        rewards.forEach((chapterId, sections) -> response.addChapters(
                StorySetInfo.StorySetChapter.newBuilder()
                        .setChapterId(chapterId)
                        .setSectionIndex(sections.stream()
                                .mapToInt(Integer::intValue)
                                .max()
                                .orElse(0))
                        .addAllRewardedIds(sections)));
        return response.build();
    }
}
