package io.stellasora.server;

import io.stellasora.server.bootstrap.ServerApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class StellaServer {
    private StellaServer() {}

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        try (ServerApplication application = ServerApplication.load(arguments.config())) {
            System.out.println("Stella Sora local server loaded: " + application.summary());
            if (arguments.checkOnly()) {
                System.out.println("Configuration and recovered protocol artifacts are valid.");
                return;
            }
            application.start();
            System.out.println(
                    "Listening on http://"
                            + application.config().bindHost()
                            + ":"
                            + application.port());
            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown, "shutdown-hook"));
            shutdown.await();
        }
    }

    private record Arguments(Path config, boolean checkOnly) {
        private static Arguments parse(String[] args) {
            Path config = defaultConfig();
            boolean checkOnly = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--config" -> {
                        if (++index >= args.length) {
                            throw new IllegalArgumentException("--config requires a path");
                        }
                        config = Path.of(args[index]);
                    }
                    case "--check" -> checkOnly = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[index]);
                }
            }
            return new Arguments(config.toAbsolutePath().normalize(), checkOnly);
        }

        private static Path defaultConfig() {
            Path local = Path.of("config.json");
            if (Files.isRegularFile(local)) {
                return local;
            }
            String appPath = System.getProperty("jpackage.app-path", "");
            if (!appPath.isBlank()) {
                Path executable = Path.of(appPath).toAbsolutePath().normalize();
                Path executableDirectory = executable.getParent();
                if (executableDirectory != null) {
                    Path packaged = executableDirectory.resolve("config.json");
                    if (Files.isRegularFile(packaged)) {
                        return packaged;
                    }
                }
            }
            return Path.of("config.example.json");
        }
    }
}
