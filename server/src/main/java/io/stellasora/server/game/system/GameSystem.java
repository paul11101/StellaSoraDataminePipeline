package io.stellasora.server.game.system;

import io.stellasora.server.protocol.PacketRouter;
import io.stellasora.server.protocol.ProtocolException;

/** A cohesive authoritative gameplay subsystem that owns a set of packet handlers. */
@FunctionalInterface
public interface GameSystem {
    void register(PacketRouter router) throws ProtocolException;
}
