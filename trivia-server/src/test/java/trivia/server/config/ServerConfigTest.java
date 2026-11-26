package trivia.server.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {

    @Test
    void loadsClasspathDefaults() {
        ServerConfig config = ServerConfig.defaults();
        assertEquals(5000, config.tcpPort());
        assertEquals(8080, config.httpPort());
        assertEquals(5001, config.wsPort());
        assertTrue(config.heartbeatTimeoutMs() >= config.heartbeatIntervalMs());
        assertTrue(config.sessionRecoverWindowMs() > 0);
    }

    @Test
    void cliArgsOverridePorts() {
        ServerConfig config = ServerConfig.load(null, new String[]{"15000", "18080", "Demo", "15001"});
        assertEquals(15000, config.tcpPort());
        assertEquals(18080, config.httpPort());
        assertEquals("Demo", config.serverName());
        assertEquals(15001, config.wsPort());
    }
}
