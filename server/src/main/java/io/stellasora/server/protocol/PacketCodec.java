package io.stellasora.server.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PacketCodec {
    public static final int MESSAGE_ID_BYTES = Short.BYTES;
    public static final int AUTHENTICATED_HEADER_BYTES = Long.BYTES + Short.BYTES + Short.BYTES;

    public Packet decodeHandshake(byte[] plaintext) throws ProtocolException {
        if (plaintext.length < MESSAGE_ID_BYTES) {
            throw new ProtocolException("handshake packet is shorter than the message ID");
        }
        ByteBuffer input = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN);
        int messageId = input.getShort();
        byte[] payload = new byte[input.remaining()];
        input.get(payload);
        return new Packet(messageId, 0, 0L, payload, false);
    }

    public Packet decodeAuthenticated(byte[] plaintext) throws ProtocolException {
        if (plaintext.length < AUTHENTICATED_HEADER_BYTES) {
            throw new ProtocolException(
                    "authenticated packet is shorter than the 12-byte logical header");
        }
        ByteBuffer input = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN);
        long serverTimestamp = input.getLong();
        int sequence = Short.toUnsignedInt(input.getShort());
        int messageId = input.getShort();
        byte[] payload = new byte[input.remaining()];
        input.get(payload);
        return new Packet(messageId, sequence, serverTimestamp, payload, true);
    }

    public Packet decodeNested(byte[] plaintext) throws ProtocolException {
        return decodeHandshake(plaintext);
    }

    public byte[] encodeServer(int messageId, byte[] protobufPayload) {
        return ByteBuffer.allocate(MESSAGE_ID_BYTES + protobufPayload.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort((short) messageId)
                .put(protobufPayload)
                .array();
    }

    public byte[] encodeAuthenticatedClientForTest(
            int messageId, int sequence, long serverTimestamp, byte[] protobufPayload) {
        return ByteBuffer.allocate(AUTHENTICATED_HEADER_BYTES + protobufPayload.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(serverTimestamp)
                .putShort((short) sequence)
                .putShort((short) messageId)
                .put(protobufPayload)
                .array();
    }

    public Map<Integer, Integer> signedMessageIdCandidates(byte[] plaintext) {
        int[] offsets = {0, 2, 4, 8, 10, 12};
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (int offset : offsets) {
            if (offset + Short.BYTES <= plaintext.length) {
                int value = ByteBuffer.wrap(plaintext, offset, Short.BYTES)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getShort();
                result.put(offset, value);
            }
        }
        return result;
    }
}
