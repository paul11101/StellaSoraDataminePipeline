package io.stellasora.server.game.system;

import io.stellasora.server.game.PlayerMessageFactory;
import java.util.List;

public final class GameSystems {
    private GameSystems() {}

    public static List<GameSystem> defaults(PlayerMessageFactory messages) {
        return List.of(new PlayerSystem(messages), new StorySystem(messages));
    }
}
