package io.stellasora.server.protocol;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class Session {
    private final String token;
    private final byte[] key;
    private final CipherSuite cipherSuite;
    private final byte[] clientPublicKey;
    private final byte[] serverPublicKey;
    private final Instant createdAt;
    private final AtomicInteger lastClientSequence = new AtomicInteger();
    private volatile Instant lastSeenAt;
    private volatile boolean loggedIn;
    private volatile long lastStateRevision = -1L;

    public Session(
            String token,
            byte[] key,
            CipherSuite cipherSuite,
            byte[] clientPublicKey,
            byte[] serverPublicKey) {
        this.token = token;
        this.key = Arrays.copyOf(key, key.length);
        this.cipherSuite = cipherSuite;
        this.clientPublicKey = Arrays.copyOf(clientPublicKey, clientPublicKey.length);
        this.serverPublicKey = Arrays.copyOf(serverPublicKey, serverPublicKey.length);
        this.createdAt = Instant.now();
        this.lastSeenAt = createdAt;
    }

    public String token() {
        return token;
    }

    public byte[] key() {
        return Arrays.copyOf(key, key.length);
    }

    public CipherSuite cipherSuite() {
        return cipherSuite;
    }

    public byte[] clientPublicKey() {
        return Arrays.copyOf(clientPublicKey, clientPublicKey.length);
    }

    public byte[] serverPublicKey() {
        return Arrays.copyOf(serverPublicKey, serverPublicKey.length);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    public void touch(int clientSequence) {
        lastClientSequence.set(clientSequence);
        lastSeenAt = Instant.now();
    }

    public int lastClientSequence() {
        return lastClientSequence.get();
    }

    public boolean loggedIn() {
        return loggedIn;
    }

    public void markLoggedIn() {
        loggedIn = true;
    }

    public long lastStateRevision() {
        return lastStateRevision;
    }

    public void markStateRevision(long revision) {
        lastStateRevision = revision;
    }
}
