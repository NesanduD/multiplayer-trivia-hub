package trivia.client.http;

import com.google.gson.reflect.TypeToken;
import trivia.shared.dto.MatchResult;
import trivia.shared.dto.PlayerStats;
import trivia.shared.protocol.ProtocolCodec;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Consumes the REST stats API using java.net.http.HttpClient (Member 05).
 */
public class StatsApiClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private String baseUrl = "http://localhost:8080";

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public List<PlayerStats> leaderboard() throws IOException, InterruptedException {
        String body = get("/api/leaderboard");
        Type type = new TypeToken<List<PlayerStats>>() {
        }.getType();
        List<PlayerStats> list = ProtocolCodec.gson().fromJson(body, type);
        return list == null ? Collections.emptyList() : list;
    }

    public List<MatchResult> matches() throws IOException, InterruptedException {
        String body = get("/api/matches");
        Type type = new TypeToken<List<MatchResult>>() {
        }.getType();
        List<MatchResult> list = ProtocolCodec.gson().fromJson(body, type);
        return list == null ? Collections.emptyList() : list;
    }

    public List<MatchResult> playerHistory(String playerId) throws IOException, InterruptedException {
        String body = get("/api/players/" + playerId + "/history");
        Type type = new TypeToken<List<MatchResult>>() {
        }.getType();
        List<MatchResult> list = ProtocolCodec.gson().fromJson(body, type);
        return list == null ? Collections.emptyList() : list;
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
