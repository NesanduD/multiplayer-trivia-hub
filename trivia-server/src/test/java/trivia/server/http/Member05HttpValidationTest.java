package trivia.server.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Member05HttpValidationTest {
    @TempDir
    Path tempDir;

    private HttpServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        StatsController controller = new StatsController(
                new JsonFileStore(tempDir.resolve("matches.json")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", controller::handle);
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
    void malformedMatchJsonReturnsBadRequest() throws Exception {
        HttpResponse<String> response = send("POST", "/api/matches", "{");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Malformed JSON"));
    }

    @Test
    void blankMatchIdReturnsBadRequest() throws Exception {
        HttpResponse<String> response = send("POST", "/api/matches", "{\"matchId\":\"   \"}");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Invalid match payload"));
    }

    @Test
    void unsupportedMethodOnKnownRouteReturnsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = send("PUT", "/api/matches", "{}");

        assertEquals(405, response.statusCode());
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("POST"));
        assertTrue(response.body().contains("Method not allowed"));
    }

    @Test
    void unsupportedMethodOnReadOnlyRouteReturnsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = send("POST", "/api/leaderboard", "{}");

        assertEquals(405, response.statusCode());
        assertEquals("GET, OPTIONS", response.headers().firstValue("Allow").orElse(""));
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
