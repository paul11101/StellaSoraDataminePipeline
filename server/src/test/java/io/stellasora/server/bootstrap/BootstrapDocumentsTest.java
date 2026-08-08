package io.stellasora.server.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.stellasora.server.config.ServerConfig;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

final class BootstrapDocumentsTest {
    @Test
    void generatesADecryptableLocalServerListAndKeepsTheSavedManifest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ServerConfig config = ServerConfig.load(
                java.nio.file.Path.of("config.example.json"), mapper);
        BootstrapDocuments documents = BootstrapDocuments.load(config, mapper);

        byte[] encrypted = documents.encryptedServerList();
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(config.metadataKey().getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(Arrays.copyOfRange(encrypted, 0, 16)));
        byte[] plaintext = cipher.doFinal(Arrays.copyOfRange(encrypted, 16, encrypted.length));
        String searchable = new String(plaintext, StandardCharsets.ISO_8859_1);
        assertTrue(searchable.contains("http://127.0.0.1:18080/game/"));
        assertTrue(searchable.contains("https://nova.stargazer-games.com/report/"));
        assertEquals(2_273_328, documents.encryptedResourceManifest().length);
    }
}
