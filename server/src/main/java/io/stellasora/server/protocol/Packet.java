package io.stellasora.server.protocol;

import java.util.Arrays;

public record Packet(
        int messageId,
        int sequence,
        long serverTimestamp,
        byte[] payload,
        boolean authenticated) {

    public Packet {
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
