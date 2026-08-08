package io.stellasora.server.protocol;

import io.stellasora.server.game.PlayerMessageFactory;
import io.stellasora.server.game.PlayerState;
import io.stellasora.server.persistence.PlayerRepository;

public record RequestContext(
        Session session,
        PlayerState player,
        PlayerRepository repository,
        DescriptorRegistry descriptors,
        PlayerMessageFactory messages) {}
