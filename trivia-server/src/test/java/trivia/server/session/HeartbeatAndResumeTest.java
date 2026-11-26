package trivia.server.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import trivia.server.config.ServerConfig;
import trivia.server.http.HttpStatsServer;
import trivia.server.nio.NioGameServer;
import trivia.server.room.RoomManager;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;
import trivia.shared.protocol.ProtocolCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatAndResumeTest {
    private NioGameServer nioGameServer;
    private HttpStatsServer httpStatsServer;
    private RoomManager roomManager;
    private Thread serverThread;

    @AfterEach
    void tearDown() {
        if (nioGameServer != null) {
            nioGameServer.close();
        }
        if (httpStatsServer != null) {
            httpStatsServer.close();
        }
        if (roomManager != null) {
            roomManager.shutdown();
        }
        if (serverThread != null) {
            try {
                serverThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ServerConfig startServer(int tcpPort, int httpPort) throws Exception {
        ServerConfig config = new ServerConfig.Builder()
                .serverName("HB-Test")
                .tcpPort(tcpPort)
                .httpPort(httpPort)
                .wsPort(tcpPort + 1)
                .statsPath(Files.createTempFile("trivia-hb-", ".json"))
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(600)
                .sessionRecoverWindowMs(2000)
                .dispatchWorkers(4)
                .build();

        AtomicReference<RoomManager> ref = new AtomicReference<>();
        httpStatsServer = new HttpStatsServer(
                config.httpPort(), config.statsPath(), config.tcpPort(), config.wsPort(),
                config.serverName(), ref::get);
        roomManager = new RoomManager(httpStatsServer, config);
        ref.set(roomManager);
        nioGameServer = new NioGameServer(config.tcpPort(), roomManager);
        httpStatsServer.start();
        serverThread = new Thread(() -> {
            try {
                nioGameServer.start();
            } catch (Exception ignored) {
                // closed
            }
        }, "nio-hb-test");
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(250);
        return config;
    }

    @Test
    void idleClientIsSoftDisconnectedThenExpired() throws Exception {
        int tcp = 15110;
        startServer(tcp, 18110);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", tcp), 2000);
            socket.setSoTimeout(8000);
            var out = new BufferedOutputStream(socket.getOutputStream());
            var in = new BufferedInputStream(socket.getInputStream());

            Message hello = Message.of(MessageType.HELLO);
            hello.setPlayerName("IdlePlayer");
            ProtocolCodec.writeFrame(out, hello);

            Message welcome = awaitType(in, MessageType.WELCOME, 3000);
            assertNotNull(welcome.getSessionToken());
            assertEquals(1, roomManager.totalPlayers());

            // Do not send PING — wait past heartbeat timeout + recover window
            Thread.sleep(3500);
            assertEquals(0, roomManager.totalPlayers(), "Abandoned session should expire");
        }
    }

    @Test
    void resumeRestoresPlayerAndRoom() throws Exception {
        int tcp = 15120;
        startServer(tcp, 18120);

        String token;
        String playerId;
        String roomId;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", tcp), 2000);
            socket.setSoTimeout(5000);
            var out = new BufferedOutputStream(socket.getOutputStream());
            var in = new BufferedInputStream(socket.getInputStream());

            Message hello = Message.of(MessageType.HELLO);
            hello.setPlayerName("ResumePlayer");
            ProtocolCodec.writeFrame(out, hello);
            Message welcome = awaitType(in, MessageType.WELCOME, 3000);
            token = welcome.getSessionToken();
            playerId = welcome.getPlayerId();
            assertNotNull(token);

            Message create = Message.of(MessageType.CREATE_ROOM);
            create.setRoomName("Recover Room");
            ProtocolCodec.writeFrame(out, create);
            Message created = awaitType(in, MessageType.ROOM_CREATED, 3000);
            roomId = created.getRoomId();
            assertNotNull(roomId);
            // Drop connection without GOODBYE (simulate crash)
        }

        Thread.sleep(300);
        assertEquals(1, roomManager.totalPlayers());
        assertEquals(1, roomManager.totalRooms());

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", tcp), 2000);
            socket.setSoTimeout(5000);
            var out = new BufferedOutputStream(socket.getOutputStream());
            var in = new BufferedInputStream(socket.getInputStream());

            Message resume = Message.of(MessageType.RESUME);
            resume.setSessionToken(token);
            ProtocolCodec.writeFrame(out, resume);

            Message welcome = awaitType(in, MessageType.WELCOME, 3000);
            assertEquals(playerId, welcome.getPlayerId());
            assertEquals(Boolean.TRUE, welcome.getExtra().get("resumed"));
            assertEquals(roomId, welcome.getRoomId());
            assertNotNull(welcome.getSessionToken());
            assertTrue(!welcome.getSessionToken().equals(token), "Token should rotate on resume");

            Message ping = Message.of(MessageType.PING);
            ping.setServerTime(System.currentTimeMillis());
            ProtocolCodec.writeFrame(out, ping);

            boolean sawRoomState = roomId.equals(welcome.getRoomId());
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline && !sawRoomState) {
                Message next = ProtocolCodec.readFrame(in);
                if (next == null) {
                    break;
                }
                if (next.getType() == MessageType.ROOM_STATE && roomId.equals(next.getRoomId())) {
                    sawRoomState = true;
                }
            }
            assertTrue(sawRoomState, "Resumed player should be back in room");
        }
    }

    @Test
    void nameOnlyCannotStealSession() throws Exception {
        int tcp = 15130;
        startServer(tcp, 18130);

        String playerId;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", tcp), 2000);
            socket.setSoTimeout(5000);
            var out = new BufferedOutputStream(socket.getOutputStream());
            var in = new BufferedInputStream(socket.getInputStream());
            Message hello = Message.of(MessageType.HELLO);
            hello.setPlayerName("Alice");
            ProtocolCodec.writeFrame(out, hello);
            Message welcome = awaitType(in, MessageType.WELCOME, 3000);
            playerId = welcome.getPlayerId();
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", tcp), 2000);
            socket.setSoTimeout(5000);
            var out = new BufferedOutputStream(socket.getOutputStream());
            var in = new BufferedInputStream(socket.getInputStream());
            Message hello = Message.of(MessageType.HELLO);
            hello.setPlayerName("Alice");
            ProtocolCodec.writeFrame(out, hello);
            Message welcome = awaitType(in, MessageType.WELCOME, 3000);
            assertNotNull(welcome.getPlayerId());
            assertTrue(!playerId.equals(welcome.getPlayerId()), "Name-only HELLO must mint a new playerId");
        }
    }

    private static Message awaitType(BufferedInputStream in, MessageType type, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Message msg = ProtocolCodec.readFrame(in);
            if (msg != null && msg.getType() == type) {
                return msg;
            }
        }
        throw new AssertionError("Did not receive " + type + " within " + timeoutMs + "ms");
    }
}
