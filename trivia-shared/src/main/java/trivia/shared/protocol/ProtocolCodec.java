package trivia.shared.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Length-prefixed JSON framing for TCP.
 * Wire format: 4-byte big-endian length + UTF-8 JSON body.
 */
public final class ProtocolCodec {
    public static final int MAX_FRAME_BYTES = 64 * 1024;
    public static final int HEADER_BYTES = 4;

    private static final Gson GSON = new GsonBuilder().create();

    private ProtocolCodec() {
    }

    public static byte[] encode(Message message) {
        if (message == null || message.getType() == null) {
            throw new IllegalArgumentException("Message and type are required");
        }
        byte[] body = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame too large: " + body.length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + body.length);
        buffer.putInt(body.length);
        buffer.put(body);
        return buffer.array();
    }

    public static Message decode(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("Empty frame body");
        }
        if (body.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame too large: " + body.length);
        }
        try {
            Message message = GSON.fromJson(new String(body, StandardCharsets.UTF_8), Message.class);
            if (message == null || message.getType() == null) {
                throw new IllegalArgumentException("Missing message type");
            }
            return message;
        } catch (JsonSyntaxException ex) {
            throw new IllegalArgumentException("Invalid JSON frame", ex);
        }
    }

    public static void writeFrame(OutputStream out, Message message) throws IOException {
        out.write(encode(message));
        out.flush();
    }

    public static Message readFrame(InputStream in) throws IOException {
        byte[] header = in.readNBytes(HEADER_BYTES);
        if (header.length == 0) {
            return null; // EOF
        }
        if (header.length < HEADER_BYTES) {
            throw new IOException("Incomplete frame header");
        }
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            throw new IOException("Incomplete frame body");
        }
        return decode(body);
    }

    /**
     * Extract zero or more complete frames from a NIO receive buffer.
     * Remaining incomplete bytes are compacted to the start of the buffer.
     */
    public static List<Message> drainFrames(ByteBuffer buffer) {
        List<Message> messages = new ArrayList<>();
        buffer.flip();
        while (buffer.remaining() >= HEADER_BYTES) {
            buffer.mark();
            int length = buffer.getInt();
            if (length <= 0 || length > MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("Invalid frame length: " + length);
            }
            if (buffer.remaining() < length) {
                buffer.reset();
                break;
            }
            byte[] body = new byte[length];
            buffer.get(body);
            messages.add(decode(body));
        }
        buffer.compact();
        return messages;
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static Gson gson() {
        return GSON;
    }
}
