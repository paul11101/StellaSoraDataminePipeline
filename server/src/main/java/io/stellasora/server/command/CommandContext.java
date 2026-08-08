package io.stellasora.server.command;

import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.game.PlayerState;
import io.stellasora.server.persistence.PlayerRepository;

public record CommandContext(
        PlayerState player, GameDataIndex gameData, PlayerRepository repository) {}
