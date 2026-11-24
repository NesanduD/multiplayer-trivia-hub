package trivia;

/**
 * The slice of Member 5's session registry that the selector loop needs.
 *
 * NIO: after select() returns, the loop walks every session to drain queued writes,
 * not only the keys that were readable.
 * TCP: the map key is the client remote address (IP + source port), because that pair
 * is what separates connections that all use destination port 5000.
 * Multithreading: add, remove, and all can run at the same time as the selector thread,
 * so the real registry must tolerate that (a ConcurrentHashMap does). all() is copied
 * before it is walked, so a close during a write cannot break the loop.
 */
public interface Sessions {
    /** Publish a session only after its SelectionKey has been attached. */
    void add(ClientSession session);

    /** Drop the session after its channel has been closed. */
    void remove(ClientSession session);

    /** Current sessions. Iteration must survive a concurrent add or remove. */
    Iterable<ClientSession> all();
}
