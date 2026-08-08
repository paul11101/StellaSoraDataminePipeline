package io.stellasora.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.stellasora.server.bootstrap.BootstrapDocuments;
import io.stellasora.server.command.CommandRegistry;
import io.stellasora.server.command.CommandResult;
import io.stellasora.server.config.ServerConfig;
import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.persistence.PlayerRepository;
import io.stellasora.server.protocol.GameProtocolService;
import io.stellasora.server.protocol.MessageCatalog;
import io.stellasora.server.protocol.SessionManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalHttpServer implements AutoCloseable {
    private static final int MAX_GAME_FRAME_BYTES = 8 * 1024 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper mapper;
    private final BootstrapDocuments bootstrap;
    private final GameProtocolService protocol;
    private final MessageCatalog catalog;
    private final SessionManager sessions;
    private final GameDataIndex dataIndex;
    private final PlayerRepository repository;
    private final CommandRegistry commands;
    private final byte[] adminKey;
    private final boolean accessLogEnabled;
    private final Path accessLogPath;

    public LocalHttpServer(
            ServerConfig config,
            ObjectMapper mapper,
            BootstrapDocuments bootstrap,
            GameProtocolService protocol,
            MessageCatalog catalog,
            SessionManager sessions,
            GameDataIndex dataIndex,
            PlayerRepository repository,
            CommandRegistry commands,
            String adminKey)
            throws IOException {
        this.mapper = mapper;
        this.bootstrap = bootstrap;
        this.protocol = protocol;
        this.catalog = catalog;
        this.sessions = sessions;
        this.dataIndex = dataIndex;
        this.repository = repository;
        this.commands = commands;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
        this.accessLogEnabled = config.diagnosticsEnabled();
        this.accessLogPath = config.diagnosticLog().resolveSibling("http-access.jsonl");
        this.server = HttpServer.create(new InetSocketAddress(config.bindHost(), config.port()), 64);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/health", this::health);
        server.createContext("/meta/serverlist.html", exchange -> staticBytes(
                exchange, bootstrap.encryptedServerList(), "application/octet-stream"));
        server.createContext("/meta/win.html", exchange -> staticBytes(
                exchange, bootstrap.encryptedResourceManifest(), "application/octet-stream"));
        server.createContext("/game/", this::game);
        server.createContext("/admin/command", this::adminCommand);
        server.createContext("/admin/state", this::adminState);
        server.createContext("/admin/protocol-coverage", this::adminProtocolCoverage);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("time", Instant.now().toString());
        response.put("messageCatalog", catalog.size());
        response.put("protocolRequests", protocol.coverage().summary().totalRequests());
        response.put("protocolHandlers", protocol.coverage().summary().implementedRequests());
        response.put("sessions", sessions.size());
        response.put("gameData", dataIndex.counts());
        response.put("playerRevision", repository.state().revision);
        sendJson(exchange, 200, response);
    }

    private void staticBytes(HttpExchange exchange, byte[] value, String contentType)
            throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        sendBytes(exchange, 200, value, contentType);
    }

    private void game(HttpExchange exchange) throws IOException {
        if (!"/game/".equals(exchange.getRequestURI().getPath())) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_GAME_FRAME_BYTES + 1);
        exchange.setAttribute("requestLength", body.length);
        if (body.length > MAX_GAME_FRAME_BYTES) {
            sendJson(exchange, 413, Map.of("error", "frame too large"));
            return;
        }
        String token = exchange.getRequestHeaders().getFirst("X-Token");
        String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
        try {
            byte[] response = protocol.handle(body, token, remote);
            sendBytes(exchange, 200, response, "application/octet-stream");
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            sendJson(exchange, 400, Map.of("error", message));
        }
    }

    private void adminCommand(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(64 * 1024 + 1);
        exchange.setAttribute("requestLength", body.length);
        if (body.length > 64 * 1024) {
            sendJson(exchange, 413, Map.of("error", "command body too large"));
            return;
        }
        String command;
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        try {
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("json")) {
                JsonNode request = mapper.readTree(body);
                command = request.path("command").asText();
            } else {
                command = new String(body, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            sendJson(exchange, 400, Map.of("error", "invalid command JSON"));
            return;
        }
        CommandResult result = commands.invoke(command);
        sendJson(exchange, result.success() ? 200 : 400, result);
    }

    private void adminState(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        sendJson(exchange, 200, repository.state());
    }

    private void adminProtocolCoverage(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        sendJson(exchange, 200, protocol.coverage());
    }

    private boolean authorize(HttpExchange exchange) throws IOException {
        String supplied = exchange.getRequestHeaders().getFirst("X-Admin-Key");
        byte[] candidate =
                supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(adminKey, candidate)) {
            sendJson(exchange, 401, Map.of("error", "invalid admin key"));
            return false;
        }
        return true;
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendJson(exchange, 405, Map.of("error", "method not allowed"));
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendBytes(exchange, status, mapper.writeValueAsBytes(value), "application/json; charset=utf-8");
    }

    private void sendBytes(HttpExchange exchange, int status, byte[] value, String contentType)
            throws IOException {
        appendAccess(exchange, status, value.length);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, value.length);
        try (var output = exchange.getResponseBody()) {
            output.write(value);
        } finally {
            exchange.close();
        }
    }

    private synchronized void appendAccess(HttpExchange exchange, int status, int responseLength) {
        if (!accessLogEnabled) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", Instant.now().toString());
        row.put("remote", exchange.getRemoteAddress().getAddress().getHostAddress());
        row.put("method", exchange.getRequestMethod());
        row.put("path", exchange.getRequestURI().getPath());
        row.put("status", status);
        row.put("requestLength", requestLength(exchange));
        row.put("responseLength", responseLength);
        try {
            Path parent = accessLogPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    accessLogPath,
                    mapper.writeValueAsString(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.println("HTTP access diagnostic write failed: " + exception.getMessage());
        }
    }

    private static long requestLength(HttpExchange exchange) {
        Object measured = exchange.getAttribute("requestLength");
        if (measured instanceof Integer length) {
            return length.longValue();
        }
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared == null) {
            return 0L;
        }
        try {
            return Long.parseLong(declared);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
