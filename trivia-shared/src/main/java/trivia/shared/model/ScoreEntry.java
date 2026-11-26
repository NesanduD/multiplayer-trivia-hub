package trivia.shared.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class ScoreEntry {
    private String playerId;
    private String playerName;
    private int score;

    public ScoreEntry() {
    }

    public ScoreEntry(String playerId, String playerName, int score) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.score = score;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("playerId", playerId);
        map.put("playerName", playerName);
        map.put("score", score);
        return map;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }
}
