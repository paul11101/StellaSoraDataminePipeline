package io.stellasora.server;

import java.nio.file.Path;

public final class TestArtifacts {
    private TestArtifacts() {}

    public static Path resourcesRoot() {
        return Path.of("resources/tw/v137").toAbsolutePath().normalize();
    }

    public static Path messageCatalog() {
        return resourcesRoot().resolve("protocol/message_ids.json");
    }

    public static Path networkDescriptor() {
        return resourcesRoot().resolve("protocol/network.desc");
    }

    public static Path gameDataRoot() {
        return resourcesRoot().resolve("game/readable");
    }
}
