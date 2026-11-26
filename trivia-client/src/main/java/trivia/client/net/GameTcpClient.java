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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Traditional blocking TCP Socket client (Member 01).
 * Demonstrates connection establishment, framed I/O, and graceful termination.
 */
public class GameTcpClient implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(GameTcpClient.class.getName());

    private final CopyOnWriteArrayList<Consumer<Message>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> errorListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicBoolean readerRunning = new AtomicBoolean(false);

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private Thread readerThread;
    private String playerId;
    private String playerName;
    private final String clientId = UUID.randomUUID().toString();

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

    public synchronized void connect(String host, int port, String playerName) throws IOException {
        if (state.get() != ConnectionState.DISCONNECTED) {
            closeSocketOnly();
        }
        setState(ConnectionState.CONNECTING);
        this.playerName = playerName;
        this.playerId = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            out = new BufferedOutputStream(socket.getOutputStream());
            in = new BufferedInputStream(socket.getInputStream());
            setState(ConnectionState.CONNECTED);

            Message hello = Message.of(MessageType.HELLO);
            hello.setPlayerName(playerName);
            hello.setClientId(clientId);
            send(hello);

            readerRunning.set(true);
            readerThread = new Thread(this::readLoop, "tcp-client-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        } catch (UnknownHostException ex) {
            setState(ConnectionState.DISCONNECTED);
            throw ex;
        } catch (IOException ex) {
            setState(ConnectionState.DISCONNECTED);
            closeSocketOnly();
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

    private void readLoop() {
        try {
            while (readerRunning.get() && socket != null && !socket.isClosed()) {
                Message message = ProtocolCodec.readFrame(in);
                if (message == null) {
                    break;
                }
                if (message.getType() == MessageType.WELCOME) {
                    playerId = message.getPlayerId();
                    setState(ConnectionState.REGISTERED);
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
            setState(ConnectionState.DISCONNECTED);
            closeSocketOnly();
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

    private synchronized void closeSocketOnly() {
        readerRunning.set(false);
        try {
            if (out != null && socket != null && socket.isConnected() && !socket.isClosed()
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
        closeSocketOnly();
        setState(ConnectionState.DISCONNECTED);
    }
}
