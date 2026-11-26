package trivia.server.room;

import trivia.server.config.ServerConfig;
import trivia.server.dispatch.SessionDispatcher;
import trivia.server.game.QuestionBank;
import trivia.server.session.HeartbeatMonitor;
import trivia.server.session.PlayerSessionRecord;
import trivia.server.session.SessionRegistry;
import trivia.shared.api.ClientSession;
import trivia.shared.api.MatchStatsPublisher;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/**
 * Manages concurrent game rooms and routes player commands on the dispatch pool.
 */
public class RoomManager {
    private static final Logger LOG = Logger.getLogger(RoomManager.class.getName());

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final QuestionBank questionBank = new QuestionBank();
    private final ExecutorService roomExecutor;
    private final ScheduledExecutorService scheduler;
    private final MatchStatsPublisher statsPublisher;
    private final ServerConfig config;
    private final SessionRegistry sessionRegistry = new SessionRegistry();
    private final SessionDispatcher dispatcher;
    private final HeartbeatMonitor heartbeatMonitor;

    public RoomManager(MatchStatsPublisher statsPublisher) {
        this(statsPublisher, ServerConfig.defaults());
    }

    public RoomManager(MatchStatsPublisher statsPublisher, ServerConfig config) {
        this.statsPublisher = statsPublisher;
        this.config = config;
        this.roomExecutor = Executors.newFixedThreadPool(Math.max(2, config.dispatchWorkers()), r -> {
            Thread t = new Thread(r, "room-worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "question-timer");
            t.setDaemon(true);
            return t;
        });
        this.dispatcher = new SessionDispatcher(config.dispatchWorkers());
        this.heartbeatMonitor = new HeartbeatMonitor(
                sessionRegistry,
                config,
                scheduler,
                this::handleIdleTimeout,
                this::expireAbandonedSessions);
        this.heartbeatMonitor.start();
    }

    public ServerConfig getConfig() {
        return config;
    }

    public SessionRegistry getSessionRegistry() {
        return sessionRegistry;
    }

    public void trackTransport(ClientSession session) {
        sessionRegistry.trackTransport(session);
    }

    /**
     * Enqueue inbound work off the I/O thread; order is preserved per connectionId.
     */
    public void dispatch(ClientSession session, Message message) {
        String key = session.getConnectionId() != null ? session.getConnectionId() : "anon";
        dispatcher.submit(key, () -> handle(session, message));
    }

    public void dispatchDisconnect(ClientSession session, String reason) {
        String key = session.getConnectionId() != null ? session.getConnectionId() : "anon";
        dispatcher.submit(key, () -> handleDisconnect(session, reason));
    }

    public void handle(ClientSession session, Message message) {
        session.touchHeartbeat();
        Optional<PlayerSessionRecord> record = sessionRegistry.findByPlayerId(session.getPlayerId());
        record.ifPresent(PlayerSessionRecord::touchHeartbeat);
        try {
            switch (message.getType()) {
                case HELLO -> handleHello(session, message);
                case RESUME -> handleResume(session, message);
                case CREATE_ROOM -> handleCreateRoom(session, message);
                case JOIN_ROOM -> handleJoinRoom(session, message);
                case LEAVE_ROOM -> handleLeaveRoom(session);
                case LIST_ROOMS -> sendRoomList(session);
                case START_GAME -> handleStartGame(session, message);
                case ANSWER -> handleAnswer(session, message);
                case CHAT -> handleChat(session, message);
                case GOODBYE -> handleDisconnect(session, "GOODBYE");
                case PING -> handlePing(session, message);
                default -> session.send(Message.error("Unsupported message type: " + message.getType()));
            }
        } catch (Exception ex) {
            LOG.warning("Error handling " + message.getType() + ": " + ex.getMessage());
            session.send(Message.error(ex.getMessage() == null ? "Server error" : ex.getMessage()));
        }
    }

    private void handleIdleTimeout(ClientSession session, String reason) {
        dispatchDisconnect(session, reason);
    }

    /**
     * Soft-disconnect recoverable players; hard-close intentional goodbye / unregistered sockets.
     */
    public void handleDisconnect(ClientSession session, String reason) {
        sessionRegistry.untrackTransport(session);
        String playerId = session.getPlayerId();
        boolean intentional = "GOODBYE".equals(reason) || "LEFT".equals(reason);
        boolean recoverableReason = !intentional;

        if (playerId != null) {
            Optional<PlayerSessionRecord> opt = sessionRegistry.findByPlayerId(playerId);
            if (opt.isPresent() && recoverableReason && opt.get().isRecoverable()) {
                softDisconnectPlayer(opt.get(), session, reason);
                session.close();
                return;
            }
            if (opt.isPresent() && opt.get().getDisconnectedAtMs() > 0 && recoverableReason) {
                // Already soft-disconnected; ignore duplicate transport teardown
                session.close();
                return;
            }
            hardRemovePlayer(playerId, reason);
        }
        session.close();
    }

    private void softDisconnectPlayer(PlayerSessionRecord record, ClientSession session, String reason) {
        if (record.getDisconnectedAtMs() > 0) {
            return;
        }
        String roomId = session.getRoomId() != null ? session.getRoomId() : record.getRoomId();
        record.setRoomId(roomId);
        record.markDisconnected();
        if (roomId != null) {
            GameRoom room = rooms.get(roomId);
            if (room != null) {
                room.softDisconnect(record.getPlayerId(), reason);
            }
        }
        LOG.info(() -> "Soft-disconnect " + record.getPlayerName() + " (" + reason + "), recover within "
                + config.sessionRecoverWindowMs() + "ms");
    }

    private void hardRemovePlayer(String playerId, String reason) {
        Optional<PlayerSessionRecord> opt = sessionRegistry.findByPlayerId(playerId);
        String roomId = opt.map(PlayerSessionRecord::getRoomId).orElse(null);
        ClientSession transport = opt.map(PlayerSessionRecord::getTransport).orElse(null);
        if (transport != null && transport.getRoomId() != null) {
            roomId = transport.getRoomId();
        }
        if (roomId == null && transport != null) {
            roomId = transport.getRoomId();
        }
        sessionRegistry.remove(playerId);
        if (roomId != null) {
            GameRoom room = rooms.get(roomId);
            if (room != null) {
                room.removePlayer(playerId, reason);
                if (room.playerCount() == 0 || "CLOSED".equals(room.getStatus())) {
                    rooms.remove(roomId);
                }
            }
        }
        if (transport != null) {
            transport.setRoomId(null);
        }
    }

    void expireAbandonedSessions() {
        long now = System.currentTimeMillis();
        long window = config.sessionRecoverWindowMs();
        for (PlayerSessionRecord record : sessionRegistry.snapshot()) {
            long disconnectedAt = record.getDisconnectedAtMs();
            if (disconnectedAt > 0 && now - disconnectedAt > window) {
                LOG.info(() -> "Recover window expired for " + record.getPlayerName());
                hardRemovePlayer(record.getPlayerId(), "SESSION_EXPIRED");
            }
        }
    }

    public int totalPlayers() {
        return sessionRegistry.playerCount();
    }

    public int totalRooms() {
        return rooms.size();
    }

    public List<Map<String, Object>> roomSummaries() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (GameRoom room : rooms.values()) {
            if (!"CLOSED".equals(room.getStatus())) {
                summaries.add(room.summary());
            }
        }
        return summaries;
    }

    public List<Map<String, Object>> onlinePlayers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PlayerSessionRecord record : sessionRegistry.snapshot()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerId", record.getPlayerId());
            row.put("playerName", record.getPlayerName());
            row.put("roomId", record.getRoomId());
            row.put("connected", record.isConnected());
            list.add(row);
        }
        return list;
    }

    public void shutdown() {
        heartbeatMonitor.close();
        dispatcher.close();
        roomExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    private void handleHello(ClientSession session, Message message) {
        if (session.getPlayerId() != null) {
            session.send(Message.error("Already registered"));
            return;
        }
        // Prefer RESUME when a token is presented on HELLO (backward-friendly clients)
        if (message.getSessionToken() != null && !message.getSessionToken().isBlank()) {
            handleResume(session, message);
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
        PlayerSessionRecord record = sessionRegistry.registerNew(playerId, session.getPlayerName(), session);
        String rawToken = sessionRegistry.issueToken(record);
        sendWelcome(session, record, rawToken, false);
        sendRoomList(session);
        LOG.info(() -> "Player connected: " + session.getPlayerName() + " (" + playerId + ")");
    }

    private void handleResume(ClientSession session, Message message) {
        if (session.getPlayerId() != null) {
            session.send(Message.error("Already registered"));
            return;
        }
        String token = message.getSessionToken();
        Optional<PlayerSessionRecord> opt = sessionRegistry.findByRawToken(token);
        if (opt.isEmpty()) {
            session.send(Message.error("Invalid or expired session token"));
            return;
        }
        PlayerSessionRecord record = opt.get();
        if (record.getDisconnectedAtMs() == 0L && record.getTransport() != null && record.getTransport().isOpen()) {
            session.send(Message.error("Session still active on another connection"));
            return;
        }
        long disconnectedAt = record.getDisconnectedAtMs();
        if (disconnectedAt > 0 && System.currentTimeMillis() - disconnectedAt > config.sessionRecoverWindowMs()) {
            hardRemovePlayer(record.getPlayerId(), "SESSION_EXPIRED");
            session.send(Message.error("Session recovery window expired"));
            return;
        }
        if (!record.isRecoverable()) {
            session.send(Message.error("Session is not recoverable"));
            return;
        }

        session.setPlayerId(record.getPlayerId());
        session.setPlayerName(record.getPlayerName());
        session.setRoomId(record.getRoomId());
        record.setTransport(session);
        record.clearDisconnected();
        record.touchHeartbeat();
        String rawToken = sessionRegistry.issueToken(record);

        String roomId = record.getRoomId();
        if (roomId != null) {
            GameRoom room = rooms.get(roomId);
            if (room == null) {
                record.setRoomId(null);
                session.setRoomId(null);
            } else {
                try {
                    room.rebindPlayer(session);
                } catch (RuntimeException ex) {
                    record.setRoomId(null);
                    session.setRoomId(null);
                }
            }
        }

        sendWelcome(session, record, rawToken, true);
        if (session.getRoomId() == null) {
            sendRoomList(session);
        }
        LOG.info(() -> "Player resumed: " + record.getPlayerName() + " (" + record.getPlayerId() + ")");
    }

    private void sendWelcome(ClientSession session, PlayerSessionRecord record, String rawToken, boolean resumed) {
        Message welcome = Message.of(MessageType.WELCOME);
        welcome.setPlayerId(record.getPlayerId());
        welcome.setPlayerName(record.getPlayerName());
        welcome.setServerTime(System.currentTimeMillis());
        welcome.setSessionToken(rawToken);
        welcome.setRoomId(session.getRoomId());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sessionToken", rawToken);
        extra.put("recoverWindowMs", config.sessionRecoverWindowMs());
        extra.put("heartbeatIntervalMs", config.heartbeatIntervalMs());
        extra.put("heartbeatTimeoutMs", config.heartbeatTimeoutMs());
        extra.put("resumed", resumed);
        welcome.setExtra(extra);
        session.send(welcome);
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
                questionBank, roomExecutor, scheduler, statsPublisher,
                config.questionSeconds(), config.questionsPerMatch(), config.pointsPerCorrect());
        rooms.put(roomId, room);
        room.addPlayer(session);
        sessionRegistry.findByPlayerId(session.getPlayerId()).ifPresent(r -> r.setRoomId(roomId));

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
            session.send(Message.error("You are already in a room. Leave it first, then join another."));
            return;
        }
        String roomId = message.getRoomId() == null ? null : message.getRoomId().trim();
        if (roomId == null || roomId.isBlank()) {
            session.send(Message.error("Enter a room code, or click Join on a live room."));
            return;
        }
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            session.send(Message.error("No live room with code \"" + roomId + "\". Ask the host for the code, or refresh the room list."));
            return;
        }
        String error = room.addPlayerOrError(session);
        if (error != null) {
            session.send(Message.error(error));
            return;
        }
        sessionRegistry.findByPlayerId(session.getPlayerId()).ifPresent(r -> r.setRoomId(roomId));
        session.send(room.roomStateMessage());
        broadcastRoomList();
        LOG.info(() -> session.getPlayerName() + " joined room " + roomId);
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
        sessionRegistry.findByPlayerId(session.getPlayerId()).ifPresent(r -> r.setRoomId(null));
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
        if (session.getRoomId() == null) {
            session.send(Message.error("Join a room first to use chat."));
            return;
        }
        GameRoom room = rooms.get(session.getRoomId());
        if (room == null) {
            session.send(Message.error("You are not in a live room."));
            return;
        }
        room.handleChat(session, message);
    }

    private void sendRoomList(ClientSession session) {
        session.send(roomListMessage());
    }

    private void broadcastRoomList() {
        Message list = roomListMessage();
        for (PlayerSessionRecord record : sessionRegistry.snapshot()) {
            ClientSession transport = record.getTransport();
            if (transport != null && transport.isOpen() && transport.getRoomId() == null) {
                transport.send(list);
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

    private void handlePing(ClientSession session, Message message) {
        Message pong = Message.of(MessageType.PONG);
        pong.setServerTime(System.currentTimeMillis());
        if (message.getServerTime() != null) {
            pong.setExtra(Map.of("clientTime", message.getServerTime()));
        }
        session.send(pong);
    }

    private void requireRegistered(ClientSession session) {
        if (session.getPlayerId() == null) {
            throw new IllegalStateException("Send HELLO or RESUME first");
        }
    }
}
