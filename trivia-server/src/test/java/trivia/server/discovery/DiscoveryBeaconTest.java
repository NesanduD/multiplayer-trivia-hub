package trivia.server.discovery;

import org.junit.jupiter.api.Test;
import trivia.server.config.ServerConfig;
import trivia.server.room.RoomManager;
import trivia.shared.discovery.DiscoveryMessage;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DiscoveryBeaconTest {

    @Test
    void beaconBroadcastsServerCoordinatesAndRoomCounts() throws Exception {
        ServerConfig config = ServerConfig.forTests(15200, 18200, 15201);
        RoomManager roomManager = new RoomManager(result -> {
        }, config);

        int testPort = 4446;
        String groupAddr = DiscoveryMessage.MULTICAST_GROUP;

        try (DiscoveryBeacon beacon = new DiscoveryBeacon(
                "BeaconTestHub", config.tcpPort(), config.httpPort(), roomManager, groupAddr, testPort, 200);
             MulticastSocket receiver = new MulticastSocket(testPort)) {
            receiver.setSoTimeout(3000);
            InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(groupAddr), testPort);
            boolean joined = false;
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                try {
                    if (nif.isUp() && nif.supportsMulticast()) {
                        receiver.joinGroup(group, nif);
                        joined = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // try next interface
                }
            }
            if (!joined) {
                return;
            }

            beacon.start();
            byte[] buf = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            receiver.receive(packet);

            String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            DiscoveryMessage msg = DiscoveryMessage.fromJson(json);
            assertEquals("BeaconTestHub", msg.getServerName());
            assertEquals(15200, msg.getTcpPort());
            assertEquals(18200, msg.getHttpPort());
            assertEquals(0, msg.getPlayers());
            assertEquals(0, msg.getRooms());
            assertNotNull(msg.getRoomSummaries());
        } finally {
            roomManager.shutdown();
        }
    }
}
