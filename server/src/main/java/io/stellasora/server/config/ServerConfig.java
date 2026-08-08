package io.stellasora.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public record ServerConfig(
        String bindHost,
        int port,
        String agentUrl,
        String metadataKey,
        String garbleKey,
        Path messageCatalog,
        Path networkDescriptor,
        Path serverListTemplate,
        Path resourceManifest,
        Path gameDataRoot,
        Path stateFile,
        Path diagnosticLog,
        String adminKey,
        boolean diagnosticsEnabled) {

    public static ServerConfig load(Path configPath, ObjectMapper mapper) throws IOException {
        Path absoluteConfig = configPath.toAbsolutePath().normalize();
        JsonNode root = mapper.readTree(absoluteConfig.toFile());
        Path base = absoluteConfig.getParent();
        if (base == null) {
            base = Path.of(".").toAbsolutePath().normalize();
        }

        ServerConfig config = new ServerConfig(
                text(root, "bindHost", "127.0.0.1"),
                root.path("port").asInt(18080),
                text(root, "agentUrl", "http://127.0.0.1:18080/game/"),
                requiredText(root, "metadataKey"),
                requiredText(root, "garbleKey"),
                resolve(base, requiredText(root, "messageCatalog")),
                resolve(base, requiredText(root, "networkDescriptor")),
                resolve(base, requiredText(root, "serverListTemplate")),
                resolve(base, requiredText(root, "resourceManifest")),
                resolve(base, requiredText(root, "gameDataRoot")),
                resolve(base, text(root, "stateFile", "runtime/player-state.json")),
                resolve(base, text(root, "diagnosticLog", "runtime/protocol.jsonl")),
                requiredText(root, "adminKey"),
                root.path("diagnosticsEnabled").asBoolean(true));
        config.validate();
        return config;
    }

    private void validate() throws IOException {
        if (port < 1 || port > 65535) {
            throw new IOException("port must be between 1 and 65535");
        }
        try {
            InetAddress address = InetAddress.getByName(bindHost);
            if (!address.isLoopbackAddress()) {
                throw new IOException("the local server may bind only to a loopback address");
            }
        } catch (UnknownHostException exception) {
            throw new IOException("invalid bindHost: " + bindHost, exception);
        }
        int metadataKeyLength = metadataKey.getBytes(StandardCharsets.UTF_8).length;
        if (metadataKeyLength != 16 && metadataKeyLength != 24 && metadataKeyLength != 32) {
            throw new IOException("metadataKey must be 16, 24, or 32 UTF-8 bytes");
        }
        if (garbleKey.getBytes(StandardCharsets.US_ASCII).length != 32) {
            throw new IOException("garbleKey must be exactly 32 ASCII bytes");
        }
        requireFile(messageCatalog, "messageCatalog");
        requireFile(networkDescriptor, "networkDescriptor");
        requireFile(serverListTemplate, "serverListTemplate");
        requireFile(resourceManifest, "resourceManifest");
        if (!Files.isDirectory(gameDataRoot)) {
            throw new IOException("gameDataRoot is not a directory: " + gameDataRoot);
        }
        if (adminKey.isBlank()) {
            throw new IOException("adminKey must not be blank");
        }
    }

    private static void requireFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(label + " is not a file: " + path);
        }
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
    }

    private static String requiredText(JsonNode root, String field) throws IOException {
        String value = root.path(field).asText();
        if (value.isBlank()) {
            throw new IOException("missing required config field: " + field);
        }
        return value;
    }

    private static String text(JsonNode root, String field, String fallback) {
        String value = root.path(field).asText();
        return value.isBlank() ? fallback : value;
    }
}
