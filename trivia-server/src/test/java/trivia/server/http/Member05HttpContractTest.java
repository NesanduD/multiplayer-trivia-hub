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

class Member05HttpContractTest {
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
    void missingMatchReturnsJsonNotFound() throws Exception {
        HttpResponse<String> response = get("/api/matches/missing-match");

        assertEquals(404, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/json"));
        assertEquals("{\"error\":\"Match not found\"}", response.body());
    }

    @Test
    void submissionWithoutMatchIdReturnsBadRequest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/matches"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Invalid match payload"));
    }

    @Test
    void unknownApiRouteReturnsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/not-a-route");

        assertEquals(404, response.statusCode());
        assertEquals("{\"error\":\"Not found\"}", response.body());
    }

    @Test
    void servesHtmlJavascriptAndStylesheet() throws Exception {
        HttpResponse<String> html = get("/");
        HttpResponse<String> javascript = get("/app.js");
        HttpResponse<String> stylesheet = get("/styles.css");

        assertEquals(200, html.statusCode());
        assertTrue(html.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/html"));
        assertTrue(html.body().contains("<title>Trivia Hub</title>"));

        assertEquals(200, javascript.statusCode());
        assertTrue(javascript.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/javascript"));
        assertTrue(javascript.body().contains("new WebSocket"));

        assertEquals(200, stylesheet.statusCode());
        assertTrue(stylesheet.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/css"));
    }

    @Test
    void missingStaticAssetReturnsNotFound() throws Exception {
        HttpResponse<String> response = get("/missing-asset.js");

        assertEquals(404, response.statusCode());
        assertEquals("Not found", response.body());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
