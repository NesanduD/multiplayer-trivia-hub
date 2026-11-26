package trivia.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
        java.util.concurrent.atomic.AtomicReference<RoomManager> ref = new java.util.concurrent.atomic.AtomicReference<>();
        httpStatsServer = new HttpStatsServer(18080, store, 15000, 15001, "Test", ref::get);
        roomManager = new RoomManager(httpStatsServer);
        ref.set(roomManager);
        nioGameServer = new NioGameServer(15000, roomManager);
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
            socket.connect(new InetSocketAddress("127.0.0.1", 15000), 2000);
            var out = new BufferedOutputStream(socket.getOutputStream());
            var in = new BufferedInputStream(socket.getInputStream());

            Message hello = Message.of(MessageType.HELLO);
            hello.setPlayerName("Tester");
            hello.setClientId(UUID.randomUUID().toString());
            ProtocolCodec.writeFrame(out, hello);

            Message welcome = ProtocolCodec.readFrame(in);
            assertNotNull(welcome);
            assertEquals(MessageType.WELCOME, welcome.getType());
            assertNotNull(welcome.getPlayerId());

            // ROOM_LIST may arrive after WELCOME
            Message maybeList = ProtocolCodec.readFrame(in);
            assertNotNull(maybeList);

            Message create = Message.of(MessageType.CREATE_ROOM);
            create.setRoomName("Integration Room");
            create.setMaxPlayers(4);
            ProtocolCodec.writeFrame(out, create);

            Message created = null;
            for (int i = 0; i < 5; i++) {
                Message next = ProtocolCodec.readFrame(in);
                if (next != null && next.getType() == MessageType.ROOM_CREATED) {
                    created = next;
                    break;
                }
            }
            assertNotNull(created);
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
}
