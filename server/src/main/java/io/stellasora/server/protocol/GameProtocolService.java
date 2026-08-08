package io.stellasora.server.protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.stellasora.server.diagnostic.PacketDiagnostics;
import java.security.KeyPair;
import java.time.Instant;

public final class GameProtocolService {
    private static final int IKE_REQUEST_ID = 1;
    private static final int IKE_RESPONSE_ID = 2;

    private final byte[] bootstrapKey;
    private final CryptoSuite crypto;
    private final PacketCodec codec;
    private final DescriptorRegistry descriptors;
    private final PacketRouter router;
    private final SessionManager sessions;
    private final PacketDiagnostics diagnostics;

    public GameProtocolService(
            String garbleKey,
            CryptoSuite crypto,
            PacketCodec codec,
            DescriptorRegistry descriptors,
            PacketRouter router,
            SessionManager sessions,
            PacketDiagnostics diagnostics)
            throws ProtocolException {
        this.bootstrapKey = crypto.asciiKey(garbleKey);
        this.crypto = crypto;
        this.codec = codec;
        this.descriptors = descriptors;
        this.router = router;
        this.sessions = sessions;
        this.diagnostics = diagnostics;
    }

    public byte[] handle(byte[] encryptedFrame, String token, String remote) throws Exception {
        if (token == null || token.isBlank()) {
            return handleHandshake(encryptedFrame, remote);
        }
        Session session = sessions.find(token)
                .orElseThrow(() -> new ProtocolException("unknown or expired X-Token"));
        byte[] plaintext = null;
        Packet request = null;
        try {
            plaintext = crypto.decrypt(encryptedFrame, session.key(), session.cipherSuite());
            request = codec.decodeAuthenticated(plaintext);
            session.touch(request.sequence());
            PacketResponse response = router.route(session, request);
            byte[] logical = codec.encodeServer(response.messageId(), response.payload());
            byte[] encrypted = crypto.encrypt(logical, session.key(), session.cipherSuite());
            diagnostics.frame(remote, "authenticated", encryptedFrame, plaintext, request, "ok");
            return encrypted;
        } catch (Exception exception) {
            diagnostics.frame(
                    remote,
                    "authenticated",
                    encryptedFrame,
                    plaintext,
                    request,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
            throw exception;
        }
    }

    public ProtocolCoverage.Snapshot coverage() {
        return router.coverage();
    }

    private byte[] handleHandshake(byte[] encryptedFrame, String remote) throws Exception {
        byte[] plaintext = null;
        Packet request = null;
        try {
            plaintext = crypto.decodeBootstrap(encryptedFrame, bootstrapKey);
            request = codec.decodeHandshake(plaintext);
            if (request.messageId() != IKE_REQUEST_ID) {
                throw new ProtocolException(
                        "the first packet must be IKEReq (1), got " + request.messageId());
            }
            DynamicMessage ikeRequest = descriptors.parse("proto.IKEReq", request.payload());
            byte[] clientPublic = bytesField(ikeRequest, "PubKey");

            KeyPair serverKeys = crypto.generateP256KeyPair();
            byte[] serverPublic = crypto.encodeP256PublicKey(serverKeys.getPublic());
            byte[] sessionKey = crypto.deriveServerSessionKey(
                    serverKeys.getPrivate(), clientPublic, serverPublic);
            CipherSuite selectedCipher = CipherSuite.AES_256_GCM;
            Session session =
                    sessions.create(sessionKey, selectedCipher, clientPublic, serverPublic);

            DynamicMessage.Builder ikeResponse = descriptors.builder("proto.IKEResp");
            descriptors.setIfPresent(ikeResponse, "Token", session.token());
            descriptors.setIfPresent(ikeResponse, "Cipher", selectedCipher.wireValue());
            descriptors.setIfPresent(ikeResponse, "ServerTs", Instant.now().getEpochSecond());
            descriptors.setIfPresent(ikeResponse, "PubKey", ByteString.copyFrom(serverPublic));

            byte[] nextPackage = optionalBytesField(ikeRequest, "NextPackage");
            if (nextPackage.length > 0) {
                Packet nestedRequest = codec.decodeNested(nextPackage);
                PacketResponse nestedResponse = router.route(session, nestedRequest);
                byte[] nestedFrame = codec.encodeServer(
                        nestedResponse.messageId(), nestedResponse.payload());
                descriptors.setIfPresent(
                        ikeResponse, "NextPackage", ByteString.copyFrom(nestedFrame));
            }

            byte[] logical = codec.encodeServer(IKE_RESPONSE_ID, ikeResponse.build().toByteArray());
            byte[] encrypted = crypto.encodeBootstrap(logical, bootstrapKey);
            diagnostics.frame(remote, "handshake", encryptedFrame, plaintext, request, "ok");
            return encrypted;
        } catch (Exception exception) {
            diagnostics.frame(
                    remote,
                    "handshake",
                    encryptedFrame,
                    plaintext,
                    request,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
            throw exception;
        }
    }

    private byte[] bytesField(DynamicMessage message, String name) throws ProtocolException {
        byte[] result = optionalBytesField(message, name);
        if (result.length == 0) {
            throw new ProtocolException("IKEReq does not contain a client public key");
        }
        return result;
    }

    private byte[] optionalBytesField(DynamicMessage message, String name) {
        Descriptors.FieldDescriptor field =
                descriptors.findField(message.getDescriptorForType(), name);
        if (field == null) {
            return new byte[0];
        }
        Object value = message.getField(field);
        return value instanceof ByteString bytes ? bytes.toByteArray() : new byte[0];
    }
}
