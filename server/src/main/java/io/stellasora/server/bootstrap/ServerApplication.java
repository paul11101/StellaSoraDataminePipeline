package io.stellasora.server.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.stellasora.server.config.ServerConfig;
import io.stellasora.server.command.CommandRegistry;
import io.stellasora.server.diagnostic.PacketDiagnostics;
import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.game.PlayerMessageFactory;
import io.stellasora.server.http.LocalHttpServer;
import io.stellasora.server.persistence.JsonPlayerRepository;
import io.stellasora.server.protocol.CryptoSuite;
import io.stellasora.server.protocol.DescriptorRegistry;
import io.stellasora.server.protocol.GameProtocolService;
import io.stellasora.server.protocol.MessageCatalog;
import io.stellasora.server.protocol.PacketCodec;
import io.stellasora.server.protocol.PacketRouter;
import io.stellasora.server.protocol.SessionManager;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Files;

public final class ServerApplication implements AutoCloseable {
    private final ServerConfig config;
    private final DescriptorRegistry descriptors;
    private final MessageCatalog catalog;
    private final GameDataIndex gameData;
    private final JsonPlayerRepository repository;
    private final SessionManager sessions;
    private final ObjectMapper mapper;
    private final CommandRegistry commands;
    private final BootstrapDocuments bootstrap;
    private final GameProtocolService protocol;
    private LocalHttpServer httpServer;

    private ServerApplication(
            ServerConfig config,
            DescriptorRegistry descriptors,
            MessageCatalog catalog,
            GameDataIndex gameData,
            JsonPlayerRepository repository,
            SessionManager sessions,
            ObjectMapper mapper,
            CommandRegistry commands,
            BootstrapDocuments bootstrap,
            GameProtocolService protocol) {
        this.config = config;
        this.descriptors = descriptors;
        this.catalog = catalog;
        this.gameData = gameData;
        this.repository = repository;
        this.sessions = sessions;
        this.mapper = mapper;
        this.commands = commands;
        this.bootstrap = bootstrap;
        this.protocol = protocol;
    }

    public static ServerApplication load(Path configPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ServerConfig config = ServerConfig.load(configPath, mapper);
        DescriptorRegistry descriptors = DescriptorRegistry.load(config.networkDescriptor());
        MessageCatalog catalog = MessageCatalog.load(config.messageCatalog(), mapper);
        GameDataIndex data = GameDataIndex.load(config.gameDataRoot(), mapper);
        JsonPlayerRepository repository =
                JsonPlayerRepository.load(config.stateFile(), mapper, data);
        CommandRegistry commands = new CommandRegistry(data, repository);
        BootstrapDocuments bootstrap = BootstrapDocuments.load(config, mapper);

        PacketCodec codec = new PacketCodec();
        CryptoSuite crypto = new CryptoSuite();
        SessionManager sessions = new SessionManager();
        PlayerMessageFactory playerMessages = new PlayerMessageFactory(descriptors);
        PacketRouter router = new PacketRouter(
                catalog, descriptors, codec, repository, playerMessages);
        writeProtocolCoverage(config, mapper, router);
        PacketDiagnostics diagnostics = new PacketDiagnostics(
                config.diagnosticsEnabled(),
                config.diagnosticLog(),
                mapper,
                catalog,
                codec);
        GameProtocolService protocol = new GameProtocolService(
                config.garbleKey(),
                crypto,
                codec,
                descriptors,
                router,
                sessions,
                diagnostics);
        return new ServerApplication(
                config,
                descriptors,
                catalog,
                data,
                repository,
                sessions,
                mapper,
                commands,
                bootstrap,
                protocol);
    }

    public synchronized void start() throws IOException {
        if (httpServer != null) {
            throw new IllegalStateException("server is already started");
        }
        LocalHttpServer http = new LocalHttpServer(
                config,
                mapper,
                bootstrap,
                protocol,
                catalog,
                sessions,
                gameData,
                repository,
                commands,
                config.adminKey());
        http.start();
        httpServer = http;
        startConsole();
    }

    public int port() {
        LocalHttpServer running = httpServer;
        return running == null ? config.port() : running.port();
    }

    public String summary() {
        return "messages="
                + catalog.size()
                + ", descriptors="
                + descriptors.messageCount()
                + ", handlers="
                + protocol.coverage().summary().implementedRequests()
                + ", data="
                + gameData.counts()
                + ", stateRevision="
                + repository.state().revision
                + ", sessions="
                + sessions.size();
    }

    public ServerConfig config() {
        return config;
    }

    private static void writeProtocolCoverage(
            ServerConfig config, ObjectMapper mapper, PacketRouter router) throws IOException {
        Path output = config.diagnosticLog().resolveSibling("protocol-coverage.json");
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), router.coverage());
    }

    private void startConsole() {
        Thread.ofPlatform().daemon().name("stella-command-console").start(() -> {
            try {
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    System.out.println(
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(commands.invoke(line)));
                }
            } catch (IOException exception) {
                System.err.println("Command console stopped: " + exception.getMessage());
            }
        });
    }

    @Override
    public synchronized void close() throws IOException {
        LocalHttpServer running = httpServer;
        if (running != null) {
            running.close();
            httpServer = null;
        }
        repository.save();
    }
}
