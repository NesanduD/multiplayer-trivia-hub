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
    private static final long INTERVAL_MS = 2000;

    private final String serverName;
    private final int tcpPort;
    private final int httpPort;
    private final RoomManager roomManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private DatagramSocket socket;

    public DiscoveryBeacon(String serverName, int tcpPort, int httpPort, RoomManager roomManager) {
        this.serverName = serverName;
        this.tcpPort = tcpPort;
        this.httpPort = httpPort;
        this.roomManager = roomManager;
    }

    public void start() throws IOException {
        socket = new DatagramSocket();
        socket.setBroadcast(true);
        running.set(true);
        thread = new Thread(this, "discovery-beacon");
        thread.setDaemon(true);
        thread.start();
        LOG.info(() -> "UDP discovery beacon started -> "
                + DiscoveryMessage.MULTICAST_GROUP + ":" + DiscoveryMessage.MULTICAST_PORT);
    }

    @Override
    public void run() {
        try {
            InetAddress group = InetAddress.getByName(DiscoveryMessage.MULTICAST_GROUP);
            String host = resolveAdvertisedHost();
            while (running.get()) {
                DiscoveryMessage announce = new DiscoveryMessage(
                        serverName,
                        host,
                        tcpPort,
                        httpPort,
                        roomManager.totalPlayers(),
                        roomManager.totalRooms());
                byte[] data = announce.toJson().getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(
                        data, data.length, group, DiscoveryMessage.MULTICAST_PORT);
                socket.send(packet);
                Thread.sleep(INTERVAL_MS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            if (running.get()) {
                LOG.log(Level.WARNING, "Discovery beacon failed", ex);
            }
        }
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
