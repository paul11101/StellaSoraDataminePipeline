package io.stellasora.server.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.security.KeyPair;
import org.junit.jupiter.api.Test;

final class CryptoSuiteTest {
    private static final byte[] KEY =
            "N&mfco452ZH5!nE3s&o5uxB57UGPENVo".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    @Test
    void roundTripsBothAuthenticatedCiphers() throws Exception {
        CryptoSuite crypto = new CryptoSuite();
        byte[] plaintext = "stella-protocol".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (CipherSuite suite : CipherSuite.values()) {
            byte[] encrypted = crypto.encrypt(plaintext, KEY, suite);
            assertNotEquals(new String(plaintext), new String(encrypted));
            assertArrayEquals(plaintext, crypto.decrypt(encrypted, KEY, suite));
        }
    }

    @Test
    void roundTripsBootstrapAeadAndGarble() throws Exception {
        CryptoSuite crypto = new CryptoSuite();
        byte[] plaintext = new byte[77];
        for (int index = 0; index < plaintext.length; index++) {
            plaintext[index] = (byte) (index * 17);
        }
        byte[] encoded = crypto.encodeBootstrap(plaintext, KEY);
        assertArrayEquals(plaintext, crypto.decodeBootstrap(encoded, KEY));
    }

    @Test
    void derivesTheSameP256SessionKeyOnBothSides() throws Exception {
        CryptoSuite crypto = new CryptoSuite();
        KeyPair client = crypto.generateP256KeyPair();
        KeyPair server = crypto.generateP256KeyPair();
        byte[] clientPublic = crypto.encodeP256PublicKey(client.getPublic());
        byte[] serverPublic = crypto.encodeP256PublicKey(server.getPublic());

        byte[] serverKey = crypto.deriveServerSessionKey(
                server.getPrivate(), clientPublic, serverPublic);
        byte[] clientKey = crypto.deriveClientSessionKey(
                client.getPrivate(), clientPublic, serverPublic);
        assertEquals(CryptoSuite.KEY_BYTES, serverKey.length);
        assertArrayEquals(serverKey, clientKey);
    }
}
