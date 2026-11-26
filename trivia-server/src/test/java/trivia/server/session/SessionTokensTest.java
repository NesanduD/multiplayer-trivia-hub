package trivia.server.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTokensTest {

    @Test
    void hashesAreStableAndNotRawToken() {
        String raw = SessionTokens.generateRawToken();
        String hash1 = SessionTokens.sha256Hex(raw);
        String hash2 = SessionTokens.sha256Hex(raw);
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
        assertNotEquals(raw, hash1);
        assertTrue(raw.length() >= 32);
    }
}
