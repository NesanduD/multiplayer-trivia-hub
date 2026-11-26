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
 * Traditional blocking TCP Socket client (Member 01).
 * Demonstrates connection establishment, framed I/O, heartbeat, and session resume.
 */
public class GameTcpClient implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(GameTcpClient.class.getName());

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

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private Thread readerThread;
    private String playerId;
    private String playerName;
    private String sessionToken;
    private long heartbeatIntervalMs = 5000;
    private ScheduledFuture<?> heartbeatFuture;
    private final String clientId = UUID.randomUUID().toString();
    private String lastHost;
    private int lastPort;

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

    public synchronized void connect(String host, int port, String playerName) throws IOException {
        connectInternal(host, port, playerName, false);
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
        if (state.get() != ConnectionState.DISCONNECTED) {
            closeSocketOnly(false);
        }
        setState(ConnectionState.CONNECTING);
        this.playerName = playerName;
        this.lastHost = host;
        this.lastPort = port;
        if (!resume) {
            this.playerId = null;
        }
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            out = new BufferedOutputStream(socket.getOutputStream());
            in = new BufferedInputStream(socket.getInputStream());
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
        message.setRoomName(roomName);
        message.setMaxPlayers(maxPlayers);
        send(message);
    }

    public void joinRoom(String roomId) throws IOException {
        Message message = Message.of(MessageType.JOIN_ROOM);
        message.setRoomId(roomId);
        send(message);
    }

    public void leaveRoom() throws IOException {
        send(Message.of(MessageType.LEAVE_ROOM));
    }

    public void listRooms() throws IOException {
        send(Message.of(MessageType.LIST_ROOMS));
    }

    public void startGame(String roomId) throws IOException {
        Message message = Message.of(MessageType.START_GAME);
        message.setRoomId(roomId);
        send(message);
    }

    public void answer(String roomId, int qIndex, int optionIndex) throws IOException {
        Message message = Message.of(MessageType.ANSWER);
        message.setRoomId(roomId);
        message.setQIndex(qIndex);
        message.setOptionIndex(optionIndex);
        send(message);
    }

    public void chat(String text) throws IOException {
        Message message = Message.of(MessageType.CHAT);
        message.setText(text);
        send(message);
    }

    private void startHeartbeat() {
        stopHeartbeat();
        long interval = Math.max(500L, heartbeatIntervalMs);
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                Message ping = Message.of(MessageType.PING);
                ping.setServerTime(System.currentTimeMillis());
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
        try {
            while (readerRunning.get() && socket != null && !socket.isClosed()) {
                Message message = ProtocolCodec.readFrame(in);
                if (message == null) {
                    break;
                }
                if (message.getType() == MessageType.WELCOME) {
                    playerId = message.getPlayerId();
                    if (message.getSessionToken() != null && !message.getSessionToken().isBlank()) {
                        sessionToken = message.getSessionToken();
                    } else if (message.getExtra() != null && message.getExtra().get("sessionToken") != null) {
                        sessionToken = String.valueOf(message.getExtra().get("sessionToken"));
                    }
                    Object interval = message.getExtra() != null ? message.getExtra().get("heartbeatIntervalMs") : null;
                    if (interval instanceof Number number) {
                        heartbeatIntervalMs = number.longValue();
                    }
                    setState(ConnectionState.REGISTERED);
                    startHeartbeat();
                }
                for (Consumer<Message> listener : listeners) {
                    listener.accept(message);
                }
            }
        } catch (SocketTimeoutException ex) {
            notifyError("Socket timed out: " + ex.getMessage());
        } catch (IOException ex) {
            if (readerRunning.get() && state.get() != ConnectionState.DISCONNECTED) {
                notifyError("Connection lost: " + ex.getMessage());
            }
        } finally {
            readerRunning.set(false);
            stopHeartbeat();
            setState(ConnectionState.DISCONNECTED);
            closeSocketOnly(false);
        }
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

    @Override
    public void close() {
        closeSocketOnly(true);
        setState(ConnectionState.DISCONNECTED);
        heartbeatScheduler.shutdownNow();
    }

    public String getLastHost() {
        return lastHost;
    }

    public int getLastPort() {
        return lastPort;
    }
}
