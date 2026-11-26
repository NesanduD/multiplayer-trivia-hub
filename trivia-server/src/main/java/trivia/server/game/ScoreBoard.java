package trivia.server.game;

import trivia.shared.model.ScoreEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe scoreboard demonstrating synchronized access to shared state.
 */
public class ScoreBoard {
    private final Map<String, ScoreEntry> scores = new LinkedHashMap<>();

    public synchronized void register(String playerId, String playerName) {
        scores.putIfAbsent(playerId, new ScoreEntry(playerId, playerName, 0));
    }

    public synchronized void remove(String playerId) {
        scores.remove(playerId);
    }

    public synchronized int addPoints(String playerId, int points) {
        ScoreEntry entry = scores.get(playerId);
        if (entry == null) {
            return 0;
        }
        entry.setScore(entry.getScore() + points);
        return entry.getScore();
    }

    public synchronized List<ScoreEntry> snapshot() {
        List<ScoreEntry> list = new ArrayList<>();
        for (ScoreEntry entry : scores.values()) {
            list.add(new ScoreEntry(entry.getPlayerId(), entry.getPlayerName(), entry.getScore()));
        }
        list.sort(Comparator.comparingInt(ScoreEntry::getScore).reversed());
        return list;
    }

    public synchronized List<Map<String, Object>> asMaps() {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (ScoreEntry entry : snapshot()) {
            maps.add(entry.toMap());
        }
        return maps;
    }
}
