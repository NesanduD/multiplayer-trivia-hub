package trivia.shared.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic length-framed JSON message used on the TCP game channel.
 * Fields that are unused for a given {@link MessageType} remain null / empty.
 */
public class Message {
    private MessageType type;
    private String playerName;
    private String clientId;
    private String playerId;
    private String roomId;
    private String roomName;
    private Integer maxPlayers;
    private Integer qIndex;
    private String text;
    private List<String> options = new ArrayList<>();
    private Integer optionIndex;
    private Boolean correct;
    private Integer points;
    private Long endsAt;
    private Long serverTime;
    private String from;
    private String reason;
    private String error;
    private String status;
    private List<Map<String, Object>> scores = new ArrayList<>();
    private List<String> players = new ArrayList<>();
    private List<Map<String, Object>> rooms = new ArrayList<>();
    private Map<String, Object> extra = new LinkedHashMap<>();

    public Message() {
    }

    public Message(MessageType type) {
        this.type = type;
    }

    public static Message of(MessageType type) {
        return new Message(type);
    }

    public static Message error(String error) {
        Message m = new Message(MessageType.ERROR);
        m.setError(error);
        return m;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
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

    public Integer getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(Integer maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public Integer getQIndex() {
        return qIndex;
    }

    public void setQIndex(Integer qIndex) {
        this.qIndex = qIndex;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options != null ? options : new ArrayList<>();
    }

    public Integer getOptionIndex() {
        return optionIndex;
    }

    public void setOptionIndex(Integer optionIndex) {
        this.optionIndex = optionIndex;
    }

    public Boolean getCorrect() {
        return correct;
    }

    public void setCorrect(Boolean correct) {
        this.correct = correct;
    }

    public Integer getPoints() {
        return points;
    }

    public void setPoints(Integer points) {
        this.points = points;
    }

    public Long getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Long endsAt) {
        this.endsAt = endsAt;
    }

    public Long getServerTime() {
        return serverTime;
    }

    public void setServerTime(Long serverTime) {
        this.serverTime = serverTime;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Map<String, Object>> getScores() {
        return scores;
    }

    public void setScores(List<Map<String, Object>> scores) {
        this.scores = scores != null ? scores : new ArrayList<>();
    }

    public List<String> getPlayers() {
        return players;
    }

    public void setPlayers(List<String> players) {
        this.players = players != null ? players : new ArrayList<>();
    }

    public List<Map<String, Object>> getRooms() {
        return rooms;
    }

    public void setRooms(List<Map<String, Object>> rooms) {
        this.rooms = rooms != null ? rooms : new ArrayList<>();
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra != null ? extra : new LinkedHashMap<>();
    }

    @Override
    public String toString() {
        return "Message{type=" + type + ", playerId=" + playerId + ", roomId=" + roomId + "}";
    }
}
