package trivia.server.discovery;

import trivia.server.room.RoomManager;
import trivia.shared.discovery.DiscoveryMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UDP multicast server announcement (Member 02).
 * Uses DatagramSocket to send to a Class D multicast group.
 */
public class DiscoveryBeacon implements AutoCloseable, Runnable {
    private static final Logger LOG = Logger.getLogger(DiscoveryBeacon.class.getName());
    private static final long DEFAULT_INTERVAL_MS = 2000;
    private static final int MAX_DATAGRAM_BYTES = 1400;

    private final String serverName;
    private final int tcpPort;
    private final int httpPort;
    private final RoomManager roomManager;
    private final String multicastGroup;
    private final int multicastPort;
    private final long intervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private DatagramSocket socket;

    public DiscoveryBeacon(String serverName, int tcpPort, int httpPort, RoomManager roomManager) {
        this(serverName, tcpPort, httpPort, roomManager,
                DiscoveryMessage.MULTICAST_GROUP, DiscoveryMessage.MULTICAST_PORT, DEFAULT_INTERVAL_MS);
    }

    public DiscoveryBeacon(String serverName, int tcpPort, int httpPort, RoomManager roomManager,
                           String multicastGroup, int multicastPort, long intervalMs) {
        this.serverName = serverName;
        this.tcpPort = tcpPort;
        this.httpPort = httpPort;
        this.roomManager = roomManager;
        this.multicastGroup = multicastGroup;
        this.multicastPort = multicastPort;
        this.intervalMs = Math.max(200L, intervalMs);
    }

    public void start() throws IOException {
        socket = new DatagramSocket();
        socket.setBroadcast(true);
        running.set(true);
        thread = new Thread(this, "discovery-beacon");
        thread.setDaemon(true);
        thread.start();
        LOG.info(() -> "UDP discovery beacon started -> "
                + multicastGroup + ":" + multicastPort);
    }

    @Override
    public void run() {
        try {
            InetAddress group = InetAddress.getByName(multicastGroup);
            String host = resolveAdvertisedHost();
            while (running.get()) {
                try {
                    DiscoveryMessage announce = new DiscoveryMessage(
                            serverName,
                            host,
                            tcpPort,
                            httpPort,
                            roomManager.totalPlayers(),
                            roomManager.totalRooms());
                    announce.setRoomSummaries(roomManager.roomSummaries());
                    byte[] data = encodeBounded(announce);
                    DatagramPacket packet = new DatagramPacket(
                            data, data.length, group, multicastPort);
                    socket.send(packet);
                } catch (IOException ex) {
                    if (running.get()) {
                        LOG.log(Level.FINE, "Transient UDP discovery send failure: " + ex.getMessage());
                    }
                }
                Thread.sleep(intervalMs);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            if (running.get()) {
                LOG.log(Level.WARNING, "Discovery beacon failed", ex);
            }
        }
    }

    private static byte[] encodeBounded(DiscoveryMessage announce) {
        byte[] data = announce.toJson().getBytes(StandardCharsets.UTF_8);
        if (data.length <= MAX_DATAGRAM_BYTES || announce.getRoomSummaries().isEmpty()) {
            return data;
        }
        // Trim room summaries to stay within a single standard MTU datagram
        var trimmed = new java.util.ArrayList<>(announce.getRoomSummaries());
        while (!trimmed.isEmpty() && data.length > MAX_DATAGRAM_BYTES) {
            trimmed.remove(trimmed.size() - 1);
            announce.setRoomSummaries(trimmed);
            data = announce.toJson().getBytes(StandardCharsets.UTF_8);
        }
        return data;
    }

    private String resolveAdvertisedHost() throws IOException {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Falling back to localhost for beacon host", ex);
        }
        return InetAddress.getLocalHost().getHostAddress();
    }

    @Override
    public void close() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
        if (socket != null) {
            socket.close();
        }
    }
}
