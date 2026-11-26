package trivia.server;

import trivia.shared.discovery.DiscoveryMessage;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;
import trivia.shared.protocol.ProtocolCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.UUID;

/**
 * Manual/CI-friendly demo script: discovery + TCP gameplay + HTTP stats.
 * Run against a live server: mvn -pl trivia-server -DskipTests exec:java not required;
 * invoke via: java -cp ... trivia.server.EndToEndDemo
 */
public final class EndToEndDemo {
    private EndToEndDemo() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int tcpPort = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        int httpPort = args.length > 2 ? Integer.parseInt(args[2]) : 8080;

        System.out.println("== UDP discovery sample ==");
        try (MulticastSocket socket = new MulticastSocket(DiscoveryMessage.MULTICAST_PORT)) {
            socket.setSoTimeout(4000);
            InetAddress group = InetAddress.getByName(DiscoveryMessage.MULTICAST_GROUP);
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (nif.isUp() && !nif.isLoopback() && nif.supportsMulticast()) {
                    try {
                        socket.joinGroup(new InetSocketAddress(group, DiscoveryMessage.MULTICAST_PORT), nif);
                    } catch (Exception ignored) {
                        // try next
                    }
                }
            }
            byte[] buf = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            DiscoveryMessage announce = DiscoveryMessage.fromJson(json);
            System.out.println("Heard beacon: " + announce);
            host = announce.getHost() == null ? host : announce.getHost();
            tcpPort = announce.getTcpPort() == 0 ? tcpPort : announce.getTcpPort();
            httpPort = announce.getHttpPort() == 0 ? httpPort : announce.getHttpPort();
        }

        System.out.println("== TCP HELLO / CREATE_ROOM / START_GAME ==");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, tcpPort), 3000);
            var out = new BufferedOutputStream(socket.getOutputStream());
            var in = new BufferedInputStream(socket.getInputStream());

            Message hello = Message.of(MessageType.HELLO);
            hello.setPlayerName("DemoPlayer");
            hello.setClientId(UUID.randomUUID().toString());
            ProtocolCodec.writeFrame(out, hello);

            Message welcome = readUntil(in, MessageType.WELCOME);
            System.out.println("WELCOME playerId=" + welcome.getPlayerId());

            Message create = Message.of(MessageType.CREATE_ROOM);
            create.setRoomName("Demo Room");
            create.setMaxPlayers(4);
            ProtocolCodec.writeFrame(out, create);
            Message created = readUntil(in, MessageType.ROOM_CREATED);
            System.out.println("ROOM_CREATED id=" + created.getRoomId());

            Message start = Message.of(MessageType.START_GAME);
            start.setRoomId(created.getRoomId());
            ProtocolCodec.writeFrame(out, start);

            Message question = readUntil(in, MessageType.QUESTION);
            System.out.println("QUESTION: " + question.getText());

            Message answer = Message.of(MessageType.ANSWER);
            answer.setRoomId(created.getRoomId());
            answer.setQIndex(question.getQIndex());
            answer.setOptionIndex(0);
            ProtocolCodec.writeFrame(out, answer);
            Message result = readUntil(in, MessageType.ANSWER_RESULT);
            System.out.println("ANSWER_RESULT correct=" + result.getCorrect() + " points=" + result.getPoints());
        }

        System.out.println("== HTTP GET /api/leaderboard ==");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://" + host + ":" + httpPort + "/api/leaderboard"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("HTTP " + response.statusCode() + " " + response.body());
        System.out.println("Demo finished OK");
    }

    private static Message readUntil(BufferedInputStream in, MessageType expected) throws Exception {
        for (int i = 0; i < 20; i++) {
            Message message = ProtocolCodec.readFrame(in);
            if (message != null && message.getType() == expected) {
                return message;
            }
        }
        throw new IllegalStateException("Did not receive " + expected);
    }
}
