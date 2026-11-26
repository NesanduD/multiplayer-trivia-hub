package trivia;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One accepted TCP connection.
 *
 * NIO: a channel is bidirectional and always moves bytes through a buffer.
 * The inbound buffer's capacity is fixed at 4 KB when it is allocated.
 * TCP: the protocol is a byte stream, not a queue of messages, so this class cuts
 * the stream into lines the way BufferedReader.readLine() does. The session id is
 * the client's IP and source port, which is how TCP tells two connections to the
 * same destination port apart.
 * The selector thread is the only thread that reads, writes, and calls interestOps.
 * Room threads may call send() and the synchronized identity methods.
 */
public final class ClientSession {
    /** Fixed buffer capacity. Also the longest command line that is accepted. */
    public static final int MAX_LINE_BYTES = 4096;

    private final SocketChannel channel;
    private final Selector selector;
    private final String sessionId;

    /**
     * Write mode after allocate: position 0, limit = capacity.
     * channel.read and offerBytes append at position. extractLines then flip/compact.
     */
    private final ByteBuffer inbound = ByteBuffer.allocate(MAX_LINE_BYTES);

    /**
     * Race-condition fix: the selector thread polls this queue while a room thread
     * offers replies. A plain list would lose or reorder those updates.
     */
    private final ConcurrentLinkedQueue<ByteBuffer> responseQueue = new ConcurrentLinkedQueue<>();

    /**
     * Close-once flag. compareAndSet is shared by the selector thread and Member 5's
     * monitor so cleanup runs one time. A new AtomicBoolean inside closeQuietly would
     * let both callers succeed.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile SelectionKey key;

    /** Written on each read and read by the idle monitor on another thread. */
    private volatile long lastActiveTime;
    private volatile boolean overLimit;

    /** Server-side identity. Both fields change together inside synchronized methods. */
    private String playerId;
    private String roomId;

    /**
     * Bytes that channel.write did not finish. Only the selector thread touches this.
     * Putting a half-written buffer back on the queue would place it behind newer
     * messages and break TCP's ordered delivery.
     */
    private ByteBuffer pendingWrite;

    public ClientSession(SocketChannel channel, Selector selector) {
        this.channel = channel;
        this.selector = selector;
        this.sessionId = remoteAddress(channel);
        this.lastActiveTime = System.currentTimeMillis();
    }

    /** Stored after register(), so send() can later turn OP_WRITE on from the selector thread. */
    public void attachKey(SelectionKey key) {
        this.key = key;
    }

    public String getSessionId() {
        return sessionId;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public SelectionKey getKey() {
        return key;
    }

    public Selector getSelector() {
        return selector;
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    /** Any successful read or PING counts as activity for the 30 second idle check. */
    public void touch() {
        lastActiveTime = System.currentTimeMillis();
    }

    /**
     * Called by the room code after JOIN succeeds.
     * Secure network programming: this is authentication stored by the server.
     * Later ANSWER and CHAT commands are authorized from these fields, not from a
     * name the client types in the command (that would allow spoofing).
     * synchronized so one thread cannot observe a new playerId with the old roomId.
     */
    public synchronized void bindIdentity(String playerId, String roomId) {
        this.playerId = playerId;
        this.roomId = roomId;
    }

    public synchronized String getPlayerId() {
        return playerId;
    }

    public synchronized String getRoomId() {
        return roomId;
    }

    public synchronized void clearIdentity() {
        playerId = null;
        roomId = null;
    }

    /** Returns true only for the first caller. Everyone else must leave the session alone. */
    public boolean tryMarkClosed() {
        return closed.compareAndSet(false, true);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean isOverLimit() {
        return overLimit;
    }

    /**
     * Queue one UTF-8 line and wake the selector. Any thread may call this.
     * NIO buffers: the bytes are wrapped, then the selector thread writes them.
     * This method does not call interestOps. Only the selector thread may change the key.
     * Order is enqueue, then wakeup(), because select() is waiting for an event and
     * will not notice the queue by itself.
     * A missing newline is added so the client can keep using readLine().
     */
    public void send(String msg) {
        if (msg == null || closed.get()) {
            return;
        }
        String framed = msg.endsWith("\n") ? msg : msg + "\n";
        responseQueue.offer(ByteBuffer.wrap(framed.getBytes(StandardCharsets.UTF_8)));
        try {
            selector.wakeup();
        } catch (ClosedSelectorException e) {
            // stop() has already closed the selector. The queued line will not be sent.
        }
    }

    /**
     * One non-blocking read, then turn the new bytes into whole lines.
     * NIO read slide: a positive count means bytes were placed in the buffer,
     * and -1 means the peer closed. Zero means nothing was ready.
     * A line of exactly 4096 bytes is valid when the next byte is '\n'.
     * One more non-newline byte is over the fixed capacity, so the client is dropped.
     */
    public ReadResult readAvailable() throws IOException {
        List<String> lines = new ArrayList<>();
        if (overLimit) {
            return new ReadResult(lines, false, true);
        }
        // The 4 KB buffer is already full of a line with no newline yet.
        // The next socket byte decides whether that line is legal or too long.
        if (!inbound.hasRemaining()) {
            int extra = readOneMore(lines);
            return new ReadResult(lines, extra == -1, overLimit);
        }
        int n = channel.read(inbound);
        if (n > 0) {
            touch();
            lines.addAll(extractLines());
            if (!overLimit && !inbound.hasRemaining()) {
                int extra = readOneMore(lines);
                return new ReadResult(lines, extra == -1, overLimit);
            }
        }
        return new ReadResult(lines, n == -1, overLimit);
    }

    /**
     * Same framing as readAvailable, but the bytes are supplied by a test instead of a socket.
     * Used to check a command that arrives in two pieces, which is the compact() exercise.
     */
    public List<String> offerBytes(byte[] chunk) {
        List<String> lines = new ArrayList<>();
        if (chunk == null || chunk.length == 0 || overLimit) {
            return lines;
        }
        int offset = 0;
        while (offset < chunk.length && !overLimit) {
            if (!inbound.hasRemaining()) {
                byte next = chunk[offset++];
                if (next == '\n') {
                    lines.add(finishFullLine());
                } else {
                    markOverLimit();
                }
                continue;
            }
            int count = Math.min(inbound.remaining(), chunk.length - offset);
            inbound.put(chunk, offset, count);
            offset += count;
            lines.addAll(extractLines());
        }
        return lines;
    }

    /**
     * Write queued buffers. Selector thread only.
     * Flow control: the lecture's while (hasRemaining) write is correct on a blocking
     * channel, but here one unfinished write must stop. Otherwise this client would
     * block the single selector thread. The unfinished buffer stays in pendingWrite
     * and OP_WRITE is enabled. When the queue is empty, OP_WRITE is cleared so
     * select() does not spin.
     *
     * @return true when every queued byte has been written
     */
    public boolean drainOutbound() throws IOException {
        while (true) {
            if (pendingWrite == null) {
                pendingWrite = responseQueue.poll();
                if (pendingWrite == null) {
                    setWriteInterest(false);
                    return true;
                }
            }
            channel.write(pendingWrite);
            if (pendingWrite.hasRemaining()) {
                setWriteInterest(true);
                return false;
            }
            pendingWrite = null;
        }
    }

    /**
     * Look at one more byte when the 4 KB buffer is full.
     * flip() switches that one-byte buffer from the write the channel just did
     * into the read the program needs.
     * -1 means the client closed. 0 means the deciding byte has not arrived yet.
     */
    private int readOneMore(List<String> lines) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        int n = channel.read(one);
        if (n > 0) {
            touch();
            one.flip();
            if (one.get() == (byte) '\n') {
                lines.add(finishFullLine());
            } else {
                markOverLimit();
            }
        }
        return n;
    }

    /**
     * Buffer lecture, applied to a chat command such as "LOGIN Amal\nPASS":
     * flip() so we read the bytes the channel just wrote, take every finished line,
     * then compact() so the unfinished tail stays at position 0 and more bytes can be appended.
     * Several newlines in one read are all taken before we return to select().
     */
    private List<String> extractLines() {
        List<String> lines = new ArrayList<>();
        inbound.flip();
        int limit = inbound.limit();
        int start = 0;
        for (int i = 0; i < limit; i++) {
            if (inbound.get(i) == (byte) '\n') {
                byte[] raw = new byte[i - start];
                inbound.position(start);
                inbound.get(raw);
                lines.add(decode(raw));
                start = i + 1;
            }
        }
        inbound.position(start);
        inbound.limit(limit);
        inbound.compact();
        return lines;
    }

    /**
     * The buffer holds exactly 4096 command bytes and the following byte was '\n'.
     * flip() then get() reads them. clear() resets position and limit so the next
     * command can reuse the same fixed buffer. clear() does not erase the old bytes;
     * the next put overwrites them.
     */
    private String finishFullLine() {
        inbound.flip();
        byte[] raw = new byte[inbound.remaining()];
        inbound.get(raw);
        inbound.clear();
        return decode(raw);
    }

    /** Drop the stored bytes. A client past the limit must not keep its partial command. */
    private void markOverLimit() {
        overLimit = true;
        inbound.clear();
    }

    /**
     * readLine() removes the '\n' and one trailing '\r', so "PING\r\n" becomes "PING".
     * An embedded '\r' is kept. The bytes are decoded only after the line is complete,
     * so a character split across two reads is not decoded too early.
     * The protocol requires UTF-8. The TCP lecture's getBytes()/readLine() is the line model.
     */
    private static String decode(byte[] raw) {
        int len = raw.length;
        if (len > 0 && raw[len - 1] == '\r') {
            len--;
        }
        return new String(raw, 0, len, StandardCharsets.UTF_8);
    }

    /**
     * OR in OP_WRITE while a buffer is unfinished. AND it out when the queue is empty.
     * Leaving OP_WRITE set makes select() return immediately and burns CPU.
     * interestOps runs only on the selector thread, because that thread owns the key.
     */
    private void setWriteInterest(boolean enable) {
        SelectionKey selectionKey = this.key;
        if (selectionKey == null || !selectionKey.isValid()) {
            return;
        }
        int ops = selectionKey.interestOps();
        int updated = enable
                ? (ops | SelectionKey.OP_WRITE)
                : (ops & ~SelectionKey.OP_WRITE);
        if (updated != ops) {
            selectionKey.interestOps(updated);
        }
    }

    /**
     * TCP: source IP + source port identify this connection.
     * Many clients share destination port 5000. Their source ports keep them separate.
     */
    private static String remoteAddress(SocketChannel channel) {
        try {
            SocketAddress address = channel.getRemoteAddress();
            if (address != null) {
                return address.toString();
            }
        } catch (IOException e) {
            // The channel is not connected yet. The channel text is unique enough for tests.
        }
        return String.valueOf(channel);
    }

    /** What one read produced: finished lines, peer close, or a line past 4 KB. */
    public static final class ReadResult {
        private final List<String> lines;
        private final boolean eof;
        private final boolean overLimit;

        ReadResult(List<String> lines, boolean eof, boolean overLimit) {
            this.lines = lines;
            this.eof = eof;
            this.overLimit = overLimit;
        }

        public List<String> lines() {
            return lines;
        }

        /** True when channel.read returned -1: the client has closed its side. */
        public boolean eof() {
            return eof;
        }

        public boolean overLimit() {
            return overLimit;
        }
    }
}
