package trivia.server.ws;

import trivia.shared.api.ClientSession;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.ProtocolCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Browser WebSocket client session bridged into the shared game protocol.
 */
public class WebSocketSession implements ClientSession, Runnable {
    private static final Logger LOG = Logger.getLogger(WebSocketSession.class.getName());
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final String connectionId = UUID.randomUUID().toString();
    private volatile long lastHeartbeatMs = System.currentTimeMillis();

    private String playerId;
    private String playerName;
    private String roomId;
    private MessageHandler handler;

    public interface MessageHandler {
        void onMessage(WebSocketSession session, Message message);

        void onDisconnect(WebSocketSession session, String reason);
    }

    public WebSocketSession(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public void setHandler(MessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public void touchHeartbeat() {
        lastHeartbeatMs = System.currentTimeMillis();
    }

    @Override
    public long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public boolean performHandshake() throws IOException {
        StringBuilder request = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            request.append((char) b);
            if (request.toString().endsWith("\r\n\r\n")) {
                break;
            }
            if (request.length() > 8192) {
                return false;
            }
        }
        Map<String, String> headers = parseHeaders(request.toString());
        String key = headers.get("sec-websocket-key");
        if (key == null || key.isBlank()) {
            return false;
        }
        String accept = acceptKey(key.trim());
        String response = """
                HTTP/1.1 101 Switching Protocols\r
                Upgrade: websocket\r
                Connection: Upgrade\r
                Sec-WebSocket-Accept: %s\r
                \r
                """.formatted(accept);
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    @Override
    public void run() {
        try {
            while (open.get() && !socket.isClosed()) {
                String payload = readTextFrame();
                if (payload == null) {
                    break;
                }
                if (payload.isEmpty()) {
                    continue;
                }
                Message message = ProtocolCodec.fromJson(payload, Message.class);
                if (message == null || message.getType() == null) {
                    send(Message.error("Invalid message"));
                    continue;
                }
                if (handler != null) {
                    handler.onMessage(this, message);
                }
            }
        } catch (Exception ex) {
            if (open.get()) {
                LOG.log(Level.FINE, "WebSocket session ended", ex);
            }
        } finally {
            String reason = "DISCONNECT";
            close();
            if (handler != null) {
                handler.onDisconnect(this, reason);
            }
        }
    }

    @Override
    public synchronized void send(Message message) {
        if (!isOpen()) {
            return;
        }
        try {
            writeTextFrame(ProtocolCodec.toJson(message));
        } catch (IOException ex) {
            LOG.log(Level.FINE, "Failed to send WebSocket message", ex);
            close();
        }
    }

    private String readTextFrame() throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null;
        }
        int opcode = b0 & 0x0F;
        int b1 = in.read();
        if (b1 == -1) {
            return null;
        }
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | (in.read() & 0xFF);
            }
        }
        if (len > 64 * 1024) {
            throw new IOException("WebSocket frame too large");
        }
        byte[] mask = new byte[4];
        if (masked) {
            if (in.readNBytes(mask, 0, 4) < 4) {
                return null;
            }
        }
        byte[] payload = in.readNBytes((int) len);
        if (payload.length < len) {
            return null;
        }
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
        if (opcode == 0x8) { // close
            return null;
        }
        if (opcode == 0x9) { // ping -> pong
            writeControlFrame((byte) 0xA, payload);
            return "";
        }
        if (opcode == 0xA) { // pong
            return "";
        }
        if (opcode != 0x1) {
            return "";
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private synchronized void writeTextFrame(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        writeFrame((byte) 0x81, payload);
    }

    private synchronized void writeControlFrame(byte opcode, byte[] payload) throws IOException {
        writeFrame((byte) (0x80 | (opcode & 0x0F)), payload == null ? new byte[0] : payload);
    }

    private void writeFrame(byte firstByte, byte[] payload) throws IOException {
        out.write(firstByte);
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length <= 65535) {
            out.write(126);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            long len = payload.length;
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        out.write(payload);
        out.flush();
    }

    private static Map<String, String> parseHeaders(String request) {
        Map<String, String> headers = new ConcurrentHashMap<>();
        String[] lines = request.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT),
                        line.substring(idx + 1).trim());
            }
        }
        return headers;
    }

    private static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    @Override
    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    @Override
    public String getPlayerName() {
        return playerName;
    }

    @Override
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    @Override
    public String getRoomId() {
        return roomId;
    }

    @Override
    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    @Override
    public boolean isOpen() {
        return open.get() && !socket.isClosed();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
