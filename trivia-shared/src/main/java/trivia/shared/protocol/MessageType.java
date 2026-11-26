package trivia.shared.protocol;

/**
 * All TCP message types exchanged between client and server.
 */
public enum MessageType {
    // Connection
    HELLO,
    WELCOME,
    GOODBYE,
    ERROR,

    // Rooms
    CREATE_ROOM,
    ROOM_CREATED,
    JOIN_ROOM,
    LEAVE_ROOM,
    LIST_ROOMS,
    ROOM_LIST,
    ROOM_STATE,
    START_GAME,

    // Gameplay
    QUESTION,
    ANSWER,
    ANSWER_RESULT,
    SCORE_UPDATE,
    GAME_OVER,

    // Chat / presence
    CHAT,
    PLAYER_LEFT
}
