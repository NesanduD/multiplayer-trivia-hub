package trivia.server.room;

import trivia.server.game.QuestionBank;
import trivia.server.game.QuestionTimer;
import trivia.server.game.ScoreBoard;
import trivia.shared.api.ClientSession;
import trivia.shared.api.MatchStatsPublisher;
import trivia.shared.dto.MatchResult;
import trivia.shared.model.Question;
import trivia.shared.model.ScoreEntry;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GameRoom {
    private static final Logger LOG = Logger.getLogger(GameRoom.class.getName());
    public static final int QUESTION_SECONDS = 15;
    public static final int QUESTIONS_PER_MATCH = 5;
    public static final int POINTS_PER_CORRECT = 10;

    private final String roomId;
    private final String roomName;
    private final int maxPlayers;
    private final String hostPlayerId;
    private final Map<String, ClientSession> players = new ConcurrentHashMap<>();
    private final Map<String, Boolean> softDisconnected = new ConcurrentHashMap<>();
    private final ScoreBoard scoreBoard = new ScoreBoard();
    private final QuestionBank questionBank;
    private final ExecutorService roomExecutor;
    private final QuestionTimer questionTimer;
    private final MatchStatsPublisher statsPublisher;
    private final int questionSeconds;
    private final int questionsPerMatch;
    private final int pointsPerCorrect;
    private final Object lock = new Object();

    private volatile String status = "WAITING";
    private List<Question> matchQuestions = List.of();
    private int currentIndex = -1;
    private long endsAt;
    private final Map<String, Integer> answersThisRound = new ConcurrentHashMap<>();
    private Future<Boolean> timeoutFuture;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GameRoom(String roomId,
                    String roomName,
                    int maxPlayers,
                    String hostPlayerId,
                    QuestionBank questionBank,
                    ExecutorService roomExecutor,
                    ScheduledExecutorService scheduler,
                    MatchStatsPublisher statsPublisher) {
        this(roomId, roomName, maxPlayers, hostPlayerId, questionBank, roomExecutor, scheduler,
                statsPublisher, QUESTION_SECONDS, QUESTIONS_PER_MATCH, POINTS_PER_CORRECT);
    }

    public GameRoom(String roomId,
                    String roomName,
                    int maxPlayers,
                    String hostPlayerId,
                    QuestionBank questionBank,
                    ExecutorService roomExecutor,
                    ScheduledExecutorService scheduler,
                    MatchStatsPublisher statsPublisher,
                    int questionSeconds,
                    int questionsPerMatch,
                    int pointsPerCorrect) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.maxPlayers = maxPlayers;
        this.hostPlayerId = hostPlayerId;
        this.questionBank = questionBank;
        this.roomExecutor = roomExecutor;
        this.questionTimer = new QuestionTimer(scheduler);
        this.statsPublisher = statsPublisher;
        this.questionSeconds = questionSeconds;
        this.questionsPerMatch = questionsPerMatch;
        this.pointsPerCorrect = pointsPerCorrect;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public String getStatus() {
        return status;
    }

    public int playerCount() {
        return players.size();
    }

    public boolean contains(String playerId) {
        return players.containsKey(playerId);
    }

    public boolean addPlayer(ClientSession session) {
        return addPlayerOrError(session) == null;
    }

    /**
     * @return null on success, otherwise a user-friendly error message
     */
    public String addPlayerOrError(ClientSession session) {
        synchronized (lock) {
            if (!"WAITING".equals(status)) {
                return "This room’s game already started. Wait for it to finish, or create another room.";
            }
            if (players.size() >= maxPlayers) {
                return "Room is full (" + maxPlayers + " players).";
            }
            if (players.containsKey(session.getPlayerId())) {
                return "You are already in this room.";
            }
            players.put(session.getPlayerId(), session);
            session.setRoomId(roomId);
            scoreBoard.register(session.getPlayerId(), session.getPlayerName());
            broadcastRoomState();
            Message joinChat = Message.of(MessageType.CHAT);
            joinChat.setRoomId(roomId);
            joinChat.setFrom("System");
            joinChat.setText(session.getPlayerName() + " joined the room");
            broadcast(joinChat);
            return null;
        }
    }

    /**
     * Keep seat, scores, and answer locks while the transport is down (recoverable).
     */
    public void softDisconnect(String playerId, String reason) {
        synchronized (lock) {
            ClientSession session = players.get(playerId);
            if (session == null || softDisconnected.containsKey(playerId)) {
                return;
            }
            softDisconnected.put(playerId, Boolean.TRUE);
            String name = session.getPlayerName() == null ? playerId : session.getPlayerName();
            Message left = Message.of(MessageType.PLAYER_LEFT);
            left.setPlayerId(playerId);
            left.setFrom(name);
            left.setReason(reason == null ? "DISCONNECTED" : reason);
            left.setRoomId(roomId);
            left.setExtra(Map.of("recoverable", true));
            broadcast(left);
            Message chat = Message.of(MessageType.CHAT);
            chat.setRoomId(roomId);
            chat.setFrom("System");
            chat.setText(name + " disconnected — may reconnect");
            broadcast(chat);
            broadcastRoomState();
        }
    }

    /**
     * Re-attach a resumed transport to an existing seat without resetting scores/answers.
     */
    public void rebindPlayer(ClientSession session) {
        synchronized (lock) {
            String playerId = session.getPlayerId();
            if (playerId == null || !players.containsKey(playerId)) {
                throw new IllegalStateException("No recoverable seat in this room");
            }
            players.put(playerId, session);
            softDisconnected.remove(playerId);
            session.setRoomId(roomId);
            Message chat = Message.of(MessageType.CHAT);
            chat.setRoomId(roomId);
            chat.setFrom("System");
            chat.setText(session.getPlayerName() + " reconnected");
            broadcast(chat);
            session.send(roomStateMessage());
            session.send(scoreUpdateMessage());
            if ("PLAYING".equals(status) && currentIndex >= 0 && currentIndex < matchQuestions.size()) {
                Question question = matchQuestions.get(currentIndex);
                Message q = Message.of(MessageType.QUESTION);
                q.setRoomId(roomId);
                q.setQIndex(currentIndex);
                q.setText(question.getText());
                q.setOptions(question.getOptions());
                q.setEndsAt(endsAt);
                session.send(q);
            }
            broadcastRoomState();
        }
    }

    public void removePlayer(String playerId, String reason) {
        synchronized (lock) {
            ClientSession removed = players.remove(playerId);
            softDisconnected.remove(playerId);
            if (removed == null) {
                return;
            }
            String leftName = removed.getPlayerName() == null ? playerId : removed.getPlayerName();
            removed.setRoomId(null);
            scoreBoard.remove(playerId);
            Message left = Message.of(MessageType.PLAYER_LEFT);
            left.setPlayerId(playerId);
            left.setFrom(leftName);
            left.setReason(reason);
            left.setRoomId(roomId);
            broadcast(left);
            Message leftChat = Message.of(MessageType.CHAT);
            leftChat.setRoomId(roomId);
            leftChat.setFrom("System");
            leftChat.setText(leftName + " left the room");
            broadcast(leftChat);
            broadcastRoomState();

            if (players.isEmpty()) {
                cancelTimeout();
                status = "CLOSED";
                running.set(false);
            }
        }
    }

    public void startGame(String requesterId) {
        synchronized (lock) {
            if (!hostPlayerId.equals(requesterId)) {
                sendError(requesterId, "Only the room host can start the game");
                return;
            }
            if (!"WAITING".equals(status)) {
                sendError(requesterId, "Game already started");
                return;
            }
            if (players.size() < 1) {
                sendError(requesterId, "Need at least one player");
                return;
            }
            matchQuestions = questionBank.pick(questionsPerMatch);
            currentIndex = -1;
            status = "PLAYING";
            running.set(true);
            LOG.info(() -> "Room " + roomId + " starting on thread " + Thread.currentThread().getName());
            roomExecutor.execute(this::advanceQuestion);
        }
    }

    public void handleAnswer(ClientSession session, Message message) {
        synchronized (lock) {
            if (!"PLAYING".equals(status)) {
                return;
            }
            if (softDisconnected.containsKey(session.getPlayerId())) {
                return;
            }
            if (message.getQIndex() == null || message.getQIndex() != currentIndex) {
                return;
            }
            if (answersThisRound.containsKey(session.getPlayerId())) {
                return;
            }
            int option = message.getOptionIndex() == null ? -1 : message.getOptionIndex();
            answersThisRound.put(session.getPlayerId(), option);

            Question question = matchQuestions.get(currentIndex);
            boolean correct = option == question.getCorrectIndex();
            int points = 0;
            if (correct) {
                points = pointsPerCorrect;
                scoreBoard.addPoints(session.getPlayerId(), points);
            }

            Message result = Message.of(MessageType.ANSWER_RESULT);
            result.setRoomId(roomId);
            result.setQIndex(currentIndex);
            result.setPlayerId(session.getPlayerId());
            result.setCorrect(correct);
            result.setPoints(points);
            result.setScores(scoreBoard.asMaps());
            session.send(result);

            broadcastScores();

            if (answersThisRound.size() >= players.size()) {
                cancelTimeout();
                roomExecutor.execute(this::advanceQuestion);
            }
        }
    }

    public void handleChat(ClientSession session, Message message) {
        String text = message.getText() == null ? "" : message.getText().trim();
        if (text.isEmpty()) {
            session.send(Message.error("Chat message cannot be empty"));
            return;
        }
        if (text.length() > 300) {
            text = text.substring(0, 300);
        }
        Message chat = Message.of(MessageType.CHAT);
        chat.setRoomId(roomId);
        chat.setFrom(session.getPlayerName());
        chat.setText(text);
        chat.setServerTime(System.currentTimeMillis());
        broadcast(chat);
    }

    public Message roomStateMessage() {
        Message state = Message.of(MessageType.ROOM_STATE);
        state.setRoomId(roomId);
        state.setRoomName(roomName);
        state.setStatus(status);
        state.setMaxPlayers(maxPlayers);
        List<String> names = new ArrayList<>();
        for (ClientSession session : players.values()) {
            names.add(session.getPlayerName());
        }
        state.setPlayers(names);
        return state;
    }

    public Map<String, Object> summary() {
        List<String> names = new ArrayList<>();
        for (ClientSession session : players.values()) {
            names.add(session.getPlayerName());
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("roomId", roomId);
        map.put("roomName", roomName);
        map.put("maxPlayers", maxPlayers);
        map.put("status", status);
        map.put("players", players.size());
        map.put("playerNames", names);
        map.put("joinable", "WAITING".equals(status) && players.size() < maxPlayers);
        return map;
    }

    private void advanceQuestion() {
        synchronized (lock) {
            if (!running.get() || players.isEmpty()) {
                return;
            }
            currentIndex++;
            if (currentIndex >= matchQuestions.size()) {
                finishMatch();
                return;
            }
            answersThisRound.clear();
            Question question = matchQuestions.get(currentIndex);
            endsAt = System.currentTimeMillis() + questionSeconds * 1000L;

            Message q = Message.of(MessageType.QUESTION);
            q.setRoomId(roomId);
            q.setQIndex(currentIndex);
            q.setText(question.getText());
            q.setOptions(question.getOptions());
            q.setEndsAt(endsAt);
            broadcast(q);
            broadcastScores();

            final int expectedIndex = currentIndex;
            timeoutFuture = questionTimer.scheduleTimeout(questionSeconds * 1000L, () -> {
                synchronized (lock) {
                    if (expectedIndex == currentIndex && "PLAYING".equals(status)) {
                        LOG.info(() -> "Question timeout in room " + roomId + " q=" + expectedIndex);
                        roomExecutor.execute(this::advanceQuestion);
                    }
                }
                return Boolean.TRUE;
            });
        }
    }

    private void finishMatch() {
        status = "FINISHED";
        running.set(false);
        cancelTimeout();

        List<ScoreEntry> finalScores = scoreBoard.snapshot();
        Message over = Message.of(MessageType.GAME_OVER);
        over.setRoomId(roomId);
        over.setScores(scoreBoard.asMaps());
        broadcast(over);

        MatchResult result = new MatchResult(
                UUID.randomUUID().toString(),
                roomId,
                roomName,
                System.currentTimeMillis(),
                finalScores);
        try {
            statsPublisher.publish(result);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to publish match stats", ex);
        }

        // Reset for another round
        status = "WAITING";
        currentIndex = -1;
        answersThisRound.clear();
        for (ScoreEntry entry : scoreBoard.snapshot()) {
            scoreBoard.remove(entry.getPlayerId());
        }
        for (ClientSession session : players.values()) {
            scoreBoard.register(session.getPlayerId(), session.getPlayerName());
        }
        broadcastRoomState();
    }

    private void cancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    private void broadcastRoomState() {
        broadcast(roomStateMessage());
    }

    private void broadcastScores() {
        broadcast(scoreUpdateMessage());
    }

    private Message scoreUpdateMessage() {
        Message scores = Message.of(MessageType.SCORE_UPDATE);
        scores.setRoomId(roomId);
        scores.setScores(scoreBoard.asMaps());
        return scores;
    }

    private void broadcast(Message message) {
        for (Map.Entry<String, ClientSession> entry : players.entrySet()) {
            if (softDisconnected.containsKey(entry.getKey())) {
                continue;
            }
            ClientSession session = entry.getValue();
            if (session != null && session.isOpen()) {
                session.send(message);
            }
        }
    }

    private void sendError(String playerId, String error) {
        ClientSession session = players.get(playerId);
        if (session != null) {
            session.send(Message.error(error));
        }
    }
}
