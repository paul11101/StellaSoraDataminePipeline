package io.stellasora.server.protocol;

import com.google.protobuf.Message;

@FunctionalInterface
public interface TypedPacketHandler<Request extends Message, Response extends Message> {
    Response handle(RequestContext context, Request request) throws Exception;
}
