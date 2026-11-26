package trivia;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Member 1's TCP server. One thread accepts and reads every client.
 *
 * NIO: this is the lecture echo server. A non-blocking ServerSocketChannel is registered
 * for OP_ACCEPT. select() waits until a channel is ready, then the loop handles
 * isAcceptable, isReadable, and isWritable. One thread monitors every channel.
 * TCP: the service is named by its port. Clients reach it by the server IP plus that port.
 * Closing a socket frees its buffers and completes connection termination.
 * Multithreading: PING is answered here, on the selector thread, because it is instant
 * (the echo server also writes during the read). Every other command is submitted to a
 * fixed thread pool so one slow room cannot block the other clients.
 */
public final class GameServer implements Runnable {
    /** Lecture echo server and the trivia protocol both use port 5000. */
    public static final int DEFAULT_PORT = 5000;

    private final int port;
    private final Sessions sessions;
    private final ExecutorService commandExecutor;
    private final InboundHandler handler;
    private final SessionDisposer disposer;

    /** Two threads (selector and shutdown hook) must agree on whether the loop is alive. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Written by the selector thread and read by stop() on the shutdown-hook thread. */
    private volatile ServerSocketChannel serverChannel;
    private volatile Selector selector;
    private volatile int boundPort;

    public GameServer(int port,
                      Sessions sessions,
                      ExecutorService commandExecutor,
                      InboundHandler handler,
                      SessionDisposer disposer) {
        this.port = port;
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
    }

    /** Port the server actually bound. Tests use port 0 and then read this value. */
    public int getPort() {
        return boundPort;
    }

    /** Lets ServerMain run the server as a task: new Thread(gameServer).start(). */
    @Override
    public void run() {
        try {
            start();
        } catch (IOException e) {
            // TCP lecture: a network failure is reported. It must not crash the whole program.
            System.out.println("Server error: " + e.getMessage());
        }
    }

    /**
     * Binds the listening socket and blocks in the selector loop.
     * TCP socket flow: socket, bind, listen, then accept inside the loop.
     * NIO: configureBlocking(false) before bind, then register OP_ACCEPT.
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            // Bind with no host, same as the echo-server slide and new ServerSocket(port):
            // the service is reachable on the machine's IP addresses at this port.
            serverChannel.bind(new InetSocketAddress(port));
            boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Server started on port " + boundPort);
            loop();
        } finally {
            // TCP: always close, so buffers and ports are not left allocated.
            shutdown();
            running.set(false);
        }
    }

    /**
     * Called from the JVM shutdown hook, which is a different thread from the selector.
     * select() is blocked waiting for an event. Closing the listening socket and calling
     * wakeup() makes that wait return so the loop can exit.
     */
    public void stop() {
        running.set(false);
        ServerSocketChannel channel = this.serverChannel;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                System.out.println("Error while closing server socket");
            }
        }
        Selector current = this.selector;
        if (current != null) {
            current.wakeup();
        }
    }

    /**
     * NIO echo-server main loop.
     * select() blocks until a channel is ready or another thread calls wakeup().
     * iterator.remove() is required, or the same key is processed again forever.
     * After the ready keys, every session is drained because a queued reply does not
     * by itself make the key writable. wakeup() only unblocks select().
     */
    private void loop() throws IOException {
        while (running.get()) {
            try {
                selector.select();
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
                System.out.println("Error while waiting for channels");
                break;
            }
            if (!running.get()) {
                break;
            }
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();
                if (!key.isValid()) {
                    continue;
                }
                try {
                    if (key.isAcceptable()) {
                        accept(key);
                    } else {
                        if (key.isReadable()) {
                            read(key);
                        }
                        // The processing-keys slide: the channel can take more bytes.
                        if (key.isValid() && key.isWritable()) {
                            drainKey(key);
                        }
                    }
                } catch (CancelledKeyException | IOException e) {
                    // One broken client must not stop the server. Close that channel only.
                    closeFromKey(key);
                }
            }
            drainAll();
        }
    }

    /**
     * NIO accept branch. Non-blocking accept() can return null, so that case is skipped.
     * The new channel is non-blocking and registered for OP_READ only.
     * The session is added to the registry after its key exists, so another thread
     * cannot send on a session the selector does not know how to write yet.
     */
    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = server.accept();
        if (clientChannel == null) {
            return;
        }
        clientChannel.configureBlocking(false);
        ClientSession session = new ClientSession(clientChannel, selector);
        SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ, session);
        session.attachKey(clientKey);
        sessions.add(session);
        System.out.println("Connected to client: " + clientChannel.getRemoteAddress());
    }

    /**
     * NIO read branch. read() == -1 means the client finished its side of the TCP close,
     * so we close our side too and avoid a half-open connection.
     * A line past 4 KB is dropped. That fixed capacity is the DoS limit: one client
     * cannot make the server grow memory without bound.
     */
    private void read(SelectionKey key) throws IOException {
        ClientSession session = (ClientSession) key.attachment();
        if (session == null || session.isClosed()) {
            return;
        }
        ClientSession.ReadResult result = session.readAvailable();
        for (String line : result.lines()) {
            dispatch(session, line);
        }
        if (result.overLimit()) {
            System.out.println("Closing client: line too long");
            closeSession(session);
            return;
        }
        if (result.eof()) {
            System.out.println("Connection closed by client: " + session.getSessionId());
            closeSession(session);
        }
    }

    /**
     * PING is handled like the echo: read it and queue PONG on this same thread.
     * It never enters a room.
     * Every other line is executor.submit. The multithreading lecture shows that a long
     * task on the only thread blocks every other client, and that a fixed pool queues
     * the extra tasks instead of creating one thread per command.
     * Authorization is not done here. The handler must use the player stored on the session.
     */
    private void dispatch(ClientSession session, String line) {
        if (line.isEmpty()) {
            return;
        }
        session.touch();
        if ("PING".equals(line)) {
            session.send("PONG\n");
            return;
        }
        try {
            commandExecutor.submit(() -> {
                try {
                    handler.onCommand(session, line);
                } catch (RuntimeException e) {
                    session.send("ERROR:Internal error\n");
                }
            });
        } catch (RejectedExecutionException e) {
            // The pool has been shut down. Tell this client instead of killing the loop.
            session.send("ERROR:Internal error\n");
        }
    }

    /** OP_WRITE was selected, so try the queued bytes for this one channel. */
    private void drainKey(SelectionKey key) {
        Object attachment = key.attachment();
        if (attachment instanceof ClientSession session) {
            drainSession(session);
        }
    }

    /**
     * Also drain sessions that were woken by send() but are not in selectedKeys.
     * A copy is taken first so a close during the write cannot break the iteration.
     */
    private void drainAll() {
        List<ClientSession> snapshot = new ArrayList<>();
        for (ClientSession session : sessions.all()) {
            snapshot.add(session);
        }
        for (ClientSession session : snapshot) {
            if (!session.isClosed()) {
                drainSession(session);
            }
        }
    }

    private void drainSession(ClientSession session) {
        try {
            session.drainOutbound();
        } catch (CancelledKeyException | IOException e) {
            closeSession(session);
        }
    }

    private void closeFromKey(SelectionKey key) {
        Object attachment = key.attachment();
        if (attachment instanceof ClientSession session) {
            closeSession(session);
        }
    }

    /**
     * Closes one client once. tryMarkClosed() is the ticket-booking rule from the
     * multithreading lecture: the selector and the idle monitor can both notice the
     * same dead socket, and only the first caller may clean it up.
     * The channel close and the registry removal stay on this thread.
     * Leaving the room is submitted to the pool so a room lock cannot stall select().
     */
    private void closeSession(ClientSession session) {
        if (session == null || !session.tryMarkClosed()) {
            return;
        }
        SelectionKey selectionKey = session.getKey();
        if (selectionKey != null) {
            selectionKey.cancel();
        }
        try {
            session.getChannel().close();
        } catch (IOException e) {
            System.out.println("Error while closing client socket");
        }
        sessions.remove(session);
        try {
            commandExecutor.submit(() -> runDisposer(session));
        } catch (RejectedExecutionException e) {
            runDisposer(session);
        }
    }

    private void runDisposer(ClientSession session) {
        try {
            disposer.dispose(session);
        } catch (RuntimeException e) {
            System.out.println("Session cleanup error: " + e.getMessage());
        }
    }

    /** Close every client, then the selector, then the listening socket. */
    private void shutdown() {
        List<ClientSession> snapshot = new ArrayList<>();
        for (ClientSession session : sessions.all()) {
            snapshot.add(session);
        }
        for (ClientSession session : snapshot) {
            closeSession(session);
        }
        Selector current = this.selector;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                System.out.println("Error while closing selector");
            }
        }
        ServerSocketChannel channel = this.serverChannel;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                System.out.println("Error while closing server socket");
            }
        }
    }
}
