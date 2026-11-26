package trivia.shared.api;

import trivia.shared.protocol.Message;

/**
 * Transport-agnostic view of a connected player session.
 * Implemented by NIO TCP and WebSocket bridges.
 */
public interface ClientSession {
    String getConnectionId();

    String getPlayerId();

    String getPlayerName();

    void setPlayerId(String playerId);

    void setPlayerName(String playerName);

    String getRoomId();

    void setRoomId(String roomId);

    void send(Message message);

    boolean isOpen();

    void close();

    /** Mark application-level activity (PING or any inbound message). */
    void touchHeartbeat();

    long getLastHeartbeatMs();
}
