package trivia.server.nio;

import trivia.server.room.RoomManager;
import trivia.shared.protocol.Message;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central non-blocking TCP game server (Member 04).
 * One selector thread monitors many SocketChannels.
 */
public class NioGameServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(NioGameServer.class.getName());

    private final int port;
    private final RoomManager roomManager;
    private final Map<SocketChannel, ClientSessionImpl> sessions = new ConcurrentHashMap<>();

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running;

    public NioGameServer(int port, RoomManager roomManager) {
        this.port = port;
        this.roomManager = roomManager;
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        LOG.info(() -> "NIO game server listening on TCP port " + port
                + " (selector thread=" + Thread.currentThread().getName() + ")");

        while (running) {
            selector.select();
            if (!running) {
                break;
            }
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) {
                    continue;
                }
                try {
                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "I/O error on key", ex);
                    closeKey(key);
                }
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client == null) {
            return;
        }
        client.configureBlocking(false);
        SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);
        ClientSessionImpl session = new ClientSessionImpl(client, clientKey);
        clientKey.attach(session);
        sessions.put(client, session);
        String remote = String.valueOf(client.getRemoteAddress());
        LOG.info(() -> "Accepted connection from " + remote + " (sessions=" + sessions.size() + ")");
    }

    private void read(SelectionKey key) throws IOException {
        ClientSessionImpl session = (ClientSessionImpl) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = session.getFrameBuffer().buffer();

        int read = channel.read(buffer);
        if (read == -1) {
            disconnect(session, "DISCONNECT");
            return;
        }
        if (read == 0) {
            return;
        }

        List<Message> messages;
        try {
            messages = session.getFrameBuffer().drain();
        } catch (IllegalArgumentException ex) {
            session.send(Message.error(ex.getMessage()));
            disconnect(session, "BAD_FRAME");
            return;
        }

        for (Message message : messages) {
            LOG.fine(() -> "RX " + message.getType() + " from " + session.getPlayerName());
            roomManager.handle(session, message);
        }
    }

    private void write(SelectionKey key) throws IOException {
        ClientSessionImpl session = (ClientSessionImpl) key.attachment();
        session.flushWrites();
    }

    private void disconnect(ClientSessionImpl session, String reason) {
        sessions.remove(session.getChannel());
        roomManager.handleDisconnect(session, reason);
        LOG.info(() -> "Client disconnected (" + reason + "): " + session.getPlayerName());
    }

    private void closeKey(SelectionKey key) {
        Object attachment = key.attachment();
        if (attachment instanceof ClientSessionImpl session) {
            disconnect(session, "ERROR");
        } else {
            try {
                key.channel().close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    public int getPort() {
        return port;
    }

    public int sessionCount() {
        return sessions.size();
    }

    @Override
    public void close() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        for (ClientSessionImpl session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }
}
