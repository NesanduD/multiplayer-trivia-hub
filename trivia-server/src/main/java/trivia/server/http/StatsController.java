package trivia.server.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import trivia.shared.dto.MatchResult;
import trivia.shared.dto.PlayerStats;
import trivia.shared.protocol.ProtocolCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * REST-style request routing for match history and leaderboards (Member 05).
 */
public class StatsController {
    private final JsonFileStore store;

    public StatsController(JsonFileStore store) {
        this.store = store;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            send(exchange, 204, "");
            return;
        }

        try {
            if ("GET".equalsIgnoreCase(method) && "/api/leaderboard".equals(path)) {
                List<PlayerStats> board = store.leaderboard();
                sendJson(exchange, 200, ProtocolCodec.toJson(board));
                return;
            }
            if ("GET".equalsIgnoreCase(method) && "/api/matches".equals(path)) {
                sendJson(exchange, 200, ProtocolCodec.toJson(store.allMatches()));
                return;
            }
            if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/matches/")) {
                String matchId = path.substring("/api/matches/".length());
                MatchResult match = store.findMatch(matchId);
                if (match == null) {
                    sendJson(exchange, 404, "{\"error\":\"Match not found\"}");
                } else {
                    sendJson(exchange, 200, ProtocolCodec.toJson(match));
                }
                return;
            }
            if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/players/") && path.endsWith("/history")) {
                String playerId = path.substring("/api/players/".length(), path.length() - "/history".length());
                sendJson(exchange, 200, ProtocolCodec.toJson(store.historyForPlayer(playerId)));
                return;
            }
            if ("POST".equalsIgnoreCase(method) && "/api/matches".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                MatchResult result = ProtocolCodec.fromJson(body, MatchResult.class);
                if (result == null || result.getMatchId() == null) {
                    sendJson(exchange, 400, "{\"error\":\"Invalid match payload\"}");
                    return;
                }
                store.saveMatch(result);
                sendJson(exchange, 201, ProtocolCodec.toJson(result));
                return;
            }
            sendJson(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (Exception ex) {
            sendJson(exchange, 500, "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
        }
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "'");
    }
}
