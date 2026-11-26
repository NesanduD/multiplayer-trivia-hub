package trivia;

/**
 * One complete command, passed to the room thread pool.
 *
 * Multithreading: this is not called on the selector thread. A slow room stays in the
 * fixed pool, and select() keeps serving the other sockets.
 * The line has already been framed: no '\n', and at most one trailing '\r' was removed,
 * which is what BufferedReader.readLine() does.
 * Secure network programming: do not trust a player name or room id written inside the
 * line. ANSWER and CHAT are authorized with session.getPlayerId() and session.getRoomId(),
 * which the server stored at JOIN. A command for some other room is rejected with
 * ERROR:Unauthorized room action.
 */
public interface InboundHandler {
    void onCommand(ClientSession session, String line);
}
