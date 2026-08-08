package io.stellasora.server.game.system;

import io.stellasora.server.game.PlayerMessageFactory;
import io.stellasora.server.game.PlayerState;
import io.stellasora.server.protocol.PacketRouter;
import io.stellasora.server.protocol.ProtocolException;
import java.util.List;
import proto.PlayerBoard;
import proto.PlayerData;
import proto.PlayerLogin;
import proto.PlayerPing;
import proto.Public;

/** Login, account profile, tutorial flags, board selection, and energy state. */
public final class PlayerSystem implements GameSystem {
    private final PlayerMessageFactory messages;

    public PlayerSystem(PlayerMessageFactory messages) {
        this.messages = messages;
    }

    @Override
    public void register(PacketRouter router) throws ProtocolException {
        router.registerTyped(
                "player_login_req",
                PlayerLogin.LoginReq.getDefaultInstance(),
                PlayerLogin.LoginResp.getDefaultInstance(),
                (context, request) -> {
                    context.session().markLoggedIn();
                    return messages.login(context.session().token());
                });
        router.registerTyped(
                "player_data_req",
                Public.Nil.getDefaultInstance(),
                PlayerData.PlayerInfo.getDefaultInstance(),
                (context, request) -> {
                    PlayerData.PlayerInfo response = messages.playerInfo(context.player());
                    context.session().markStateRevision(context.player().revision);
                    return response;
                });
        router.registerTyped(
                "player_ping_req",
                PlayerPing.Ping.getDefaultInstance(),
                PlayerPing.Pong.getDefaultInstance(),
                (context, request) -> messages.pong());
        router.registerTyped(
                "player_learn_req",
                Public.NewbieInfo.getDefaultInstance(),
                Public.Nil.getDefaultInstance(),
                (context, request) -> {
                    context.player().learnNewbie(request.getGroupId(), request.getStepId());
                    return Public.Nil.getDefaultInstance();
                });
        router.registerTyped(
                "player_board_set_req",
                PlayerBoard.PlayerBoardSetReq.getDefaultInstance(),
                Public.Nil.getDefaultInstance(),
                (context, request) -> {
                    List<Integer> boardIds = List.copyOf(request.getIdsList());
                    context.player().updateBoard(boardIds);
                    return Public.Nil.getDefaultInstance();
                });
        router.registerTyped(
                "energy_info_req",
                Public.Nil.getDefaultInstance(),
                Public.Energy.getDefaultInstance(),
                (context, request) -> messages.energy(context.player()));
        router.registerTyped(
                "player_last_read_update_req",
                Public.LastRead.getDefaultInstance(),
                Public.Nil.getDefaultInstance(),
                (context, request) -> {
                    PlayerState.LastReadState value = new PlayerState.LastReadState();
                    value.type = request.getType();
                    value.storyId = request.getStory().getIdx();
                    value.storySetChapterId = request.getStorySet().getChapterId();
                    value.storySetSectionId = request.getStorySet().getSectionId();
                    value.activityChapterId = request.getActivityStory().getChapterId();
                    value.activityStoryId = request.getActivityStory().getStoryId();
                    context.player().updateLastRead(value);
                    return Public.Nil.getDefaultInstance();
                });
    }
}
