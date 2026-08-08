package io.stellasora.server.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.stellasora.server.protocol.MessageCatalog;
import io.stellasora.server.protocol.Packet;
import io.stellasora.server.protocol.PacketCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PacketDiagnostics {
    private final boolean enabled;
    private final Path logPath;
    private final ObjectMapper mapper;
    private final MessageCatalog catalog;
    private final PacketCodec packetCodec;

    public PacketDiagnostics(
            boolean enabled,
            Path logPath,
            ObjectMapper mapper,
            MessageCatalog catalog,
            PacketCodec packetCodec) {
        this.enabled = enabled;
        this.logPath = logPath;
        this.mapper = mapper;
        this.catalog = catalog;
        this.packetCodec = packetCodec;
    }

    public void frame(
            String remote,
            String stage,
            byte[] encryptedFrame,
            byte[] plaintext,
            Packet packet,
            String result) {
        if (!enabled) {
            return;
        }
        Map<String, Object> row = base(remote, stage, encryptedFrame, result);
        if (plaintext != null) {
            row.put("plaintextLength", plaintext.length);
            Map<String, Object> candidates = new LinkedHashMap<>();
            packetCodec.signedMessageIdCandidates(plaintext).forEach((offset, id) -> {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("id", id);
                catalog.find(id).ifPresent(entry -> candidate.put("name", entry.name()));
                candidates.put(Integer.toString(offset), candidate);
            });
            row.put("messageIdCandidates", candidates);
        }
        if (packet != null) {
            row.put("messageId", packet.messageId());
            catalog.find(packet.messageId()).ifPresent(entry -> row.put("messageName", entry.name()));
            row.put("sequence", packet.sequence());
            row.put("clientServerTimestamp", packet.serverTimestamp());
            row.put("protobufLength", packet.payload().length);
        }
        append(row);
    }

    public void error(String remote, String stage, byte[] encryptedFrame, String error) {
        if (!enabled) {
            return;
        }
        append(base(remote, stage, encryptedFrame, error));
    }

    private Map<String, Object> base(
            String remote, String stage, byte[] encryptedFrame, String result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", Instant.now().toString());
        row.put("remote", remote);
        row.put("stage", stage);
        row.put("frameLength", encryptedFrame.length);
        row.put("frameSha256Prefix", digestPrefix(encryptedFrame));
        row.put("result", result);
        return row;
    }

    private synchronized void append(Map<String, Object> row) {
        try {
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = mapper.writeValueAsString(row) + System.lineSeparator();
            Files.writeString(
                    logPath,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.println("Protocol diagnostic write failed: " + exception.getMessage());
        }
    }

    private static String digestPrefix(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
