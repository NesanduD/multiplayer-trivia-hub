package trivia.server.nio;

import trivia.shared.protocol.Message;
import trivia.shared.protocol.ProtocolCodec;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Per-connection receive buffer that assembles length-prefixed frames.
 * Uses flip/compact exactly as taught in the NIO lecture.
 */
public class NioFrameBuffer {
    private final ByteBuffer buffer = ByteBuffer.allocate(ProtocolCodec.MAX_FRAME_BYTES + ProtocolCodec.HEADER_BYTES);

    public ByteBuffer buffer() {
        return buffer;
    }

    public List<Message> drain() {
        return ProtocolCodec.drainFrames(buffer);
    }

    public void clear() {
        buffer.clear();
    }
}
