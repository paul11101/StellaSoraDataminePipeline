package io.stellasora.server.protocol;

import java.util.Arrays;

public record PacketResponse(int messageId, byte[] payload) {
    public PacketResponse {
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
