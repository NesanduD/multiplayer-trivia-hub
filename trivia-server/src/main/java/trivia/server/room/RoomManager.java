package trivia.server.room;

import trivia.server.game.QuestionBank;
import trivia.shared.api.ClientSession;
import trivia.shared.api.MatchStatsPublisher;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/**
 * Manages concurrent game rooms and routes player commands.
 */
public class RoomManager {
    private static final Logger LOG = Logger.getLogger(RoomManager.class.getName());

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, ClientSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final QuestionBank questionBank = new QuestionBank();
    private final ExecutorService roomExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "room-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "question-timer");
        t.setDaemon(true);
        return t;
    });
    private final MatchStatsPublisher statsPublisher;

    public RoomManager(MatchStatsPublisher statsPublisher) {
        this.statsPublisher = statsPublisher;
    }

    public void registerSession(ClientSession session) {
        if (session.getPlayerId() != null) {
            sessionsByPlayer.put(session.getPlayerId(), session);
        }
    }

    public void handle(ClientSession session, Message message) {
        try {
            switch (message.getType()) {
                case HELLO -> handleHello(session, message);
                case CREATE_ROOM -> handleCreateRoom(session, message);
                case JOIN_ROOM -> handleJoinRoom(session, message);
                case LEAVE_ROOM -> handleLeaveRoom(session);
                case LIST_ROOMS -> sendRoomList(session);
                case START_GAME -> handleStartGame(session, message);
                case ANSWER -> handleAnswer(session, message);
                case CHAT -> handleChat(session, message);
                case GOODBYE -> handleDisconnect(session, "GOODBYE");
                default -> session.send(Message.error("Unsupported message type: " + message.getType()));
            }
        } catch (Exception ex) {
            LOG.warning("Error handling " + message.getType() + ": " + ex.getMessage());
            session.send(Message.error(ex.getMessage() == null ? "Server error" : ex.getMessage()));
        }
    }

    public void handleDisconnect(ClientSession session, String reason) {
        if (session.getPlayerId() != null) {
            sessionsByPlayer.remove(session.getPlayerId());
        }
        String roomId = session.getRoomId();
        if (roomId != null) {
            GameRoom room = rooms.get(roomId);
            if (room != null) {
                room.removePlayer(session.getPlayerId(), reason);
                if (room.playerCount() == 0 || "CLOSED".equals(room.getStatus())) {
                    rooms.remove(roomId);
                }
            }
        }
        session.close();
    }

    public int totalPlayers() {
        return sessionsByPlayer.size();
    }

    public int totalRooms() {
        return rooms.size();
    }

    public void shutdown() {
        roomExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    private void handleHello(ClientSession session, Message message) {
        if (session.getPlayerId() != null) {
            session.send(Message.error("Already registered"));
            return;
        }
        String name = message.getPlayerName();
        if (name == null || name.isBlank()) {
            session.send(Message.error("playerName required"));
            return;
        }
        String playerId = "p-" + UUID.randomUUID();
        session.setPlayerId(playerId);
        session.setPlayerName(name.trim());
        sessionsByPlayer.put(playerId, session);

        Message welcome = Message.of(MessageType.WELCOME);
        welcome.setPlayerId(playerId);
        welcome.setPlayerName(session.getPlayerName());
        welcome.setServerTime(System.currentTimeMillis());
        session.send(welcome);
        sendRoomList(session);
        LOG.info(() -> "Player connected: " + session.getPlayerName() + " (" + playerId + ")");
    }

    private void handleCreateRoom(ClientSession session, Message message) {
        requireRegistered(session);
        if (session.getRoomId() != null) {
            session.send(Message.error("Leave current room first"));
            return;
        }
        String roomName = message.getRoomName() == null || message.getRoomName().isBlank()
                ? session.getPlayerName() + "'s Room"
                : message.getRoomName().trim();
        int maxPlayers = message.getMaxPlayers() == null ? 4 : Math.max(2, Math.min(8, message.getMaxPlayers()));
        String roomId = "r-" + UUID.randomUUID().toString().substring(0, 8);

        GameRoom room = new GameRoom(
                roomId, roomName, maxPlayers, session.getPlayerId(),
                questionBank, roomExecutor, scheduler, statsPublisher);
        rooms.put(roomId, room);
        room.addPlayer(session);

        Message created = Message.of(MessageType.ROOM_CREATED);
        created.setRoomId(roomId);
        created.setRoomName(roomName);
        created.setMaxPlayers(maxPlayers);
        session.send(created);
        session.send(room.roomStateMessage());
        broadcastRoomList();
    }

    private void handleJoinRoom(ClientSession session, Message message) {
        requireRegistered(session);
        if (session.getRoomId() != null) {
            session.send(Message.error("Leave current room first"));
            return;
        }
        GameRoom room = rooms.get(message.getRoomId());
        if (room == null) {
            session.send(Message.error("Room not found"));
            return;
        }
        if (!room.addPlayer(session)) {
            session.send(Message.error("Unable to join room"));
            return;
        }
        session.send(room.roomStateMessage());
        broadcastRoomList();
    }

    private void handleLeaveRoom(ClientSession session) {
        requireRegistered(session);
        String roomId = session.getRoomId();
        if (roomId == null) {
            return;
        }
        GameRoom room = rooms.get(roomId);
        if (room != null) {
            room.removePlayer(session.getPlayerId(), "LEFT");
            if (room.playerCount() == 0 || "CLOSED".equals(room.getStatus())) {
                rooms.remove(roomId);
            }
        }
        session.setRoomId(null);
        sendRoomList(session);
        broadcastRoomList();
    }

    private void handleStartGame(ClientSession session, Message message) {
        requireRegistered(session);
        GameRoom room = rooms.get(message.getRoomId() != null ? message.getRoomId() : session.getRoomId());
        if (room == null) {
            session.send(Message.error("Not in a room"));
            return;
        }
        room.startGame(session.getPlayerId());
    }

    private void handleAnswer(ClientSession session, Message message) {
        requireRegistered(session);
        GameRoom room = rooms.get(session.getRoomId());
        if (room == null) {
            session.send(Message.error("Not in a room"));
            return;
        }
        room.handleAnswer(session, message);
    }

    private void handleChat(ClientSession session, Message message) {
        requireRegistered(session);
        GameRoom room = rooms.get(session.getRoomId());
        if (room == null) {
            session.send(Message.error("Not in a room"));
            return;
        }
        room.handleChat(session, message);
    }

    private void sendRoomList(ClientSession session) {
        session.send(roomListMessage());
    }

    private void broadcastRoomList() {
        Message list = roomListMessage();
        for (ClientSession session : sessionsByPlayer.values()) {
            if (session.getRoomId() == null) {
                session.send(list);
            }
        }
    }

    private Message roomListMessage() {
        Message list = Message.of(MessageType.ROOM_LIST);
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (GameRoom room : rooms.values()) {
            if (!"CLOSED".equals(room.getStatus())) {
                summaries.add(room.summary());
            }
        }
        list.setRooms(summaries);
        return list;
    }

    private void requireRegistered(ClientSession session) {
        if (session.getPlayerId() == null) {
            throw new IllegalStateException("Send HELLO first");
        }
    }
}
