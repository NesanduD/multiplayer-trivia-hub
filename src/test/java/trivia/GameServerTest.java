package trivia;

import org.junit.jupiter.api.Timeout;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live TCP checks against the echo-server loop.
 * Port 0 asks the OS for a free port so these tests do not fight over port 5000.
 * The handler stands in for Member 2's room code.
 */
@Timeout(15)
class GameServerTest {

    /** PING is the echo path: answered on the selector thread, and the room handler is not called. */
    @Test
    void pingAndCrlfPingReturnPongWithoutCallingTheHandler() throws Exception {
        List<String> commands = Collections.synchronizedList(new ArrayList<>());
        try (RunningServer server = RunningServer.start((session, line) -> commands.add(line))) {
            try (Socket socket = server.connect()) {
                BufferedReader reader = reader(socket);
                OutputStream out = socket.getOutputStream();
                out.write("PING\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertEquals("PONG", reader.readLine());

                out.write("PING\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertEquals("PONG", reader.readLine());
            }
            Thread.sleep(200);
            assertTrue(commands.isEmpty());
        }
    }

    /** TCP can deliver "PI" and later "NG\\n". compact() must wait. "PI" alone is not a command. */
    @Test
    void pingSplitAcrossWritesIsOneCommand() throws Exception {
        List<String> commands = Collections.synchronizedList(new ArrayList<>());
        try (RunningServer server = RunningServer.start((session, line) -> commands.add(line))) {
            try (Socket socket = server.connect()) {
                BufferedReader reader = reader(socket);
                socket.setSoTimeout(400);
                OutputStream out = socket.getOutputStream();
                out.write("PI".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThrows(java.net.SocketTimeoutException.class, reader::readLine);

                socket.setSoTimeout(3000);
                out.write("NG\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertEquals("PONG", reader.readLine());
            }
            Thread.sleep(200);
            assertTrue(commands.isEmpty());
        }
    }

    /** PING stays local. LIST is the other line, so it is submitted to the fixed thread pool. */
    @Test
    void pingAndListInOneWriteAnswerPingAndDispatchList() throws Exception {
        List<String> commands = Collections.synchronizedList(new ArrayList<>());
        try (RunningServer server = RunningServer.start((session, line) -> commands.add(line))) {
            try (Socket socket = server.connect()) {
                OutputStream out = socket.getOutputStream();
                out.write("PING\nLIST\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertEquals("PONG", reader(socket).readLine());
            }
            waitUntil(() -> commands.contains("LIST"));
            assertEquals(List.of("LIST"), List.copyOf(commands));
        }
    }

    /** A client that never sends a newline past 4 KB is closed. The buffer does not grow. */
    @Test
    void lineLongerThan4096WithoutNewlineClosesTheSocket() throws Exception {
        try (RunningServer server = RunningServer.start((session, line) -> { })) {
            try (Socket socket = server.connect()) {
                byte[] body = new byte[5000];
                Arrays.fill(body, (byte) 'A');
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
                socket.setSoTimeout(3000);
                assertClosed(socket.getInputStream());
            }
        }
    }

    /** A room thread queues a reply and wakeup() unblocks select() so the selector can write it. */
    @Test
    void sendFromAnotherThreadIsDeliveredWhileTheClientIsReading() throws Exception {
        try (RunningServer server = RunningServer.start((session, line) -> { })) {
            try (Socket socket = server.connect()) {
                waitUntil(() -> server.sessions.size() == 1);
                Thread sender = new Thread(() -> server.sessions.only().send("MSG:hi"));
                sender.start();
                sender.join(3000);
                socket.setSoTimeout(3000);
                assertEquals("MSG:hi", reader(socket).readLine());
            }
        }
    }

    /** read() == -1 is the client's FIN. We close our side and drop the session. */
    @Test
    void clientCloseRemovesTheSession() throws Exception {
        try (RunningServer server = RunningServer.start((session, line) -> { })) {
            try (Socket socket = server.connect()) {
                waitUntil(() -> server.sessions.size() == 1);
                socket.shutdownOutput();
            }
            waitUntil(() -> server.sessions.size() == 0);
        }
    }

    /** stop() closes the listening socket and wakes select(), so the selector thread can exit. */
    @Test
    void stopUnblocksTheSelectorAndClosesTheServerSocket() throws Exception {
        try (RunningServer server = RunningServer.start((session, line) -> { })) {
            int port = server.server.getPort();
            server.server.stop();
            server.thread.join(5000);
            assertFalse(server.thread.isAlive());
            assertThrows(IOException.class, () -> {
                try (Socket ignored = new Socket("localhost", port)) {
                    // The listening socket is closed.
                }
            });
        }
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        socket.setSoTimeout(3000);
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static void assertClosed(InputStream in) throws IOException {
        try {
            assertEquals(-1, in.read());
        } catch (IOException closed) {
            // The server closed the socket before the client finished reading.
        }
    }

    private static void waitUntil(Check check) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            if (check.ready()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for the server");
    }

    private interface Check {
        boolean ready();
    }

    private static final class MapSessions implements Sessions {
        private final ConcurrentHashMap<String, ClientSession> map = new ConcurrentHashMap<>();

        @Override
        public void add(ClientSession session) {
            map.put(session.getSessionId(), session);
        }

        @Override
        public void remove(ClientSession session) {
            map.remove(session.getSessionId());
        }

        @Override
        public Iterable<ClientSession> all() {
            return List.copyOf(map.values());
        }

        int size() {
            return map.size();
        }

        ClientSession only() {
            return map.values().iterator().next();
        }
    }

    private static final class RunningServer implements AutoCloseable {
        final GameServer server;
        final Thread thread;
        final ExecutorService pool;
        final MapSessions sessions;

        private RunningServer(GameServer server, Thread thread, ExecutorService pool, MapSessions sessions) {
            this.server = server;
            this.thread = thread;
            this.pool = pool;
            this.sessions = sessions;
        }

        static RunningServer start(InboundHandler handler) throws Exception {
            MapSessions sessions = new MapSessions();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            GameServer server = new GameServer(0, sessions, pool, handler, session -> { });
            AtomicReference<Throwable> crash = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    server.start();
                } catch (IOException e) {
                    crash.set(e);
                }
            }, "selector");
            thread.start();
            long deadline = System.currentTimeMillis() + 4000;
            while (server.getPort() == 0 && crash.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            if (crash.get() != null) {
                pool.shutdownNow();
                throw new AssertionError(crash.get());
            }
            if (server.getPort() == 0) {
                server.stop();
                pool.shutdownNow();
                fail("server did not bind");
            }
            return new RunningServer(server, thread, pool, sessions);
        }

        Socket connect() throws IOException {
            return new Socket("localhost", server.getPort());
        }

        @Override
        public void close() throws Exception {
            server.stop();
            thread.join(5000);
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
