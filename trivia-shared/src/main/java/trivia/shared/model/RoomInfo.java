package trivia.shared.model;

import java.util.ArrayList;
import java.util.List;

public class RoomInfo {
    private String roomId;
    private String roomName;
    private int maxPlayers;
    private String status;
    private List<String> players = new ArrayList<>();

    public RoomInfo() {
    }

    public RoomInfo(String roomId, String roomName, int maxPlayers, String status) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.maxPlayers = maxPlayers;
        this.status = status;
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

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getPlayers() {
        return players;
    }

    public void setPlayers(List<String> players) {
        this.players = players != null ? players : new ArrayList<>();
    }
}
