package io.stellasora.server.protocol;

public enum CipherSuite {
    AES_256_GCM(0),
    CHACHA20_POLY1305(1);

    private final int wireValue;

    CipherSuite(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static CipherSuite fromWireValue(int value) {
        return value == 0 ? AES_256_GCM : CHACHA20_POLY1305;
    }
}
