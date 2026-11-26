package trivia.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trivia.server.room.RoomManager;
import trivia.shared.api.MatchStatsPublisher;
import trivia.shared.discovery.DiscoveryMessage;
import trivia.shared.dto.MatchResult;
import trivia.shared.protocol.ProtocolCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * JDK HttpServer: web UI, REST stats, server info, and network lab endpoints.
 */
public class HttpStatsServer implements MatchStatsPublisher, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(HttpStatsServer.class.getName());
    private static final long STARTED_AT = System.currentTimeMillis();

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
        server.createContext("/api/network-lab", this::networkLab);
        server.createContext("/api/rooms", this::rooms);
        server.createContext("/api/players", this::players);
        server.createContext("/api", controller::handle);
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(null);
        server.start();
        LOG.info(() -> "Web UI + HTTP API at http://localhost:" + port + "/");
    }

    private void serverInfo(HttpExchange exchange) throws IOException {
        if (!allowGet(exchange)) {
            return;
        }
        RoomManager rooms = roomManagerSupplier.get();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("serverName", serverName);
        info.put("host", localHost());
        info.put("tcpPort", tcpPort);
        info.put("wsPort", wsPort);
        info.put("httpPort", port);
        info.put("players", rooms == null ? 0 : rooms.totalPlayers());
        info.put("rooms", rooms == null ? 0 : rooms.totalRooms());
        info.put("wsUrl", "ws://" + browserHost(exchange) + ":" + wsPort);
        info.put("uptimeMs", System.currentTimeMillis() - STARTED_AT);
        info.put("multicastGroup", DiscoveryMessage.MULTICAST_GROUP);
        info.put("multicastPort", DiscoveryMessage.MULTICAST_PORT);
        writeJson(exchange, 200, info);
    }

    private void rooms(HttpExchange exchange) throws IOException {
        if (!allowGet(exchange)) {
            return;
        }
        RoomManager manager = roomManagerSupplier.get();
        writeJson(exchange, 200, manager == null ? List.of() : manager.roomSummaries());
    }

    private void players(HttpExchange exchange) throws IOException {
        if (!allowGet(exchange)) {
            return;
        }
        RoomManager manager = roomManagerSupplier.get();
        writeJson(exchange, 200, manager == null ? List.of() : manager.onlinePlayers());
    }

    private void networkLab(HttpExchange exchange) throws IOException {
        if (!allowGet(exchange)) {
            return;
        }
        RoomManager manager = roomManagerSupplier.get();
        List<Map<String, Object>> concepts = new ArrayList<>();
        concepts.add(concept(
                "TCP Sockets",
                "Lecture 02",
                "Reliable connection-oriented streams",
                "Native clients use java.net.Socket / NIO SocketChannel on TCP " + tcpPort,
                "active"));
        concepts.add(concept(
                "Java NIO",
                "NIO lecture",
                "Non-blocking Channels, Buffers, Selector",
                "One selector thread accepts many TCP game clients",
                "active"));
        concepts.add(concept(
                "UDP Multicast Discovery",
                "UDP + Multicasting",
                "Connectionless Class-D group beacons",
                "SERVER_ANNOUNCE to " + DiscoveryMessage.MULTICAST_GROUP + ":" + DiscoveryMessage.MULTICAST_PORT,
                "active"));
        concepts.add(concept(
                "Addressing & Ports",
                "Addressing / Lecture 01",
                "IP + port identify services on one host",
                "TCP " + tcpPort + " · WS " + wsPort + " · HTTP " + port,
                "active"));
        concepts.add(concept(
                "Multithreading",
                "Multithreaded Servers",
                "Thread pools, sync, timers",
                "Room workers + question timers · rooms=" + (manager == null ? 0 : manager.totalRooms()),
                "active"));
        concepts.add(concept(
                "HTTP & REST",
                "HTTP and Web Services",
                "Resource APIs with JSON",
                "GET /api/leaderboard, /api/matches, /api/rooms, /api/network-lab",
                "active"));
        concepts.add(concept(
                "WebSocket Bridge",
                "Additional (browser transport)",
                "Browser-friendly duplex channel over TCP",
                "Browsers cannot open raw TCP sockets; WS maps to the same Message protocol",
                "active"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverName", serverName);
        payload.put("concepts", concepts);
        payload.put("tip", "Open DevTools → Network to watch HTTP. Use Network Lab in the UI for live PING RTT.");
        writeJson(exchange, 200, payload);
    }

    private static Map<String, Object> concept(String name, String lecture, String meaning,
                                               String evidence, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("lecture", lecture);
        map.put("meaning", meaning);
        map.put("evidence", evidence);
        map.put("status", status);
        return map;
    }

    private boolean allowGet(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return false;
        }
        return true;
    }

    private void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = ProtocolCodec.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String localHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ex) {
            return "127.0.0.1";
        }
    }

    private static String browserHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            return "localhost";
        }
        int colon = host.indexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
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
