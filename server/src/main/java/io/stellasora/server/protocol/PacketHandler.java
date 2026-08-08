package io.stellasora.server.protocol;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

@FunctionalInterface
public interface PacketHandler {
    Message handle(RequestContext context, DynamicMessage request) throws Exception;
}
