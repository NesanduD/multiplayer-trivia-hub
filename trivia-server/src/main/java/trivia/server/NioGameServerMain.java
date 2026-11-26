package trivia.server;

import trivia.server.config.ServerConfig;
import trivia.server.discovery.DiscoveryBeacon;
import trivia.server.http.HttpStatsServer;
import trivia.server.nio.NioGameServer;
import trivia.server.room.RoomManager;
import trivia.server.ws.WebSocketGameServer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Boots NIO TCP server, WebSocket web bridge, UDP discovery, and HTTP web UI/stats.
 */
public final class NioGameServerMain {
    private static final Logger LOG = Logger.getLogger(NioGameServerMain.class.getName());

    private NioGameServerMain() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.load(null, args);

        AtomicReference<RoomManager> roomManagerRef = new AtomicReference<>();

        HttpStatsServer httpStatsServer = new HttpStatsServer(
                config.httpPort(),
                config.statsPath(),
                config.tcpPort(),
                config.wsPort(),
                config.serverName(),
                roomManagerRef::get);
        RoomManager roomManager = new RoomManager(httpStatsServer, config);
        roomManagerRef.set(roomManager);

        NioGameServer nioGameServer = new NioGameServer(config.tcpPort(), roomManager);
        WebSocketGameServer webSocketGameServer = new WebSocketGameServer(config.wsPort(), roomManager);
        DiscoveryBeacon beacon = new DiscoveryBeacon(
                config.serverName(), config.tcpPort(), config.httpPort(), roomManager);

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
                 Multiplayer Trivia Game Hub - SERVER V2
                 Web UI:     http://localhost:%d/
                 WebSocket:  ws://localhost:%d
                 TCP (NIO):  %d
                 Heartbeat:  timeout %d ms · recover %d ms
                 Discovery:  UDP multicast 239.255.77.77:4445
                ========================================
                Open the Web UI in your browser to play.
                """.formatted(
                config.httpPort(),
                config.wsPort(),
                config.tcpPort(),
                config.heartbeatTimeoutMs(),
                config.sessionRecoverWindowMs()));

        nioGameServer.start();
    }
}
