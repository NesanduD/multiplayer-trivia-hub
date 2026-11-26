package trivia.shared.dto;

import trivia.shared.model.ScoreEntry;

import java.util.ArrayList;
import java.util.List;

public class MatchResult {
    private String matchId;
    private String roomId;
    private String roomName;
    private long endedAt;
    private List<ScoreEntry> scores = new ArrayList<>();

    public MatchResult() {
    }

    public MatchResult(String matchId, String roomId, String roomName, long endedAt, List<ScoreEntry> scores) {
        this.matchId = matchId;
        this.roomId = roomId;
        this.roomName = roomName;
        this.endedAt = endedAt;
        this.scores = scores != null ? scores : new ArrayList<>();
    }

    public String getMatchId() {
        return matchId;
    }

    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(long endedAt) {
        this.endedAt = endedAt;
    }

    public List<ScoreEntry> getScores() {
        return scores;
    }

    public void setScores(List<ScoreEntry> scores) {
        this.scores = scores != null ? scores : new ArrayList<>();
    }
}
