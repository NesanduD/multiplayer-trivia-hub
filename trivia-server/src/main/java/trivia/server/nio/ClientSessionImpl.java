package trivia.server.nio;

import trivia.shared.api.ClientSession;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.ProtocolCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NIO-backed client session with an outbound write queue.
 */
public class ClientSessionImpl implements ClientSession {
    private final String connectionId = UUID.randomUUID().toString();
    private final SocketChannel channel;
    private final SelectionKey key;
    private final NioFrameBuffer frameBuffer = new NioFrameBuffer();
    private final Queue<ByteBuffer> pendingWrites = new ArrayDeque<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    private String playerId;
    private String playerName;
    private String roomId;

    public ClientSessionImpl(SocketChannel channel, SelectionKey key) {
        this.channel = channel;
        this.key = key;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public NioFrameBuffer getFrameBuffer() {
        return frameBuffer;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    @Override
    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    @Override
    public String getPlayerName() {
        return playerName;
    }

    @Override
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    @Override
    public synchronized void send(Message message) {
        if (!isOpen()) {
            return;
        }
        byte[] frame = ProtocolCodec.encode(message);
        pendingWrites.add(ByteBuffer.wrap(frame));
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        key.selector().wakeup();
    }

    public synchronized boolean flushWrites() throws IOException {
        while (!pendingWrites.isEmpty()) {
            ByteBuffer buffer = pendingWrites.peek();
            channel.write(buffer);
            if (buffer.hasRemaining()) {
                return false;
            }
            pendingWrites.poll();
        }
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        return true;
    }

    @Override
    public boolean isOpen() {
        return open.get() && channel.isOpen();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            key.cancel();
            channel.close();
        } catch (IOException ignored) {
            // best-effort close
        }
    }
}
