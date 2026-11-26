package trivia.server.ws;

import trivia.server.room.RoomManager;
import trivia.shared.protocol.Message;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Accepts browser WebSocket connections and routes messages through RoomManager.
 */
public class WebSocketGameServer implements AutoCloseable, Runnable {
    private static final Logger LOG = Logger.getLogger(WebSocketGameServer.class.getName());

    private final int port;
    private final RoomManager roomManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService clients = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ws-client");
        t.setDaemon(true);
        return t;
    });

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public WebSocketGameServer(int port, RoomManager roomManager) {
        this.port = port;
        this.roomManager = roomManager;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running.set(true);
        acceptThread = new Thread(this, "ws-acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOG.info(() -> "WebSocket game bridge listening on ws://localhost:" + port);
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                clients.execute(() -> handleClient(socket));
            } catch (IOException ex) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "WebSocket accept failed", ex);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        WebSocketSession session = null;
        try {
            session = new WebSocketSession(socket);
            if (!session.performHandshake()) {
                socket.close();
                return;
            }
            WebSocketSession active = session;
            session.setHandler(new WebSocketSession.MessageHandler() {
                @Override
                public void onMessage(WebSocketSession s, Message message) {
                    roomManager.handle(s, message);
                }

                @Override
                public void onDisconnect(WebSocketSession s, String reason) {
                    roomManager.handleDisconnect(s, reason);
                }
            });
            LOG.info(() -> "WebSocket client connected from " + socket.getRemoteSocketAddress());
            active.run();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "WebSocket client error", ex);
            if (session != null) {
                roomManager.handleDisconnect(session, "ERROR");
            }
        }
    }

    public int getPort() {
        return port;
    }

    @Override
    public void close() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        clients.shutdownNow();
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }
}
