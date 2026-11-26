package trivia.client.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;
import trivia.shared.protocol.ProtocolCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTcpClientTest {
    private ServerSocket serverSocket;
    private GameTcpClient client;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test
    void validatesClientInputsBeforeSending() {
        client = new GameTcpClient();
        assertThrows(IllegalArgumentException.class, () -> client.connect("127.0.0.1", 5000, "  "));
        assertThrows(IllegalArgumentException.class, () -> client.connect("", 5000, "Alice"));
        assertThrows(IllegalArgumentException.class, () -> client.connect("127.0.0.1", 70000, "Alice"));
        assertThrows(IllegalArgumentException.class, () -> client.joinRoom(" "));
        assertThrows(IllegalArgumentException.class, () -> client.answer("r-1", 0, -1));
        assertThrows(IllegalArgumentException.class, () -> client.chat("  "));
    }

    @Test
    void connectHeartbeatCommandsAndDisconnectReconnectWork() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        AtomicInteger pingCount = new AtomicInteger();
        List<MessageType> receivedTypes = new CopyOnWriteArrayList<>();

        Thread serverThread = new Thread(() -> {
            for (int conn = 0; conn < 2; conn++) {
                try (Socket s = serverSocket.accept()) {
                    var in = new BufferedInputStream(s.getInputStream());
                    var out = new BufferedOutputStream(s.getOutputStream());
                    Message msg;
                    while ((msg = ProtocolCodec.readFrame(in)) != null) {
                        receivedTypes.add(msg.getType());
                        switch (msg.getType()) {
                            case HELLO -> {
                                Message welcome = Message.of(MessageType.WELCOME);
                                welcome.setPlayerId("p-1");
                                welcome.setPlayerName(msg.getPlayerName());
                                welcome.setSessionToken("tok-1");
                                welcome.setExtra(Map.of("heartbeatIntervalMs", 500, "heartbeatTimeoutMs", 2500));
                                ProtocolCodec.writeFrame(out, welcome);
                            }
                            case PING -> {
                                pingCount.incrementAndGet();
                                Message pong = Message.of(MessageType.PONG);
                                pong.setServerTime(System.currentTimeMillis());
                                ProtocolCodec.writeFrame(out, pong);
                            }
                            case CREATE_ROOM -> {
                                Message created = Message.of(MessageType.ROOM_CREATED);
                                created.setRoomId("r-1");
                                created.setRoomName(msg.getRoomName());
                                ProtocolCodec.writeFrame(out, created);
                            }
                            case LIST_ROOMS -> ProtocolCodec.writeFrame(out, Message.of(MessageType.ROOM_LIST));
                            case GOODBYE -> {
                                break;
                            }
                            default -> {
                            }
                        }
                        if (msg.getType() == MessageType.GOODBYE) {
                            break;
                        }
                    }
                } catch (IOException ignored) {
                    // closed
                }
            }
        }, "mock-tcp-server");
        serverThread.setDaemon(true);
        serverThread.start();

        client = new GameTcpClient();
        client.setAutoReconnect(false);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch roomCreated = new CountDownLatch(1);
        client.addStateListener(state -> {
            if (state == ConnectionState.REGISTERED) {
                registered.countDown();
            }
        });
        client.addMessageListener(m -> {
            if (m.getType() == MessageType.ROOM_CREATED) {
                roomCreated.countDown();
            }
        });

        client.connect("127.0.0.1", port, "Alice");
        assertTrue(registered.await(3, TimeUnit.SECONDS));
        assertEquals("p-1", client.getPlayerId());
        assertEquals("tok-1", client.getSessionToken());

        client.createRoom("Quiz", 4);
        assertTrue(roomCreated.await(3, TimeUnit.SECONDS));
        assertEquals("r-1", client.getCurrentRoomId());

        client.listRooms();
        client.chat("Hello room");
        client.answer("r-1", 0, 2);

        // Wait for at least 1 heartbeat PING
        Thread.sleep(700);
        assertTrue(pingCount.get() >= 1, "Expected periodic PING heartbeat");

        // Disconnect and reconnect (verifies heartbeatScheduler is not shut down by disconnect())
        client.disconnect();
        assertEquals(ConnectionState.DISCONNECTED, client.getState());

        CountDownLatch reconnected = new CountDownLatch(1);
        client.addStateListener(state -> {
            if (state == ConnectionState.REGISTERED) {
                reconnected.countDown();
            }
        });
        client.connect("127.0.0.1", port, "Alice");
        assertTrue(reconnected.await(3, TimeUnit.SECONDS));
        assertTrue(receivedTypes.contains(MessageType.GOODBYE));
        assertTrue(receivedTypes.contains(MessageType.CHAT));
        assertTrue(receivedTypes.contains(MessageType.ANSWER));
    }

    @Test
    void autoReconnectResumesSessionAfterUnexpectedDrop() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        CountDownLatch resumeReceived = new CountDownLatch(1);

        Thread serverThread = new Thread(() -> {
            try {
                // First connection: accept HELLO, send WELCOME, then drop socket abruptly
                try (Socket first = serverSocket.accept()) {
                    var in = new BufferedInputStream(first.getInputStream());
                    var out = new BufferedOutputStream(first.getOutputStream());
                    Message hello = ProtocolCodec.readFrame(in);
                    assertEquals(MessageType.HELLO, hello.getType());

                    Message welcome = Message.of(MessageType.WELCOME);
                    welcome.setPlayerId("p-99");
                    welcome.setPlayerName("Bob");
                    welcome.setSessionToken("resume-tok-99");
                    welcome.setExtra(Map.of("heartbeatIntervalMs", 5000, "heartbeatTimeoutMs", 20000));
                    ProtocolCodec.writeFrame(out, welcome);
                    Thread.sleep(100);
                }

                // Second connection: expect RESUME with same token
                try (Socket second = serverSocket.accept()) {
                    var in = new BufferedInputStream(second.getInputStream());
                    var out = new BufferedOutputStream(second.getOutputStream());
                    Message resume = ProtocolCodec.readFrame(in);
                    if (resume != null && resume.getType() == MessageType.RESUME
                            && "resume-tok-99".equals(resume.getSessionToken())) {
                        resumeReceived.countDown();
                        Message welcome = Message.of(MessageType.WELCOME);
                        welcome.setPlayerId("p-99");
                        welcome.setPlayerName("Bob");
                        welcome.setRoomId("r-saved");
                        welcome.setSessionToken("resume-tok-99b");
                        welcome.setExtra(Map.of("resumed", true));
                        ProtocolCodec.writeFrame(out, welcome);
                        Thread.sleep(300);
                    }
                }
            } catch (Exception ignored) {
                // test teardown
            }
        }, "mock-resume-server");
        serverThread.setDaemon(true);
        serverThread.start();

        client = new GameTcpClient();
        client.setReconnectBaseDelayMs(100);
        CountDownLatch resumedState = new CountDownLatch(1);
        client.addMessageListener(m -> {
            if (m.getType() == MessageType.WELCOME && "r-saved".equals(m.getRoomId())) {
                resumedState.countDown();
            }
        });

        client.connect("127.0.0.1", port, "Bob");
        assertTrue(resumeReceived.await(4, TimeUnit.SECONDS), "Client should send RESUME after unexpected drop");
        assertTrue(resumedState.await(4, TimeUnit.SECONDS), "Client should restore roomId on resumed WELCOME");
        assertEquals("r-saved", client.getCurrentRoomId());
    }
}
