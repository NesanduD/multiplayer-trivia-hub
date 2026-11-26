package trivia.client.discovery;

import trivia.shared.discovery.DiscoveryMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Joins the UDP multicast group and listens for SERVER_ANNOUNCE beacons (Member 02).
 * Discovery is best-effort: failure to join multicast must not block the UI.
 */
public class ServerDiscoveryListener implements AutoCloseable, Runnable {
    private static final Logger LOG = Logger.getLogger(ServerDiscoveryListener.class.getName());
    private static final long STALE_MS = 6000;

    private final Map<String, DiscoveryMessage> servers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<List<DiscoveryMessage>>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<NetworkInterface> joinedInterfaces = new ArrayList<>();

    private MulticastSocket socket;
    private InetAddress group;
    private Thread thread;
    private boolean active;

    public void addListener(Consumer<List<DiscoveryMessage>> listener) {
        listeners.add(listener);
    }

    public boolean isActive() {
        return active;
    }

    public void start() {
        try {
            group = InetAddress.getByName(DiscoveryMessage.MULTICAST_GROUP);
            socket = new MulticastSocket(DiscoveryMessage.MULTICAST_PORT);
            socket.setReuseAddress(true);
            boolean joined = joinAllInterfaces();
            if (!joined) {
                LOG.warning("Could not join multicast group on any interface. "
                        + "Use host 127.0.0.1 and port 5000 to connect manually.");
                closeSocketQuietly();
                active = false;
                return;
            }
            running.set(true);
            active = true;
            thread = new Thread(this, "server-discovery");
            thread.setDaemon(true);
            thread.start();
            LOG.info(() -> "Listening for UDP discovery on "
                    + DiscoveryMessage.MULTICAST_GROUP + ":" + DiscoveryMessage.MULTICAST_PORT);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "UDP discovery unavailable; connect manually via host/port", ex);
            closeSocketQuietly();
            active = false;
        }
    }

    private boolean joinAllInterfaces() {
        boolean joined = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                try {
                    if (!nif.isUp() || nif.isLoopback() || !nif.supportsMulticast() || !hasIpv4(nif)) {
                        continue;
                    }
                    socket.joinGroup(new InetSocketAddress(group, DiscoveryMessage.MULTICAST_PORT), nif);
                    joinedInterfaces.add(nif);
                    joined = true;
                    LOG.info(() -> "Joined multicast on interface " + nif.getName());
                } catch (Exception ex) {
                    LOG.fine(() -> "Skip interface " + nif.getName() + ": " + ex.getMessage());
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Failed enumerating network interfaces", ex);
        }
        return joined;
    }

    private static boolean hasIpv4(NetworkInterface nif) {
        Enumeration<InetAddress> addresses = nif.getInetAddresses();
        while (addresses.hasMoreElements()) {
            if (addresses.nextElement() instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void run() {
        byte[] buf = new byte[2048];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                DiscoveryMessage message = DiscoveryMessage.fromJson(json);
                if (message.getHost() == null || message.getHost().isBlank()) {
                    message.setHost(packet.getAddress().getHostAddress());
                }
                message.setTimestamp(System.currentTimeMillis());
                String key = message.getHost() + ":" + message.getTcpPort();
                servers.put(key, message);
                pruneAndNotify();
            } catch (IllegalArgumentException ex) {
                LOG.fine(() -> "Ignoring bad discovery datagram: " + ex.getMessage());
            } catch (IOException ex) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "Discovery receive failed", ex);
                }
            }
        }
    }

    public List<DiscoveryMessage> currentServers() {
        pruneAndNotify();
        List<DiscoveryMessage> list = new ArrayList<>(servers.values());
        list.sort(Comparator.comparing(DiscoveryMessage::getServerName));
        return list;
    }

    private void pruneAndNotify() {
        long now = System.currentTimeMillis();
        servers.entrySet().removeIf(e -> now - e.getValue().getTimestamp() > STALE_MS);
        List<DiscoveryMessage> snapshot = new ArrayList<>(servers.values());
        snapshot.sort(Comparator.comparing(DiscoveryMessage::getServerName));
        for (Consumer<List<DiscoveryMessage>> listener : listeners) {
            listener.accept(snapshot);
        }
    }

    private void closeSocketQuietly() {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // ignore
            }
            socket = null;
        }
    }

    @Override
    public void close() {
        running.set(false);
        active = false;
        if (socket != null) {
            for (NetworkInterface nif : joinedInterfaces) {
                try {
                    socket.leaveGroup(new InetSocketAddress(group, DiscoveryMessage.MULTICAST_PORT), nif);
                } catch (Exception ignored) {
                    // ignore leave failures
                }
            }
            closeSocketQuietly();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
}
