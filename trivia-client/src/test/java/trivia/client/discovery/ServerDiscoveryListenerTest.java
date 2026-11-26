package trivia.client.discovery;

import org.junit.jupiter.api.Test;
import trivia.shared.discovery.DiscoveryMessage;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDiscoveryListenerTest {

    @Test
    void discoveryMessageRoundTripsWithRoomSummariesAndLegacyJson() {
        DiscoveryMessage msg = new DiscoveryMessage("Main-Hub", "192.168.1.10", 5000, 8080, 3, 1);
        msg.setRoomSummaries(List.of(Map.of(
                "roomId", "r-1234",
                "roomName", "Science",
                "players", 3,
                "maxPlayers", 4,
                "status", "WAITING")));

        DiscoveryMessage decoded = DiscoveryMessage.fromJson(msg.toJson());
        assertEquals("Main-Hub", decoded.getServerName());
        assertEquals("192.168.1.10", decoded.getHost());
        assertEquals(5000, decoded.getTcpPort());
        assertEquals(3, decoded.getPlayers());
        assertEquals(1, decoded.getRooms());
        assertEquals(1, decoded.getRoomSummaries().size());
        assertEquals("Science", decoded.getRoomSummaries().get(0).get("roomName"));

        // Backward compatibility: JSON without roomSummaries field
        String legacyJson = "{\"type\":\"SERVER_ANNOUNCE\",\"serverName\":\"Legacy\",\"host\":\"127.0.0.1\",\"tcpPort\":5000,\"httpPort\":8080,\"players\":1,\"rooms\":0}";
        DiscoveryMessage legacy = DiscoveryMessage.fromJson(legacyJson);
        assertEquals("Legacy", legacy.getServerName());
        assertTrue(legacy.getRoomSummaries().isEmpty());
    }

    @Test
    void tracksMultipleServersUpdatesCountsAndPrunesStale() throws Exception {
        try (ServerDiscoveryListener listener = new ServerDiscoveryListener("239.255.77.77", 4445, 500)) {
            List<List<DiscoveryMessage>> notifications = new CopyOnWriteArrayList<>();
            listener.addListener(notifications::add);

            InetAddress sender = InetAddress.getByName("127.0.0.1");
            DiscoveryMessage alpha = new DiscoveryMessage("Alpha", "192.168.1.20", 5000, 8080, 2, 1);
            DiscoveryMessage beta = new DiscoveryMessage("Beta", "192.168.1.21", 5002, 8081, 4, 2);

            listener.processAnnouncement(alpha.toJson(), sender);
            listener.processAnnouncement(beta.toJson(), sender);

            List<DiscoveryMessage> servers = listener.currentServers();
            assertEquals(2, servers.size());
            assertEquals("Alpha", servers.get(0).getServerName());
            assertEquals("Beta", servers.get(1).getServerName());

            // Updating same host:port replaces in place without duplicating
            DiscoveryMessage alphaUpdated = new DiscoveryMessage("Alpha", "192.168.1.20", 5000, 8080, 5, 2);
            listener.processAnnouncement(alphaUpdated.toJson(), sender);
            servers = listener.currentServers();
            assertEquals(2, servers.size());
            assertEquals(5, servers.get(0).getPlayers());

            // Wait past staleMs (500ms) and verify stale entries are pruned
            Thread.sleep(600);
            servers = listener.currentServers();
            assertTrue(servers.isEmpty(), "Outdated server announcements should be pruned");
            assertTrue(notifications.size() >= 3);
        }
    }

    @Test
    void rejectsInvalidDiscoveryAnnouncements() throws Exception {
        try (ServerDiscoveryListener listener = new ServerDiscoveryListener()) {
            InetAddress sender = InetAddress.getByName("127.0.0.1");
            DiscoveryMessage badPort = new DiscoveryMessage("Bad", "127.0.0.1", 0, 8080, 0, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> listener.processAnnouncement(badPort.toJson(), sender));
            assertThrows(IllegalArgumentException.class,
                    () -> listener.processAnnouncement("{\"type\":\"OTHER\"}", sender));
        }
    }
}
