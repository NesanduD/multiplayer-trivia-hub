package trivia.server;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationSmokeTest {
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

    @Test
    void helloCreateRoomAndHttpLeaderboard() throws Exception {
        Path store = Files.createTempFile("trivia-stats-", ".json");
        ServerConfig config = ServerConfig.forTests(15000, 18080, 15001);
        AtomicReference<RoomManager> ref = new AtomicReference<>();
        httpStatsServer = new HttpStatsServer(
                config.httpPort(), store, config.tcpPort(), config.wsPort(), config.serverName(), ref::get);
        roomManager = new RoomManager(httpStatsServer, config);
        ref.set(roomManager);
        nioGameServer = new NioGameServer(config.tcpPort(), roomManager);
        httpStatsServer.start();

        serverThread = new Thread(() -> {
            try {
                nioGameServer.start();
            } catch (Exception ignored) {
                // closed during teardown
            }
        }, "nio-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(300);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", config.tcpPort()), 2000);
            socket.setSoTimeout(5000);
            var out = new BufferedOutputStream(socket.getOutputStream());
            var in = new BufferedInputStream(socket.getInputStream());

            Message hello = Message.of(MessageType.HELLO);
            hello.setPlayerName("Tester");
            hello.setClientId(UUID.randomUUID().toString());
            ProtocolCodec.writeFrame(out, hello);

            Message welcome = awaitType(in, MessageType.WELCOME, 3000);
            assertNotNull(welcome.getPlayerId());
            assertNotNull(welcome.getSessionToken());

            Message create = Message.of(MessageType.CREATE_ROOM);
            create.setRoomName("Integration Room");
            create.setMaxPlayers(4);
            ProtocolCodec.writeFrame(out, create);

            Message created = awaitType(in, MessageType.ROOM_CREATED, 3000);
            assertTrue(created.getRoomId().startsWith("r-"));
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://127.0.0.1:18080/api/leaderboard"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("["));
    }

    private static Message awaitType(BufferedInputStream in, MessageType type, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Message msg = ProtocolCodec.readFrame(in);
            if (msg != null && msg.getType() == type) {
                return msg;
            }
        }
        throw new AssertionError("Did not receive " + type);
    }
}
