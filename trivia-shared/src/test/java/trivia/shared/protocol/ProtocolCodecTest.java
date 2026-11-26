package trivia.shared.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCodecTest {

    @Test
    void encodeAndDecodeRoundTrip() {
        Message original = Message.of(MessageType.HELLO);
        original.setPlayerName("Amal");
        original.setClientId("client-1");

        byte[] frame = ProtocolCodec.encode(original);
        int length = ByteBuffer.wrap(frame, 0, 4).getInt();
        byte[] body = new byte[length];
        System.arraycopy(frame, 4, body, 0, length);

        Message decoded = ProtocolCodec.decode(body);
        assertEquals(MessageType.HELLO, decoded.getType());
        assertEquals("Amal", decoded.getPlayerName());
        assertEquals("client-1", decoded.getClientId());
    }

    @Test
    void drainFramesHandlesPartialThenComplete() {
        Message first = Message.of(MessageType.CHAT);
        first.setText("hello");
        byte[] frame = ProtocolCodec.encode(first);

        ByteBuffer buffer = ByteBuffer.allocate(Math.max(512, frame.length + 64));
        int splitAt = Math.min(6, frame.length - 1);
        buffer.put(frame, 0, splitAt); // partial frame
        List<Message> none = ProtocolCodec.drainFrames(buffer);
        assertTrue(none.isEmpty());

        buffer.put(frame, splitAt, frame.length - splitAt);
        List<Message> messages = ProtocolCodec.drainFrames(buffer);
        assertEquals(1, messages.size());
        assertEquals("hello", messages.getFirst().getText());
    }

    @Test
    void rejectsOversizedLength() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(ProtocolCodec.MAX_FRAME_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> ProtocolCodec.drainFrames(buffer));
    }
}
