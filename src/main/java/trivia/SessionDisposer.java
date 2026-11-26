package trivia;

/**
 * Room cleanup after the selector thread has already closed the channel.
 *
 * TCP: the socket may already be closed. A second close must be harmless, because
 * both sides of the connection can finish independently.
 * Multithreading: the selector and the idle monitor can both notice the same dead
 * client. dispose must call session.tryMarkClosed() and return when it loses the race,
 * so the player is removed from the room only once.
 * This runs on the thread pool, not inside select(), so taking a room lock cannot
 * stall every other client.
 */
public interface SessionDisposer {
    void dispose(ClientSession session);
}
