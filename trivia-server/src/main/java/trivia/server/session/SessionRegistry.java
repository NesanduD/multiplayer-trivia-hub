package trivia.server.session;

import trivia.shared.api.ClientSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps player identities and hashed session tokens to recoverable session records.
 */
public final class SessionRegistry {
    private final Map<String, PlayerSessionRecord> byPlayerId = new ConcurrentHashMap<>();
    private final Map<String, String> playerIdByTokenHash = new ConcurrentHashMap<>();
    private final Map<String, ClientSession> transportsByConnectionId = new ConcurrentHashMap<>();

    public void trackTransport(ClientSession session) {
        if (session.getConnectionId() != null) {
            transportsByConnectionId.put(session.getConnectionId(), session);
            session.touchHeartbeat();
        }
    }

    public void untrackTransport(ClientSession session) {
        if (session.getConnectionId() != null) {
            transportsByConnectionId.remove(session.getConnectionId(), session);
        }
    }

    public Collection<ClientSession> liveTransports() {
        return List.copyOf(transportsByConnectionId.values());
    }

    public String issueToken(PlayerSessionRecord record) {
        String raw = SessionTokens.generateRawToken();
        String hash = SessionTokens.sha256Hex(raw);
        String previous = record.getTokenHash();
        if (previous != null) {
            playerIdByTokenHash.remove(previous, record.getPlayerId());
        }
        record.setTokenHash(hash);
        playerIdByTokenHash.put(hash, record.getPlayerId());
        return raw;
    }

    public PlayerSessionRecord registerNew(String playerId, String playerName, ClientSession transport) {
        PlayerSessionRecord record = new PlayerSessionRecord(playerId, playerName, null, transport);
        byPlayerId.put(playerId, record);
        return record;
    }

    public Optional<PlayerSessionRecord> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPlayerId.get(playerId));
    }

    public Optional<PlayerSessionRecord> findByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = SessionTokens.sha256Hex(rawToken.trim());
        String playerId = playerIdByTokenHash.get(hash);
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPlayerId.get(playerId));
    }

    public void remove(String playerId) {
        PlayerSessionRecord record = byPlayerId.remove(playerId);
        if (record != null && record.getTokenHash() != null) {
            playerIdByTokenHash.remove(record.getTokenHash(), playerId);
        }
    }

    public List<PlayerSessionRecord> snapshot() {
        return new ArrayList<>(byPlayerId.values());
    }

    public int playerCount() {
        return byPlayerId.size();
    }
}
