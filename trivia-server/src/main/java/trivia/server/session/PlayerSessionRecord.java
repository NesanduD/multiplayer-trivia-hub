package trivia.server.session;

import trivia.shared.api.ClientSession;

/**
 * Logical player session that can outlive a single TCP/WS transport during recovery.
 */
public final class PlayerSessionRecord {
    private final String playerId;
    private final String playerName;
    private volatile String tokenHash;
    private volatile String roomId;
    private volatile ClientSession transport;
    private volatile long lastHeartbeatMs;
    private volatile long disconnectedAtMs;
    private volatile boolean recoverable = true;

    public PlayerSessionRecord(String playerId, String playerName, String tokenHash, ClientSession transport) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.tokenHash = tokenHash;
        this.transport = transport;
        this.lastHeartbeatMs = System.currentTimeMillis();
        this.disconnectedAtMs = 0L;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public ClientSession getTransport() {
        return transport;
    }

    public void setTransport(ClientSession transport) {
        this.transport = transport;
    }

    public long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public void touchHeartbeat() {
        this.lastHeartbeatMs = System.currentTimeMillis();
    }

    public long getDisconnectedAtMs() {
        return disconnectedAtMs;
    }

    public void markDisconnected() {
        this.disconnectedAtMs = System.currentTimeMillis();
        this.transport = null;
    }

    public void clearDisconnected() {
        this.disconnectedAtMs = 0L;
    }

    public boolean isConnected() {
        return disconnectedAtMs == 0L && transport != null && transport.isOpen();
    }

    public boolean isRecoverable() {
        return recoverable;
    }

    public void setRecoverable(boolean recoverable) {
        this.recoverable = recoverable;
    }
}
