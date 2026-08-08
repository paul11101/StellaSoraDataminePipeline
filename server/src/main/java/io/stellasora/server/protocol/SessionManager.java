package io.stellasora.server.protocol;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Session create(
            byte[] key,
            CipherSuite cipherSuite,
            byte[] clientPublicKey,
            byte[] serverPublicKey) {
        byte[] tokenBytes = new byte[24];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Session session = new Session(token, key, cipherSuite, clientPublicKey, serverPublicKey);
        sessions.put(token, session);
        return session;
    }

    public Optional<Session> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(token));
    }

    public Collection<Session> activeSessions() {
        return java.util.List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }
}
