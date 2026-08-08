package io.stellasora.server.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.stellasora.server.TestArtifacts;
import io.stellasora.server.diagnostic.PacketDiagnostics;
import io.stellasora.server.game.GameDataIndex;
import io.stellasora.server.game.PlayerMessageFactory;
import io.stellasora.server.persistence.JsonPlayerRepository;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GameProtocolServiceTest {
    private static final String GARBLE_KEY = "N&mfco452ZH5!nE3s&o5uxB57UGPENVo";

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void completesRecoveredStartupChainAndPushesStateChanges() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DescriptorRegistry descriptors = DescriptorRegistry.load(TestArtifacts.networkDescriptor());
        MessageCatalog catalog = MessageCatalog.load(TestArtifacts.messageCatalog(), mapper);
        GameDataIndex data = GameDataIndex.load(TestArtifacts.gameDataRoot(), mapper);
        JsonPlayerRepository repository = JsonPlayerRepository.load(
                temporaryDirectory.resolve("state.json"), mapper, data);
        PacketCodec codec = new PacketCodec();
        CryptoSuite crypto = new CryptoSuite();
        SessionManager sessions = new SessionManager();
        PacketRouter router = new PacketRouter(
                catalog,
                descriptors,
                codec,
                repository,
                new PlayerMessageFactory(descriptors));
        PacketDiagnostics diagnostics = new PacketDiagnostics(
                false,
                temporaryDirectory.resolve("protocol.jsonl"),
                mapper,
                catalog,
                codec);
        GameProtocolService service = new GameProtocolService(
                GARBLE_KEY, crypto, codec, descriptors, router, sessions, diagnostics);

        KeyPair clientKeys = crypto.generateP256KeyPair();
        byte[] clientPublic = crypto.encodeP256PublicKey(clientKeys.getPublic());
        DynamicMessage.Builder ike = descriptors.builder("proto.IKEReq");
        descriptors.setIfPresent(ike, "ProtoVersion", 1);
        descriptors.setIfPresent(ike, "ClientTs", Instant.now().getEpochSecond());
        descriptors.setIfPresent(ike, "PubKey", ByteString.copyFrom(clientPublic));
        byte[] bootstrapKey = GARBLE_KEY.getBytes(StandardCharsets.US_ASCII);
        byte[] ikeFrame = crypto.encodeBootstrap(
                codec.encodeServer(1, ike.build().toByteArray()), bootstrapKey);

        byte[] ikeResponseFrame = service.handle(ikeFrame, null, "127.0.0.1");
        Packet ikeResponsePacket = codec.decodeHandshake(
                crypto.decodeBootstrap(ikeResponseFrame, bootstrapKey));
        assertEquals(2, ikeResponsePacket.messageId());
        DynamicMessage ikeResponse = descriptors.parse(
                "proto.IKEResp", ikeResponsePacket.payload());
        String token = (String) ikeResponse.getField(
                descriptors.findField(ikeResponse.getDescriptorForType(), "Token"));
        byte[] serverPublic = ((ByteString) ikeResponse.getField(
                        descriptors.findField(ikeResponse.getDescriptorForType(), "PubKey")))
                .toByteArray();
        assertFalse(token.isBlank());

        byte[] sessionKey = crypto.deriveClientSessionKey(
                clientKeys.getPrivate(), clientPublic, serverPublic);
        DynamicMessage loginRequest = descriptors.empty("proto.LoginReq");
        byte[] loginLogical = codec.encodeAuthenticatedClientForTest(
                4, 1, Instant.now().getEpochSecond(), loginRequest.toByteArray());
        byte[] loginFrame = crypto.encrypt(
                loginLogical, sessionKey, CipherSuite.AES_256_GCM);

        byte[] loginResponseFrame = service.handle(loginFrame, token, "127.0.0.1");
        Packet loginResponsePacket = codec.decodeHandshake(crypto.decrypt(
                loginResponseFrame, sessionKey, CipherSuite.AES_256_GCM));
        assertEquals(5, loginResponsePacket.messageId());
        DynamicMessage loginResponse = descriptors.parse(
                "proto.LoginResp", loginResponsePacket.payload());
        String echoedToken = (String) loginResponse.getField(
                descriptors.findField(loginResponse.getDescriptorForType(), "Token"));
        assertEquals(token, echoedToken);
        assertArrayEquals(sessionKey, sessions.find(token).orElseThrow().key());

        DynamicMessage playerDataRequest = descriptors.empty("proto.Nil");
        byte[] playerDataLogical = codec.encodeAuthenticatedClientForTest(
                1001, 2, Instant.now().getEpochSecond(), playerDataRequest.toByteArray());
        byte[] playerDataFrame = crypto.encrypt(
                playerDataLogical, sessionKey, CipherSuite.AES_256_GCM);

        byte[] playerDataResponseFrame =
                service.handle(playerDataFrame, token, "127.0.0.1");
        Packet playerDataResponsePacket = codec.decodeHandshake(crypto.decrypt(
                playerDataResponseFrame, sessionKey, CipherSuite.AES_256_GCM));
        assertEquals(1002, playerDataResponsePacket.messageId());
        DynamicMessage playerInfo = descriptors.parse(
                "proto.PlayerInfo", playerDataResponsePacket.payload());
        assertLuaPlayerDataShape(descriptors, playerInfo);

        DynamicMessage energyRequest = descriptors.empty("proto.Nil");
        byte[] energyLogical = codec.encodeAuthenticatedClientForTest(
                8007, 3, Instant.now().getEpochSecond(), energyRequest.toByteArray());
        byte[] energyFrame = crypto.encrypt(
                energyLogical, sessionKey, CipherSuite.AES_256_GCM);
        byte[] energyResponseFrame = service.handle(energyFrame, token, "127.0.0.1");
        Packet energyResponsePacket = codec.decodeHandshake(crypto.decrypt(
                energyResponseFrame, sessionKey, CipherSuite.AES_256_GCM));
        assertEquals(8008, energyResponsePacket.messageId());
        DynamicMessage energy =
                descriptors.parse("proto.Energy", energyResponsePacket.payload());
        assertEquals(200, field(descriptors, energy, "Primary"));

        repository.state().grantItem(1, 1, true);
        DynamicMessage pingRequest = descriptors.empty("proto.Ping");
        byte[] pingLogical = codec.encodeAuthenticatedClientForTest(
                1012, 4, Instant.now().getEpochSecond(), pingRequest.toByteArray());
        byte[] pingFrame = crypto.encrypt(
                pingLogical, sessionKey, CipherSuite.AES_256_GCM);

        byte[] pingResponseFrame = service.handle(pingFrame, token, "127.0.0.1");
        Packet pingResponsePacket = codec.decodeHandshake(crypto.decrypt(
                pingResponseFrame, sessionKey, CipherSuite.AES_256_GCM));
        assertEquals(1013, pingResponsePacket.messageId());
        DynamicMessage pong = descriptors.parse("proto.Pong", pingResponsePacket.payload());
        ByteString nextPackage = (ByteString) pong.getField(
                descriptors.findField(pong.getDescriptorForType(), "NextPackage"));
        assertFalse(nextPackage.isEmpty());
        Packet pushedPlayerData = codec.decodeNested(nextPackage.toByteArray());
        assertEquals(1002, pushedPlayerData.messageId());
        assertLuaPlayerDataShape(
                descriptors,
                descriptors.parse("proto.PlayerInfo", pushedPlayerData.payload()));
    }

    private static void assertLuaPlayerDataShape(
            DescriptorRegistry descriptors, DynamicMessage playerInfo) {
        for (String fieldName : new String[] {
            "Acc",
            "Formation",
            "Energy",
            "WorldClass",
            "Agent",
            "Quests",
            "State",
            "Phone",
            "Story",
            "VampireSurvivorRecord",
            "HuntPermit",
            "TraceRequest",
            "LastRead"
        }) {
            Descriptors.FieldDescriptor field =
                    descriptors.findField(playerInfo.getDescriptorForType(), fieldName);
            assertTrue(field != null && playerInfo.hasField(field), fieldName);
        }

        Descriptors.FieldDescriptor questsField =
                descriptors.findField(playerInfo.getDescriptorForType(), "Quests");
        DynamicMessage quests = (DynamicMessage) playerInfo.getField(questsField);
        Descriptors.FieldDescriptor questList =
                descriptors.findField(quests.getDescriptorForType(), "List");
        assertTrue(questList != null && questList.isRepeated());

        Descriptors.FieldDescriptor characters =
                descriptors.findField(playerInfo.getDescriptorForType(), "Chars");
        assertTrue(playerInfo.getRepeatedFieldCount(characters) >= 3);

        Descriptors.FieldDescriptor board =
                descriptors.findField(playerInfo.getDescriptorForType(), "Board");
        assertEquals(3, playerInfo.getRepeatedFieldCount(board));
        assertEquals(410301, playerInfo.getRepeatedField(board, 0));
    }

    private static Object field(
            DescriptorRegistry descriptors, DynamicMessage message, String fieldName) {
        Descriptors.FieldDescriptor field =
                descriptors.findField(message.getDescriptorForType(), fieldName);
        return message.getField(field);
    }
}
