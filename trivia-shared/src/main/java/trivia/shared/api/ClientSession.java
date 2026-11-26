package trivia.shared.api;

import trivia.shared.protocol.Message;

/**
 * Transport-agnostic view of a connected player session.
 * Implemented by the NIO server layer.
 */
public interface ClientSession {
    String getPlayerId();

    String getPlayerName();

    void setPlayerId(String playerId);

    void setPlayerName(String playerName);

    void send(Message message);

    boolean isOpen();

    void close();
}
