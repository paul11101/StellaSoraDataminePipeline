package io.stellasora.server.persistence;

import io.stellasora.server.game.PlayerState;
import java.io.IOException;

public interface PlayerRepository {
    PlayerState state();

    void save() throws IOException;
}
