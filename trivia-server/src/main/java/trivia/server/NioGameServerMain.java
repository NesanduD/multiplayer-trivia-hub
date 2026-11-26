package trivia.server;

import trivia.server.discovery.DiscoveryBeacon;
import trivia.server.http.HttpStatsServer;
import trivia.server.nio.NioGameServer;
import trivia.server.room.RoomManager;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Boots NIO TCP game server, UDP discovery beacon, and HTTP stats service.
 */
public final class NioGameServerMain {
    private static final Logger LOG = Logger.getLogger(NioGameServerMain.class.getName());

    public static final int DEFAULT_TCP_PORT = 5000;
    public static final int DEFAULT_HTTP_PORT = 8080;

    private NioGameServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int tcpPort = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_TCP_PORT;
        int httpPort = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_HTTP_PORT;
        String serverName = args.length > 2 ? args[2] : "Trivia-Hub";

        Path storePath = Path.of("data", "match-history.json");
        HttpStatsServer httpStatsServer = new HttpStatsServer(httpPort, storePath);
        RoomManager roomManager = new RoomManager(httpStatsServer);
        NioGameServer nioGameServer = new NioGameServer(tcpPort, roomManager);
        DiscoveryBeacon beacon = new DiscoveryBeacon(serverName, tcpPort, httpPort, roomManager);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down Trivia Hub server...");
            beacon.close();
            nioGameServer.close();
            httpStatsServer.close();
            roomManager.shutdown();
        }));

        httpStatsServer.start();
        beacon.start();

        LOG.info(() -> """
                ========================================
                 Multiplayer Trivia Game Hub - SERVER
                 TCP (NIO):  %d
                 HTTP:       %d
                 Discovery:  UDP multicast 239.255.77.77:4445
                ========================================
                """.formatted(tcpPort, httpPort));

        nioGameServer.start();
    }
}
