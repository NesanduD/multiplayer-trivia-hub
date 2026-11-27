package trivia.server.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trivia.shared.dto.MatchResult;
import trivia.shared.model.ScoreEntry;
import trivia.shared.protocol.ProtocolCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Member05HttpSuccessFlowTest {
    @TempDir
    Path tempDir;

    private HttpServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        JsonFileStore store = new JsonFileStore(tempDir.resolve("matches.json"));
        StatsController controller = new StatsController(store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", controller::handle);
        server.createContext("/", new StaticFileHandler());
        server.start();

        client = HttpClient.newHttpClient();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postingMatchMakesItAvailableInMatchListAndDetail() throws Exception {
        MatchResult match = new MatchResult(
                "match-123",
                "room-abc",
                "HTTP Test Room",
                1_700_000_000_000L,
                List.of(new ScoreEntry("player-1", "Ada", 25)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/matches"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ProtocolCodec.toJson(match)))
                .build();

        HttpResponse<String> posted = client.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> listed = get("/api/matches");
        HttpResponse<String> detailed = get("/api/matches/match-123");

        assertEquals(201, posted.statusCode());
        assertTrue(posted.body().contains("match-123"));
        assertEquals(200, listed.statusCode());
        assertTrue(listed.body().contains("match-123"));
        assertEquals(200, detailed.statusCode());
        assertTrue(detailed.body().contains("HTTP Test Room"));
        assertTrue(detailed.body().contains("Ada"));
    }

    @Test
    void playerHistoryReturnsMatchesContainingThatPlayer() throws Exception {
        MatchResult match = new MatchResult(
                "match-history",
                "room-history",
                "History Room",
                1_700_000_000_001L,
                List.of(new ScoreEntry("player-history", "Grace", 40)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/matches"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ProtocolCodec.toJson(match)))
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response = get("/api/players/player-history/history");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("match-history"));
        assertTrue(response.body().contains("Grace"));
    }

    @Test
    void optionsRequestReturnsNoContentAndCorsHeaders() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/matches"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(204, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")
                .contains("POST"));
    }

    @Test
    void staticHandlerSupportsHeadAndRejectsTraversalPath() throws Exception {
        HttpRequest headRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/styles.css"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> head = client.send(headRequest, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> traversal = get("/../pom.xml");

        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertTrue(head.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
        assertEquals(400, traversal.statusCode());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
