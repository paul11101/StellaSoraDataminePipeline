package io.stellasora.server.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedOutputStream;
import io.stellasora.server.config.ServerConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public record BootstrapDocuments(byte[] encryptedServerList, byte[] encryptedResourceManifest) {
    private static final SecureRandom RANDOM = new SecureRandom();

    public BootstrapDocuments {
        encryptedServerList = encryptedServerList.clone();
        encryptedResourceManifest = encryptedResourceManifest.clone();
    }

    @Override
    public byte[] encryptedServerList() {
        return encryptedServerList.clone();
    }

    @Override
    public byte[] encryptedResourceManifest() {
        return encryptedResourceManifest.clone();
    }

    public static BootstrapDocuments load(ServerConfig config, ObjectMapper mapper)
            throws IOException {
        JsonNode template = mapper.readTree(config.serverListTemplate().toFile());
        byte[] plaintext = encodeServerList(template, config.agentUrl());
        byte[] encrypted = encryptAesCbc(plaintext, config.metadataKey());
        byte[] manifest = Files.readAllBytes(config.resourceManifest());
        return new BootstrapDocuments(encrypted, manifest);
    }

    private static byte[] encodeServerList(JsonNode root, String agentUrl) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CodedOutputStream coded = CodedOutputStream.newInstance(output);
        coded.writeUInt32(1, root.path("version").asInt());

        JsonNode agents = root.path("agents");
        if (agents.isArray() && !agents.isEmpty()) {
            for (JsonNode agent : agents) {
                coded.writeByteArray(2, encodeAgent(agent, agentUrl));
            }
        } else {
            coded.writeByteArray(2, encodeAgent(null, agentUrl));
        }

        coded.writeUInt32(3, root.path("status").asInt());
        writeString(coded, 4, root.path("message").asText());
        writeString(coded, 6, root.path("report_endpoint").asText());
        JsonNode rules = root.path("rules");
        if (rules.isArray()) {
            for (JsonNode rule : rules) {
                coded.writeByteArray(7, encodeRule(rule));
            }
        }
        coded.flush();
        return output.toByteArray();
    }

    private static byte[] encodeAgent(JsonNode agent, String agentUrl) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CodedOutputStream coded = CodedOutputStream.newInstance(output);
        String name = agent == null ? "Stella Sora Local" : agent.path("name").asText();
        int status = agent == null ? 1 : agent.path("status").asInt(1);
        int zone = agent == null ? 1 : agent.path("zone").asInt(1);
        writeString(coded, 1, name);
        writeString(coded, 2, agentUrl);
        coded.writeUInt32(3, status);
        coded.writeUInt32(4, zone);
        coded.flush();
        return output.toByteArray();
    }

    private static byte[] encodeRule(JsonNode rule) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CodedOutputStream coded = CodedOutputStream.newInstance(output);
        coded.writeUInt32(1, rule.path("platform").asInt());
        writeString(coded, 2, rule.path("channel").asText());
        writeString(coded, 3, rule.path("version").asText());
        coded.writeUInt32(4, rule.path("op").asInt());
        coded.writeUInt32(5, rule.path("action").asInt());
        writeString(coded, 6, rule.path("url").asText());
        coded.writeBool(7, rule.path("enable").asBoolean());
        coded.flush();
        return output.toByteArray();
    }

    private static void writeString(CodedOutputStream output, int field, String value)
            throws IOException {
        if (!value.isEmpty()) {
            output.writeString(field, value);
        }
    }

    private static byte[] encryptAesCbc(byte[] plaintext, String keyText) throws IOException {
        byte[] key = keyText.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IOException("unable to encrypt local server list", exception);
        }
    }
}
