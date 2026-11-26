package trivia.shared.discovery;

import trivia.shared.protocol.ProtocolCodec;

/**
 * UDP multicast beacon payload (one datagram = one JSON message).
 */
public class DiscoveryMessage {
    public static final String TYPE_ANNOUNCE = "SERVER_ANNOUNCE";
    public static final String MULTICAST_GROUP = "239.255.77.77";
    public static final int MULTICAST_PORT = 4445;

    private String type = TYPE_ANNOUNCE;
    private String serverName;
    private String host;
    private int tcpPort;
    private int httpPort;
    private int players;
    private int rooms;
    private long timestamp;

    public DiscoveryMessage() {
    }

    public DiscoveryMessage(String serverName, String host, int tcpPort, int httpPort, int players, int rooms) {
        this.serverName = serverName;
        this.host = host;
        this.tcpPort = tcpPort;
        this.httpPort = httpPort;
        this.players = players;
        this.rooms = rooms;
        this.timestamp = System.currentTimeMillis();
    }

    public String toJson() {
        return ProtocolCodec.toJson(this);
    }

    public static DiscoveryMessage fromJson(String json) {
        DiscoveryMessage message = ProtocolCodec.fromJson(json, DiscoveryMessage.class);
        if (message == null || !TYPE_ANNOUNCE.equals(message.getType())) {
            throw new IllegalArgumentException("Not a SERVER_ANNOUNCE message");
        }
        return message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getPlayers() {
        return players;
    }

    public void setPlayers(int players) {
        this.players = players;
    }

    public int getRooms() {
        return rooms;
    }

    public void setRooms(int rooms) {
        this.rooms = rooms;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return serverName + " @ " + host + ":" + tcpPort + " (players=" + players + ", rooms=" + rooms + ")";
    }
}
