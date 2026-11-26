package trivia.server;

import trivia.server.discovery.DiscoveryBeacon;
import trivia.server.http.HttpStatsServer;
import trivia.server.nio.NioGameServer;
import trivia.server.room.RoomManager;
import trivia.server.ws.WebSocketGameServer;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Boots NIO TCP server, WebSocket web bridge, UDP discovery, and HTTP web UI/stats.
 */
public final class NioGameServerMain {
    private static final Logger LOG = Logger.getLogger(NioGameServerMain.class.getName());

    public static final int DEFAULT_TCP_PORT = 5000;
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_WS_PORT = 5001;

    private NioGameServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int tcpPort = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_TCP_PORT;
        int httpPort = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_HTTP_PORT;
        String serverName = args.length > 2 ? args[2] : "Trivia-Hub";
        int wsPort = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_WS_PORT;

        Path storePath = Path.of("data", "match-history.json");
        AtomicReference<RoomManager> roomManagerRef = new AtomicReference<>();

        HttpStatsServer httpStatsServer = new HttpStatsServer(
                httpPort, storePath, tcpPort, wsPort, serverName, roomManagerRef::get);
        RoomManager roomManager = new RoomManager(httpStatsServer);
        roomManagerRef.set(roomManager);

        NioGameServer nioGameServer = new NioGameServer(tcpPort, roomManager);
        WebSocketGameServer webSocketGameServer = new WebSocketGameServer(wsPort, roomManager);
        DiscoveryBeacon beacon = new DiscoveryBeacon(serverName, tcpPort, httpPort, roomManager);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down Trivia Hub server...");
            beacon.close();
            webSocketGameServer.close();
            nioGameServer.close();
            httpStatsServer.close();
            roomManager.shutdown();
        }));

        httpStatsServer.start();
        webSocketGameServer.start();
        beacon.start();

        LOG.info(() -> """
                ========================================
                 Multiplayer Trivia Game Hub - SERVER
                 Web UI:     http://localhost:%d/
                 WebSocket:  ws://localhost:%d
                 TCP (NIO):  %d
                 Discovery:  UDP multicast 239.255.77.77:4445
                ========================================
                Open the Web UI in your browser to play.
                """.formatted(httpPort, wsPort, tcpPort));

        nioGameServer.start();
    }
}
