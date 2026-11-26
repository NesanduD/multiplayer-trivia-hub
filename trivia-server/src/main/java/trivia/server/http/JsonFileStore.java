package trivia.server.http;

import trivia.shared.dto.MatchResult;
import trivia.shared.dto.PlayerStats;
import trivia.shared.model.ScoreEntry;
import trivia.shared.protocol.ProtocolCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple JSON file-backed store for match history and aggregated player stats.
 */
public class JsonFileStore {
    private static final Logger LOG = Logger.getLogger(JsonFileStore.class.getName());

    private final Path storeFile;
    private final List<MatchResult> matches = new ArrayList<>();
    private final Map<String, PlayerStats> playerStats = new ConcurrentHashMap<>();

    public JsonFileStore(Path storeFile) {
        this.storeFile = storeFile;
        load();
    }

    public synchronized void saveMatch(MatchResult result) {
        matches.add(result);
        for (ScoreEntry entry : result.getScores()) {
            PlayerStats stats = playerStats.computeIfAbsent(
                    entry.getPlayerId(),
                    id -> new PlayerStats(entry.getPlayerId(), entry.getPlayerName()));
            stats.setPlayerName(entry.getPlayerName());
            stats.setMatchesPlayed(stats.getMatchesPlayed() + 1);
            stats.setTotalScore(stats.getTotalScore() + entry.getScore());
        }
        if (!result.getScores().isEmpty()) {
            ScoreEntry winner = result.getScores().getFirst();
            PlayerStats stats = playerStats.get(winner.getPlayerId());
            if (stats != null) {
                stats.setWins(stats.getWins() + 1);
            }
        }
        persist();
    }

    public synchronized MatchResult findMatch(String matchId) {
        return matches.stream()
                .filter(m -> m.getMatchId().equals(matchId))
                .findFirst()
                .orElse(null);
    }

    public synchronized List<MatchResult> historyForPlayer(String playerId) {
        List<MatchResult> history = new ArrayList<>();
        for (MatchResult match : matches) {
            boolean played = match.getScores().stream()
                    .anyMatch(s -> s.getPlayerId().equals(playerId));
            if (played) {
                history.add(match);
            }
        }
        history.sort(Comparator.comparingLong(MatchResult::getEndedAt).reversed());
        return history;
    }

    public synchronized List<PlayerStats> leaderboard() {
        List<PlayerStats> list = new ArrayList<>(playerStats.values());
        list.sort(Comparator.comparingInt(PlayerStats::getTotalScore).reversed());
        return list;
    }

    public synchronized List<MatchResult> allMatches() {
        List<MatchResult> copy = new ArrayList<>(matches);
        copy.sort(Comparator.comparingLong(MatchResult::getEndedAt).reversed());
        return copy;
    }

    private void load() {
        if (!Files.exists(storeFile)) {
            return;
        }
        try {
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            StoreSnapshot snapshot = ProtocolCodec.fromJson(json, StoreSnapshot.class);
            if (snapshot != null) {
                if (snapshot.matches != null) {
                    matches.addAll(snapshot.matches);
                }
                if (snapshot.playerStats != null) {
                    playerStats.putAll(snapshot.playerStats);
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Could not load stats store", ex);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(storeFile.getParent() == null ? Path.of(".") : storeFile.getParent());
            StoreSnapshot snapshot = new StoreSnapshot();
            snapshot.matches = new ArrayList<>(matches);
            snapshot.playerStats = new LinkedHashMap<>(playerStats);
            Files.writeString(storeFile, ProtocolCodec.toJson(snapshot), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not persist stats store", ex);
        }
    }

    public static class StoreSnapshot {
        public List<MatchResult> matches = new ArrayList<>();
        public Map<String, PlayerStats> playerStats = new LinkedHashMap<>();
    }
}
