package trivia.server.http;

import com.sun.net.httpserver.HttpServer;
import trivia.shared.api.MatchStatsPublisher;
import trivia.shared.dto.MatchResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * JDK HttpServer exposing REST stats endpoints (Member 05).
 */
public class HttpStatsServer implements MatchStatsPublisher, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(HttpStatsServer.class.getName());

    private final int port;
    private final JsonFileStore store;
    private final StatsController controller;
    private HttpServer server;

    public HttpStatsServer(int port, Path storeFile) {
        this.port = port;
        this.store = new JsonFileStore(storeFile);
        this.controller = new StatsController(store);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", controller::handle);
        server.setExecutor(null);
        server.start();
        LOG.info(() -> "HTTP stats server listening on http://localhost:" + port + "/api/leaderboard");
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
