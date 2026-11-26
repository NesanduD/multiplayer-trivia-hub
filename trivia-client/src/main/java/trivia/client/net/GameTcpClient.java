package trivia.client.net;

import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;
import trivia.shared.protocol.ProtocolCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Traditional blocking TCP Socket client (Member 04).
 * Demonstrates connection establishment, framed I/O, heartbeat monitoring, and session resume.
 */
public class GameTcpClient implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(GameTcpClient.class.getName());
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    private final CopyOnWriteArrayList<Consumer<Message>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> errorListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicBoolean readerRunning = new AtomicBoolean(false);
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tcp-client-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tcp-client-reconnect");
        t.setDaemon(true);
        return t;
    });

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private Thread readerThread;
    private volatile String playerId;
    private volatile String playerName;
    private volatile String sessionToken;
    private volatile String currentRoomId;
    private volatile long heartbeatIntervalMs = 5000;
    private volatile long heartbeatTimeoutMs = 20000;
    private volatile long lastInboundMs;
    private volatile boolean autoReconnect = true;
    private volatile long reconnectBaseDelayMs = 500;
    private volatile boolean intentionalDisconnect;
    private volatile boolean closed;
    private ScheduledFuture<?> heartbeatFuture;
    private final String clientId = UUID.randomUUID().toString();
    private volatile String lastHost;
    private volatile int lastPort;

    public void addMessageListener(Consumer<Message> listener) {
        listeners.add(listener);
    }

    public void addErrorListener(Consumer<String> listener) {
        errorListeners.add(listener);
    }

    public void addStateListener(Consumer<ConnectionState> listener) {
        stateListeners.add(listener);
    }

    public ConnectionState getState() {
        return state.get();
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public String getCurrentRoomId() {
        return currentRoomId;
    }

    public long getLastInboundMs() {
        return lastInboundMs;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    public void setReconnectBaseDelayMs(long reconnectBaseDelayMs) {
        this.reconnectBaseDelayMs = Math.max(50L, reconnectBaseDelayMs);
    }

    public synchronized void connect(String host, int port, String playerName) throws IOException {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Player name is required");
        }
        connectInternal(host, port, playerName.trim(), false);
    }

    /**
     * Reconnect and resume a recoverable session using the stored session token.
     */
    public synchronized void resume(String host, int port) throws IOException {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new IOException("No session token to resume");
        }
        connectInternal(host, port, playerName == null ? "Player" : playerName, true);
    }

    private void connectInternal(String host, int port, String playerName, boolean resume) throws IOException {
        if (closed) {
            throw new IllegalStateException("Client has been permanently closed");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Server host is required");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (state.get() != ConnectionState.DISCONNECTED && state.get() != ConnectionState.RECONNECTING) {
            closeSocketOnly(false);
        }
        intentionalDisconnect = false;
        setState(ConnectionState.CONNECTING);
        this.playerName = playerName;
        this.lastHost = host.trim();
        this.lastPort = port;
        if (!resume) {
            this.playerId = null;
            this.currentRoomId = null;
        }
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(this.lastHost, port), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            out = new BufferedOutputStream(socket.getOutputStream());
            in = new BufferedInputStream(socket.getInputStream());
            lastInboundMs = System.currentTimeMillis();
            setState(ConnectionState.CONNECTED);

            if (resume) {
                Message resumeMsg = Message.of(MessageType.RESUME);
                resumeMsg.setSessionToken(sessionToken);
                resumeMsg.setClientId(clientId);
                send(resumeMsg);
            } else {
                Message hello = Message.of(MessageType.HELLO);
                hello.setPlayerName(playerName);
                hello.setClientId(clientId);
                send(hello);
            }

            readerRunning.set(true);
            readerThread = new Thread(this::readLoop, "tcp-client-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        } catch (UnknownHostException ex) {
            setState(ConnectionState.DISCONNECTED);
            throw ex;
        } catch (IOException ex) {
            setState(ConnectionState.DISCONNECTED);
            closeSocketOnly(false);
            throw ex;
        }
    }

    public synchronized void send(Message message) throws IOException {
        if (socket == null || !socket.isConnected() || socket.isClosed()) {
            throw new IOException("Not connected");
        }
        ProtocolCodec.writeFrame(out, message);
        LOG.fine(() -> "TX " + message.getType());
    }

    public void createRoom(String roomName, int maxPlayers) throws IOException {
        Message message = Message.of(MessageType.CREATE_ROOM);
        message.setRoomName(roomName == null ? "" : roomName.trim());
        message.setMaxPlayers(maxPlayers);
        send(message);
    }

    public void joinRoom(String roomId) throws IOException {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("Room ID is required");
        }
        Message message = Message.of(MessageType.JOIN_ROOM);
        message.setRoomId(roomId.trim());
        send(message);
    }

    public void leaveRoom() throws IOException {
        send(Message.of(MessageType.LEAVE_ROOM));
        currentRoomId = null;
    }

    public void listRooms() throws IOException {
        send(Message.of(MessageType.LIST_ROOMS));
    }

    public void startGame(String roomId) throws IOException {
        String targetRoom = (roomId == null || roomId.isBlank()) ? currentRoomId : roomId.trim();
        if (targetRoom == null || targetRoom.isBlank()) {
            throw new IllegalArgumentException("Room ID is required to start game");
        }
        Message message = Message.of(MessageType.START_GAME);
        message.setRoomId(targetRoom);
        send(message);
    }

    public void answer(String roomId, int qIndex, int optionIndex) throws IOException {
        String targetRoom = (roomId == null || roomId.isBlank()) ? currentRoomId : roomId.trim();
        if (targetRoom == null || targetRoom.isBlank()) {
            throw new IllegalArgumentException("Room ID is required to submit answer");
        }
        if (optionIndex < 0) {
            throw new IllegalArgumentException("Option index must be non-negative");
        }
        Message message = Message.of(MessageType.ANSWER);
        message.setRoomId(targetRoom);
        message.setQIndex(qIndex);
        message.setOptionIndex(optionIndex);
        send(message);
    }

    public void chat(String text) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chat message cannot be empty");
        }
        Message message = Message.of(MessageType.CHAT);
        message.setText(text);
        send(message);
    }

    private void startHeartbeat() {
        stopHeartbeat();
        if (heartbeatScheduler.isShutdown()) {
            return;
        }
        long interval = Math.max(500L, heartbeatIntervalMs);
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (state.get() != ConnectionState.REGISTERED) {
                return;
            }
            long now = System.currentTimeMillis();
            if (lastInboundMs > 0 && now - lastInboundMs > heartbeatTimeoutMs) {
                LOG.warning("Server heartbeat timeout detected; closing stale socket");
                closeSocketOnly(false);
                return;
            }
            try {
                Message ping = Message.of(MessageType.PING);
                ping.setServerTime(now);
                send(ping);
            } catch (IOException ex) {
                LOG.fine(() -> "Heartbeat send failed: " + ex.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void readLoop() {
        boolean unexpectedDrop = false;
        try {
            while (readerRunning.get() && socket != null && !socket.isClosed()) {
                Message message = ProtocolCodec.readFrame(in);
                if (message == null) {
                    unexpectedDrop = !intentionalDisconnect && !closed;
                    break;
                }
                lastInboundMs = System.currentTimeMillis();
                if (message.getType() == MessageType.WELCOME) {
                    playerId = message.getPlayerId();
                    currentRoomId = message.getRoomId();
                    if (message.getSessionToken() != null && !message.getSessionToken().isBlank()) {
                        sessionToken = message.getSessionToken();
                    } else if (message.getExtra() != null && message.getExtra().get("sessionToken") != null) {
                        sessionToken = String.valueOf(message.getExtra().get("sessionToken"));
                    }
                    Object interval = message.getExtra() != null ? message.getExtra().get("heartbeatIntervalMs") : null;
                    if (interval instanceof Number number) {
                        heartbeatIntervalMs = number.longValue();
                    }
                    Object timeout = message.getExtra() != null ? message.getExtra().get("heartbeatTimeoutMs") : null;
                    if (timeout instanceof Number number) {
                        heartbeatTimeoutMs = number.longValue();
                    } else {
                        heartbeatTimeoutMs = Math.max(3000L, heartbeatIntervalMs * 3);
                    }
                    setState(ConnectionState.REGISTERED);
                    startHeartbeat();
                } else if (message.getType() == MessageType.ROOM_CREATED || message.getType() == MessageType.ROOM_STATE) {
                    if (message.getRoomId() != null) {
                        currentRoomId = message.getRoomId();
                    }
                } else if (message.getType() == MessageType.PLAYER_LEFT) {
                    if (playerId != null && playerId.equals(message.getPlayerId())) {
                        currentRoomId = null;
                    }
                } else if (message.getType() == MessageType.ERROR && state.get() != ConnectionState.REGISTERED) {
                    sessionToken = null;
                }
                for (Consumer<Message> listener : listeners) {
                    listener.accept(message);
                }
            }
        } catch (SocketTimeoutException ex) {
            unexpectedDrop = !intentionalDisconnect && !closed;
            if (!shouldAutoReconnect()) {
                notifyError("Socket timed out: " + ex.getMessage());
            }
        } catch (IOException ex) {
            if (readerRunning.get() && !intentionalDisconnect && !closed
                    && state.get() != ConnectionState.DISCONNECTED) {
                unexpectedDrop = true;
                if (!shouldAutoReconnect()) {
                    notifyError("Connection lost: " + ex.getMessage());
                }
            }
        } finally {
            readerRunning.set(false);
            stopHeartbeat();
            closeSocketOnly(false);
            if (unexpectedDrop && shouldAutoReconnect()) {
                scheduleReconnect();
            } else if (state.get() != ConnectionState.RECONNECTING) {
                setState(ConnectionState.DISCONNECTED);
            }
        }
    }

    private boolean shouldAutoReconnect() {
        return autoReconnect && !intentionalDisconnect && !closed
                && sessionToken != null && !sessionToken.isBlank()
                && lastHost != null && lastPort > 0;
    }

    private void scheduleReconnect() {
        if (reconnectExecutor.isShutdown()) {
            setState(ConnectionState.DISCONNECTED);
            return;
        }
        setState(ConnectionState.RECONNECTING);
        reconnectExecutor.submit(() -> {
            for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
                if (intentionalDisconnect || closed) {
                    setState(ConnectionState.DISCONNECTED);
                    return;
                }
                long delay = reconnectBaseDelayMs * (1L << (attempt - 1));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setState(ConnectionState.DISCONNECTED);
                    return;
                }
                if (intentionalDisconnect || closed) {
                    setState(ConnectionState.DISCONNECTED);
                    return;
                }
                try {
                    LOG.info("Attempting session resume (" + attempt + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                    resume(lastHost, lastPort);
                    return;
                } catch (IOException ex) {
                    LOG.warning("Reconnect attempt " + attempt + " failed: " + ex.getMessage());
                }
            }
            setState(ConnectionState.DISCONNECTED);
            notifyError("Connection lost and session recovery failed after " + MAX_RECONNECT_ATTEMPTS + " attempts.");
        });
    }

    private void setState(ConnectionState next) {
        state.set(next);
        for (Consumer<ConnectionState> listener : stateListeners) {
            listener.accept(next);
        }
    }

    private void notifyError(String error) {
        LOG.log(Level.WARNING, error);
        for (Consumer<String> listener : errorListeners) {
            listener.accept(error);
        }
    }

    private synchronized void closeSocketOnly(boolean sendGoodbye) {
        readerRunning.set(false);
        stopHeartbeat();
        try {
            if (sendGoodbye && out != null && socket != null && socket.isConnected() && !socket.isClosed()
                    && state.get() != ConnectionState.DISCONNECTED) {
                Message goodbye = Message.of(MessageType.GOODBYE);
                ProtocolCodec.writeFrame(out, goodbye);
            }
        } catch (IOException ignored) {
            // ignore during shutdown
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        socket = null;
        in = null;
        out = null;
    }

    /**
     * Intentionally disconnect the active TCP session without shutting down client executors,
     * allowing subsequent calls to {@link #connect(String, int, String)} or {@link #resume(String, int)}.
     */
    public synchronized void disconnect() {
        intentionalDisconnect = true;
        closeSocketOnly(true);
        setState(ConnectionState.DISCONNECTED);
    }

    @Override
    public void close() {
        closed = true;
        disconnect();
        heartbeatScheduler.shutdownNow();
        reconnectExecutor.shutdownNow();
    }

    public String getLastHost() {
        return lastHost;
    }

    public int getLastPort() {
        return lastPort;
    }
}
