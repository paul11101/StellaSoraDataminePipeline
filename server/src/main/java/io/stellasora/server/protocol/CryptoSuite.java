package io.stellasora.server.protocol;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoSuite {
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;
    public static final int OUTER_OVERHEAD_BYTES = NONCE_BYTES + TAG_BYTES;

    private static final int P256_COORDINATE_BYTES = 32;
    private static final int SEC1_UNCOMPRESSED_BYTES = 65;
    private static final SecureRandom RANDOM = new SecureRandom();

    public byte[] encrypt(byte[] plaintext, byte[] key, CipherSuite suite)
            throws ProtocolException {
        requireKey(key);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = cipher(suite);
            init(cipher, Cipher.ENCRYPT_MODE, suite, key, nonce);
            cipher.updateAAD(nonce);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] frame = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, frame, 0, nonce.length);
            System.arraycopy(encrypted, 0, frame, nonce.length, encrypted.length);
            return frame;
        } catch (GeneralSecurityException exception) {
            throw new ProtocolException("AEAD encryption failed", exception);
        }
    }

    public byte[] decrypt(byte[] frame, byte[] key, CipherSuite suite)
            throws ProtocolException {
        requireKey(key);
        if (frame.length <= OUTER_OVERHEAD_BYTES) {
            throw new ProtocolException("encrypted frame must be longer than 28 bytes");
        }
        byte[] nonce = Arrays.copyOfRange(frame, 0, NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(frame, NONCE_BYTES, frame.length);
        try {
            Cipher cipher = cipher(suite);
            init(cipher, Cipher.DECRYPT_MODE, suite, key, nonce);
            cipher.updateAAD(nonce);
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new ProtocolException("AEAD authentication failed", exception);
        }
    }

    public byte[] encodeBootstrap(byte[] plaintext, byte[] garbleKey)
            throws ProtocolException {
        return garbleEncode(encrypt(plaintext, garbleKey, CipherSuite.AES_256_GCM), garbleKey);
    }

    public byte[] decodeBootstrap(byte[] frame, byte[] garbleKey) throws ProtocolException {
        return decrypt(garbleDecode(frame, garbleKey), garbleKey, CipherSuite.AES_256_GCM);
    }

    public byte[] garbleEncode(byte[] frame, byte[] garbleKey) throws ProtocolException {
        requireGarbleKey(garbleKey);
        int lengthByte = frame.length & 0xff;
        byte[] encoded = new byte[frame.length];
        for (int index = 0; index < frame.length; index++) {
            int value = Byte.toUnsignedInt(frame[index])
                    ^ Byte.toUnsignedInt(garbleKey[index % garbleKey.length]);
            int rotated = ((value << 1) | (value >>> 7)) & 0xff;
            encoded[index] = (byte) (rotated ^ lengthByte);
        }
        return encoded;
    }

    public byte[] garbleDecode(byte[] frame, byte[] garbleKey) throws ProtocolException {
        requireGarbleKey(garbleKey);
        int lengthByte = frame.length & 0xff;
        byte[] decoded = new byte[frame.length];
        for (int index = 0; index < frame.length; index++) {
            int value = Byte.toUnsignedInt(frame[index]) ^ lengthByte;
            int rotated = ((value >>> 1) | ((value & 1) << 7)) & 0xff;
            decoded[index] = (byte) (rotated
                    ^ Byte.toUnsignedInt(garbleKey[index % garbleKey.length]));
        }
        return decoded;
    }

    public KeyPair generateP256KeyPair() throws ProtocolException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new ProtocolException("unable to generate P-256 key pair", exception);
        }
    }

    public byte[] encodeP256PublicKey(PublicKey publicKey) throws ProtocolException {
        if (!(publicKey instanceof ECPublicKey ecPublicKey)) {
            throw new ProtocolException("public key is not an EC public key");
        }
        byte[] encoded = new byte[SEC1_UNCOMPRESSED_BYTES];
        encoded[0] = 0x04;
        byte[] x = unsignedFixed(ecPublicKey.getW().getAffineX(), P256_COORDINATE_BYTES);
        byte[] y = unsignedFixed(ecPublicKey.getW().getAffineY(), P256_COORDINATE_BYTES);
        System.arraycopy(x, 0, encoded, 1, x.length);
        System.arraycopy(y, 0, encoded, 1 + x.length, y.length);
        return encoded;
    }

    public PublicKey decodeP256PublicKey(byte[] encoded) throws ProtocolException {
        if (encoded.length != SEC1_UNCOMPRESSED_BYTES || encoded[0] != 0x04) {
            throw new ProtocolException("client P-256 key must use 65-byte uncompressed SEC1 encoding");
        }
        try {
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65));
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
            ECPublicKeySpec keySpec = new ECPublicKeySpec(new ECPoint(x, y), curve);
            return KeyFactory.getInstance("EC").generatePublic(keySpec);
        } catch (GeneralSecurityException exception) {
            throw new ProtocolException("unable to decode client P-256 key", exception);
        }
    }

    public byte[] deriveServerSessionKey(
            PrivateKey serverPrivateKey, byte[] clientPublicBytes, byte[] serverPublicBytes)
            throws ProtocolException {
        PublicKey clientPublicKey = decodeP256PublicKey(clientPublicBytes);
        byte[] sharedSecret = ecdh(serverPrivateKey, clientPublicKey);
        return deriveSessionKey(sharedSecret, clientPublicBytes, serverPublicBytes);
    }

    public byte[] deriveClientSessionKey(
            PrivateKey clientPrivateKey, byte[] clientPublicBytes, byte[] serverPublicBytes)
            throws ProtocolException {
        PublicKey serverPublicKey = decodeP256PublicKey(serverPublicBytes);
        byte[] sharedSecret = ecdh(clientPrivateKey, serverPublicKey);
        return deriveSessionKey(sharedSecret, clientPublicBytes, serverPublicBytes);
    }

    public byte[] deriveSessionKey(
            byte[] sharedSecret, byte[] clientPublicBytes, byte[] serverPublicBytes)
            throws ProtocolException {
        byte[] normalizedSecret = normalizeSecret(sharedSecret);
        byte[] info = calculateInfo(clientPublicBytes, serverPublicBytes);
        return hkdfSha256(normalizedSecret, serverPublicBytes, info, KEY_BYTES);
    }

    public byte[] calculateInfo(byte[] clientPublicBytes, byte[] serverPublicBytes)
            throws ProtocolException {
        if (clientPublicBytes.length == 0 || serverPublicBytes.length == 0) {
            throw new ProtocolException("CalInfo requires non-empty public keys");
        }
        byte[] info = new byte[clientPublicBytes.length];
        for (int index = 0; index < clientPublicBytes.length; index++) {
            int client = Byte.toUnsignedInt(clientPublicBytes[index]);
            int server = Byte.toUnsignedInt(serverPublicBytes[index % serverPublicBytes.length]);
            int mixed = client <= server ? server >>> 1 : server << 1 & 0xff;
            info[index] = (byte) (client ^ mixed);
        }
        return info;
    }

    public byte[] asciiKey(String value) throws ProtocolException {
        byte[] key = value.getBytes(StandardCharsets.US_ASCII);
        requireKey(key);
        return key;
    }

    private static Cipher cipher(CipherSuite suite) throws GeneralSecurityException {
        return Cipher.getInstance(
                suite == CipherSuite.AES_256_GCM ? "AES/GCM/NoPadding" : "ChaCha20-Poly1305");
    }

    private static void init(
            Cipher cipher, int mode, CipherSuite suite, byte[] key, byte[] nonce)
            throws GeneralSecurityException {
        if (suite == CipherSuite.AES_256_GCM) {
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
        } else {
            cipher.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        }
    }

    private static byte[] ecdh(PrivateKey privateKey, PublicKey publicKey)
            throws ProtocolException {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(privateKey);
            agreement.doPhase(publicKey, true);
            return agreement.generateSecret();
        } catch (GeneralSecurityException exception) {
            throw new ProtocolException("ECDH agreement failed", exception);
        }
    }

    private static byte[] normalizeSecret(byte[] sharedSecret) {
        byte[] normalized = new byte[KEY_BYTES];
        int copyLength = Math.min(sharedSecret.length, normalized.length);
        System.arraycopy(
                sharedSecret,
                sharedSecret.length - copyLength,
                normalized,
                normalized.length - copyLength,
                copyLength);
        return normalized;
    }

    private static byte[] hkdfSha256(
            byte[] inputKeyMaterial, byte[] salt, byte[] info, int outputLength)
            throws ProtocolException {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            byte[] effectiveSalt = salt.length == 0 ? new byte[32] : salt;
            hmac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
            byte[] pseudoRandomKey = hmac.doFinal(inputKeyMaterial);

            byte[] output = new byte[outputLength];
            byte[] previous = new byte[0];
            int written = 0;
            int counter = 1;
            while (written < outputLength) {
                hmac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
                hmac.update(previous);
                hmac.update(info);
                hmac.update((byte) counter);
                previous = hmac.doFinal();
                int copyLength = Math.min(previous.length, outputLength - written);
                System.arraycopy(previous, 0, output, written, copyLength);
                written += copyLength;
                counter++;
            }
            return output;
        } catch (GeneralSecurityException exception) {
            throw new ProtocolException("HKDF-SHA256 failed", exception);
        }
    }

    private static byte[] unsignedFixed(BigInteger value, int size) throws ProtocolException {
        byte[] source = value.toByteArray();
        int offset = source.length > 1 && source[0] == 0 ? 1 : 0;
        int length = source.length - offset;
        if (length > size) {
            throw new ProtocolException("EC coordinate does not fit P-256");
        }
        byte[] result = new byte[size];
        System.arraycopy(source, offset, result, size - length, length);
        return result;
    }

    private static void requireKey(byte[] key) throws ProtocolException {
        if (key.length != KEY_BYTES) {
            throw new ProtocolException("protocol key must be exactly 32 bytes");
        }
    }

    private static void requireGarbleKey(byte[] key) throws ProtocolException {
        if (key.length == 0) {
            throw new ProtocolException("garble key must not be empty");
        }
    }
}
