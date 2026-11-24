package trivia;

import org.junit.jupiter.api.Test;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the buffer lecture without a socket.
 * compact() must keep an unfinished command, flip() must expose a finished line,
 * and readLine()'s trailing '\r' rule must leave the command text clean.
 */
class ClientSessionFramingTest {

    /** NIO worksheet: "LOGIN Amal\nPASS" arrives in two reads. The first piece stays buffered. */
    @Test
    void partialLineIsReassembledWithCompact() throws Exception {
        try (SessionFixture fixture = SessionFixture.open()) {
            ClientSession session = fixture.session;
            assertTrue(session.offerBytes("JOIN:room:".getBytes(StandardCharsets.UTF_8)).isEmpty());
            assertEquals(List.of("JOIN:room:ann"),
                    session.offerBytes("ann\n".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** One channel.read can contain several newline-terminated commands. Both must come out. */
    @Test
    void twoLinesInOneBufferBothComeOut() throws Exception {
        try (SessionFixture fixture = SessionFixture.open()) {
            List<String> lines = fixture.session.offerBytes("PING\nLIST\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(List.of("PING", "LIST"), lines);
        }
    }

    /** TCP readLine strips one trailing CR. An embedded CR is part of the text and stays. */
    @Test
    void trailingCarriageReturnIsStrippedOnce() throws Exception {
        try (SessionFixture fixture = SessionFixture.open()) {
            ClientSession session = fixture.session;
            assertEquals(List.of("PING"), session.offerBytes("PING\r\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals(List.of("A\rB"), session.offerBytes("A\rB\n".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Decode only a finished line, so a character split across two reads is not corrupted. */
    @Test
    void splitUtf8CharacterStaysIntactUntilTheLineEnds() throws Exception {
        try (SessionFixture fixture = SessionFixture.open()) {
            ClientSession session = fixture.session;
            assertTrue(session.offerBytes(new byte[] {(byte) 0xC3}).isEmpty());
            assertEquals(List.of("é"), session.offerBytes(new byte[] {(byte) 0xA9, '\n'}));
        }
    }

    /** The fixed 4 KB capacity accepts a line that fills the buffer when the next byte is newline. */
    @Test
    void lineOfExactly4096BytesIsAccepted() throws Exception {
        try (SessionFixture fixture = SessionFixture.open()) {
            byte[] body = new byte[ClientSession.MAX_LINE_BYTES];
            Arrays.fill(body, (byte) 'A');
            byte[] framed = Arrays.copyOf(body, body.length + 1);
            framed[body.length] = '\n';

            List<String> lines = fixture.session.offerBytes(framed);

            assertEquals(1, lines.size());
            assertEquals(ClientSession.MAX_LINE_BYTES, lines.get(0).length());
            assertFalse(fixture.session.isOverLimit());
        }
    }

    /** DoS limit: one more byte with no newline is rejected instead of growing the buffer. */
    @Test
    void bytePast4096WithoutNewlineMarksTheSessionOverLimit() throws Exception {
        try (SessionFixture fixture = SessionFixture.open()) {
            byte[] body = new byte[ClientSession.MAX_LINE_BYTES + 1];
            Arrays.fill(body, (byte) 'B');

            assertTrue(fixture.session.offerBytes(body).isEmpty());
            assertTrue(fixture.session.isOverLimit());
        }
    }

    private static final class SessionFixture implements AutoCloseable {
        final SocketChannel channel;
        final Selector selector;
        final ClientSession session;

        private SessionFixture(SocketChannel channel, Selector selector, ClientSession session) {
            this.channel = channel;
            this.selector = selector;
            this.session = session;
        }

        static SessionFixture open() throws Exception {
            SocketChannel channel = SocketChannel.open();
            Selector selector = Selector.open();
            return new SessionFixture(channel, selector, new ClientSession(channel, selector));
        }

        @Override
        public void close() throws Exception {
            channel.close();
            selector.close();
        }
    }
}
