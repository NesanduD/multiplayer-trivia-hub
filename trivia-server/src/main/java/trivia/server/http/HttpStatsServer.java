package trivia.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trivia.server.room.RoomManager;
import trivia.shared.api.MatchStatsPublisher;
import trivia.shared.dto.MatchResult;
import trivia.shared.protocol.ProtocolCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * JDK HttpServer: web UI, REST stats, and server info for browser clients.
 */
public class HttpStatsServer implements MatchStatsPublisher, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(HttpStatsServer.class.getName());

    private final int port;
    private final JsonFileStore store;
    private final StatsController controller;
    private final Supplier<RoomManager> roomManagerSupplier;
    private final int wsPort;
    private final int tcpPort;
    private final String serverName;
    private HttpServer server;

    public HttpStatsServer(int port, Path storeFile, int tcpPort, int wsPort, String serverName,
                           Supplier<RoomManager> roomManagerSupplier) {
        this.port = port;
        this.store = new JsonFileStore(storeFile);
        this.controller = new StatsController(store);
        this.tcpPort = tcpPort;
        this.wsPort = wsPort;
        this.serverName = serverName;
        this.roomManagerSupplier = roomManagerSupplier;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/server-info", this::serverInfo);
        server.createContext("/api", controller::handle);
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(null);
        server.start();
        LOG.info(() -> "Web UI + HTTP API at http://localhost:" + port + "/");
    }

    private void serverInfo(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        RoomManager rooms = roomManagerSupplier.get();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("serverName", serverName);
        info.put("tcpPort", tcpPort);
        info.put("wsPort", wsPort);
        info.put("httpPort", port);
        info.put("players", rooms == null ? 0 : rooms.totalPlayers());
        info.put("rooms", rooms == null ? 0 : rooms.totalRooms());
        info.put("wsUrl", "ws://localhost:" + wsPort);
        byte[] body = ProtocolCodec.toJson(info).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void publish(MatchResult result) {
        store.saveMatch(result);
        LOG.info(() -> "Published match " + result.getMatchId() + " with "
                + result.getScores().size() + " players");
    }

    public JsonFileStore getStore() {
        return store;
    }

    public int getPort() {
        return port;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
