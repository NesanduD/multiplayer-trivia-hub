package trivia.client.discovery;

import trivia.shared.discovery.DiscoveryMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
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

    public void addListener(Consumer<List<DiscoveryMessage>> listener) {
        listeners.add(listener);
    }

    public void start() throws IOException {
        group = InetAddress.getByName(DiscoveryMessage.MULTICAST_GROUP);
        socket = new MulticastSocket(DiscoveryMessage.MULTICAST_PORT);
        socket.setReuseAddress(true);
        joinAllInterfaces();
        running.set(true);
        thread = new Thread(this, "server-discovery");
        thread.setDaemon(true);
        thread.start();
        LOG.info(() -> "Listening for UDP discovery on "
                + DiscoveryMessage.MULTICAST_GROUP + ":" + DiscoveryMessage.MULTICAST_PORT);
    }

    private void joinAllInterfaces() throws IOException {
        boolean joined = false;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface nif = interfaces.nextElement();
            try {
                if (!nif.isUp() || nif.isLoopback() || !nif.supportsMulticast()) {
                    continue;
                }
                socket.joinGroup(new InetSocketAddress(group, DiscoveryMessage.MULTICAST_PORT), nif);
                joinedInterfaces.add(nif);
                joined = true;
            } catch (SocketException | IllegalArgumentException ex) {
                LOG.fine(() -> "Skip interface " + nif.getName() + ": " + ex.getMessage());
            }
        }
        if (!joined) {
            // Last-resort deprecated join for constrained environments
            socket.joinGroup(group);
        }
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

    @Override
    public void close() {
        running.set(false);
        if (socket != null) {
            for (NetworkInterface nif : joinedInterfaces) {
                try {
                    socket.leaveGroup(new InetSocketAddress(group, DiscoveryMessage.MULTICAST_PORT), nif);
                } catch (Exception ignored) {
                    // ignore leave failures
                }
            }
            socket.close();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
}
