package io.stellasora.server.protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.stellasora.server.game.PlayerMessageFactory;
import io.stellasora.server.game.system.GameSystem;
import io.stellasora.server.game.system.GameSystems;
import io.stellasora.server.persistence.PlayerRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transport-level packet dispatcher. Gameplay behavior is registered by {@link GameSystem}
 * implementations instead of being embedded in this class.
 */
public final class PacketRouter {
    private static final int MAX_CHAIN_DEPTH = 32;

    private final MessageCatalog catalog;
    private final DescriptorRegistry descriptors;
    private final PacketCodec codec;
    private final PlayerRepository repository;
    private final PlayerMessageFactory playerMessages;
    private final Map<Integer, Registration> handlers = new HashMap<>();

    public PacketRouter(
            MessageCatalog catalog,
            DescriptorRegistry descriptors,
            PacketCodec codec,
            PlayerRepository repository,
            PlayerMessageFactory playerMessages)
            throws ProtocolException {
        this(catalog, descriptors, codec, repository, playerMessages,
                GameSystems.defaults(playerMessages));
    }

    public PacketRouter(
            MessageCatalog catalog,
            DescriptorRegistry descriptors,
            PacketCodec codec,
            PlayerRepository repository,
            PlayerMessageFactory playerMessages,
            List<GameSystem> systems)
            throws ProtocolException {
        this.catalog = catalog;
        this.descriptors = descriptors;
        this.codec = codec;
        this.repository = repository;
        this.playerMessages = playerMessages;
        for (GameSystem system : List.copyOf(systems)) {
            system.register(this);
        }
    }

    public PacketResponse route(Session session, Packet packet) throws Exception {
        return route(session, packet, 0);
    }

    public void register(int requestId, int responseId, PacketHandler handler) {
        register(requestId, responseId, handler, "dynamic");
    }

    private void register(
            int requestId, int responseId, PacketHandler handler, String implementation) {
        Registration previous = handlers.put(
                requestId, new Registration(responseId, handler, implementation));
        if (previous != null) {
            throw new IllegalStateException("duplicate packet handler for message ID " + requestId);
        }
    }

    public void register(String requestName, PacketHandler handler) throws ProtocolException {
        MessageCatalog.Entry request = catalog.find(requestName)
                .orElseThrow(() -> new ProtocolException(
                        "request is missing from message catalog: " + requestName));
        MessageCatalog.Entry response = successResponse(request);
        register(request.id(), response.id(), handler);
    }

    public <Request extends Message, Response extends Message> void registerTyped(
            String requestName,
            Request requestDefault,
            Response responseDefault,
            TypedPacketHandler<Request, Response> handler)
            throws ProtocolException {
        MessageCatalog.Entry request = catalog.find(requestName)
                .orElseThrow(() -> new ProtocolException(
                        "request is missing from message catalog: " + requestName));
        MessageCatalog.Entry response = successResponse(request);
        requireType(request, requestDefault);
        requireType(response, responseDefault);

        @SuppressWarnings("unchecked")
        Parser<Request> parser = (Parser<Request>) requestDefault.getParserForType();
        register(request.id(), response.id(), (context, dynamicRequest) -> {
            Request typedRequest = parser.parseFrom(dynamicRequest.toByteArray());
            Response typedResponse = handler.handle(context, typedRequest);
            if (typedResponse == null) {
                throw new ProtocolException("handler returned no response for " + requestName);
            }
            requireType(response, typedResponse);
            return typedResponse;
        }, "typed_java");
    }

    public int handlerCount() {
        return handlers.size();
    }

    public Set<Integer> handledRequestIds() {
        return Set.copyOf(handlers.keySet());
    }

    public ProtocolCoverage.Snapshot coverage() {
        Map<Integer, String> implementations = new HashMap<>();
        handlers.forEach((id, registration) ->
                implementations.put(id, registration.implementation()));
        return ProtocolCoverage.snapshot(catalog, implementations);
    }

    private PacketResponse route(Session session, Packet packet, int depth) throws Exception {
        if (depth >= MAX_CHAIN_DEPTH) {
            throw new ProtocolException("NextPackage chain exceeds " + MAX_CHAIN_DEPTH + " packets");
        }
        MessageCatalog.Entry requestEntry = catalog.require(packet.messageId());
        if (!"client_to_server".equals(requestEntry.direction())) {
            throw new ProtocolException(
                    "client sent non-request message: " + requestEntry.name());
        }
        DynamicMessage request = descriptors.parse(requestEntry.protobufType(), packet.payload());
        long revisionBefore = repository.state().revision;

        Registration registration = handlers.get(packet.messageId());
        MessageCatalog.Entry responseEntry;
        Message response;
        if (registration != null) {
            responseEntry = catalog.require(registration.responseId());
            RequestContext context = new RequestContext(
                    session,
                    repository.state(),
                    repository,
                    descriptors,
                    playerMessages);
            response = registration.handler().handle(context, request);
        } else {
            responseEntry = successResponse(requestEntry);
            response = descriptors.empty(responseEntry.protobufType());
        }

        byte[] nextRequest = nextPackage(request);
        if (nextRequest.length > 0) {
            Packet nestedRequest = codec.decodeNested(nextRequest);
            PacketResponse nestedResponse = route(session, nestedRequest, depth + 1);
            byte[] nestedFrame = codec.encodeServer(
                    nestedResponse.messageId(), nestedResponse.payload());
            response = playerMessages.withNextPackage(response, nestedFrame);
        } else if ("player_ping_req".equals(requestEntry.name())
                && repository.state().revision > session.lastStateRevision()) {
            Message playerInfo = playerMessages.playerInfo(repository.state());
            MessageCatalog.Entry playerData = catalog.find("player_data_succeed_ack")
                    .orElseThrow(() -> new ProtocolException(
                            "player_data_succeed_ack is missing from message catalog"));
            byte[] nestedFrame = codec.encodeServer(playerData.id(), playerInfo.toByteArray());
            response = playerMessages.withNextPackage(response, nestedFrame);
            session.markStateRevision(repository.state().revision);
        }

        if (repository.state().revision != revisionBefore) {
            saveState();
        }
        return new PacketResponse(responseEntry.id(), response.toByteArray());
    }

    private MessageCatalog.Entry successResponse(MessageCatalog.Entry request)
            throws ProtocolException {
        if (!"client_to_server".equals(request.direction()) || !"request".equals(request.role())) {
            throw new ProtocolException("message is not a client request: " + request.name());
        }
        return catalog.successFor(request)
                .orElseThrow(() -> new ProtocolException(
                        "no success response is cataloged for " + request.name()));
    }

    private static void requireType(MessageCatalog.Entry entry, Message message)
            throws ProtocolException {
        String actual = message.getDescriptorForType().getFullName();
        if (!entry.protobufType().equals(actual)) {
            throw new ProtocolException(
                    "generated protobuf mismatch for "
                            + entry.name()
                            + ": expected "
                            + entry.protobufType()
                            + ", got "
                            + actual);
        }
    }

    private byte[] nextPackage(Message message) {
        Descriptors.FieldDescriptor field =
                descriptors.findField(message.getDescriptorForType(), "NextPackage");
        if (field == null) {
            return new byte[0];
        }
        Object value = message.getField(field);
        return value instanceof ByteString bytes ? bytes.toByteArray() : new byte[0];
    }

    private void saveState() throws ProtocolException {
        try {
            repository.save();
        } catch (IOException exception) {
            throw new ProtocolException("unable to persist local player state", exception);
        }
    }

    private record Registration(int responseId, PacketHandler handler, String implementation) {}
}
