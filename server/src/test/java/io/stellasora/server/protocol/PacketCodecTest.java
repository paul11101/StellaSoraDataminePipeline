package io.stellasora.server.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PacketCodecTest {
    @Test
    void decodesAuthenticatedBigEndianHeader() throws Exception {
        PacketCodec codec = new PacketCodec();
        byte[] payload = {1, 2, 3, 4};
        byte[] encoded = codec.encodeAuthenticatedClientForTest(
                7304, 65535, 1_735_000_000L, payload);

        Packet packet = codec.decodeAuthenticated(encoded);
        assertEquals(7304, packet.messageId());
        assertEquals(65535, packet.sequence());
        assertEquals(1_735_000_000L, packet.serverTimestamp());
        assertArrayEquals(payload, packet.payload());
        assertTrue(packet.authenticated());
    }

    @Test
    void preservesSignedNegativeMessageIds() throws Exception {
        PacketCodec codec = new PacketCodec();
        byte[] encoded = codec.encodeServer(-10057, new byte[] {9});
        Packet packet = codec.decodeHandshake(encoded);
        assertEquals(-10057, packet.messageId());
        assertArrayEquals(new byte[] {9}, packet.payload());
    }
}
