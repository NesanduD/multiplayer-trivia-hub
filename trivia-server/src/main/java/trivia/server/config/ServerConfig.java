package trivia.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Externalized server settings loaded from classpath defaults, optional file, env, then CLI.
 */
public final class ServerConfig {
    private static final Logger LOG = Logger.getLogger(ServerConfig.class.getName());
    public static final String CLASSPATH_RESOURCE = "trivia-hub.properties";

    private final String serverName;
    private final int tcpPort;
    private final int httpPort;
    private final int wsPort;
    private final Path statsPath;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final long sessionRecoverWindowMs;
    private final int dispatchWorkers;
    private final int questionSeconds;
    private final int questionsPerMatch;
    private final int pointsPerCorrect;

    private ServerConfig(Builder b) {
        this.serverName = b.serverName;
        this.tcpPort = b.tcpPort;
        this.httpPort = b.httpPort;
        this.wsPort = b.wsPort;
        this.statsPath = b.statsPath;
        this.heartbeatIntervalMs = b.heartbeatIntervalMs;
        this.heartbeatTimeoutMs = b.heartbeatTimeoutMs;
        this.sessionRecoverWindowMs = b.sessionRecoverWindowMs;
        this.dispatchWorkers = b.dispatchWorkers;
        this.questionSeconds = b.questionSeconds;
        this.questionsPerMatch = b.questionsPerMatch;
        this.pointsPerCorrect = b.pointsPerCorrect;
    }

    public static ServerConfig defaults() {
        return load(null, new String[0]);
    }

    /**
     * Load order: classpath defaults → optional properties file → env → CLI args.
     * CLI: {@code [tcpPort] [httpPort] [serverName] [wsPort]}
     */
    public static ServerConfig load(Path optionalFile, String[] args) {
        Properties props = new Properties();
        try (InputStream in = ServerConfig.class.getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ex) {
            LOG.warning("Could not load classpath " + CLASSPATH_RESOURCE + ": " + ex.getMessage());
        }

        Path resolvedFile = optionalFile;
        if (resolvedFile == null) {
            String fromEnv = firstNonBlank(System.getenv("TRIVIA_CONFIG"), System.getProperty("trivia.config"));
            if (fromEnv != null) {
                resolvedFile = Path.of(fromEnv);
            } else if (Files.isRegularFile(Path.of("trivia-hub.properties"))) {
                resolvedFile = Path.of("trivia-hub.properties");
            }
        }
        final Path file = resolvedFile;
        if (file != null && Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                props.load(reader);
                LOG.info(() -> "Loaded config file " + file.toAbsolutePath());
            } catch (IOException ex) {
                LOG.warning("Could not load config file " + file + ": " + ex.getMessage());
            }
        }

        applyEnv(props);

        Builder b = new Builder()
                .serverName(props.getProperty("server.name", "Trivia-Hub"))
                .tcpPort(intProp(props, "server.tcpPort", 5000))
                .httpPort(intProp(props, "server.httpPort", 8080))
                .wsPort(intProp(props, "server.wsPort", 5001))
                .statsPath(Path.of(props.getProperty("server.statsPath", "data/match-history.json")))
                .heartbeatIntervalMs(longProp(props, "heartbeat.intervalMs", 5000))
                .heartbeatTimeoutMs(longProp(props, "heartbeat.timeoutMs", 20000))
                .sessionRecoverWindowMs(longProp(props, "session.recoverWindowMs", 60000))
                .dispatchWorkers(intProp(props, "dispatch.workers", 8))
                .questionSeconds(intProp(props, "game.questionSeconds", 15))
                .questionsPerMatch(intProp(props, "game.questionsPerMatch", 5))
                .pointsPerCorrect(intProp(props, "game.pointsPerCorrect", 10));

        if (args != null) {
            if (args.length > 0) {
                b.tcpPort(Integer.parseInt(args[0]));
            }
            if (args.length > 1) {
                b.httpPort(Integer.parseInt(args[1]));
            }
            if (args.length > 2) {
                b.serverName(args[2]);
            }
            if (args.length > 3) {
                b.wsPort(Integer.parseInt(args[3]));
            }
        }
        return b.build();
    }

    /** Test helper with short timeouts. */
    public static ServerConfig forTests(int tcpPort, int httpPort, int wsPort) {
        return new Builder()
                .serverName("Test")
                .tcpPort(tcpPort)
                .httpPort(httpPort)
                .wsPort(wsPort)
                .statsPath(Path.of("target", "test-match-history-" + tcpPort + ".json"))
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(800)
                .sessionRecoverWindowMs(2000)
                .dispatchWorkers(4)
                .questionSeconds(15)
                .questionsPerMatch(5)
                .pointsPerCorrect(10)
                .build();
    }

    private static void applyEnv(Properties props) {
        putEnv(props, "server.name", "TRIVIA_SERVER_NAME");
        putEnv(props, "server.tcpPort", "TRIVIA_TCP_PORT");
        putEnv(props, "server.httpPort", "TRIVIA_HTTP_PORT");
        putEnv(props, "server.wsPort", "TRIVIA_WS_PORT");
        putEnv(props, "server.statsPath", "TRIVIA_STATS_PATH");
        putEnv(props, "heartbeat.intervalMs", "TRIVIA_HEARTBEAT_INTERVAL_MS");
        putEnv(props, "heartbeat.timeoutMs", "TRIVIA_HEARTBEAT_TIMEOUT_MS");
        putEnv(props, "session.recoverWindowMs", "TRIVIA_SESSION_RECOVER_MS");
        putEnv(props, "dispatch.workers", "TRIVIA_DISPATCH_WORKERS");
    }

    private static void putEnv(Properties props, String key, String env) {
        String value = firstNonBlank(System.getenv(env), System.getProperty(env.toLowerCase().replace('_', '.')));
        if (value != null) {
            props.setProperty(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static int intProp(Properties props, String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static long longProp(Properties props, String key, long defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }

    public String serverName() {
        return serverName;
    }

    public int tcpPort() {
        return tcpPort;
    }

    public int httpPort() {
        return httpPort;
    }

    public int wsPort() {
        return wsPort;
    }

    public Path statsPath() {
        return statsPath;
    }

    public long heartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public long heartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    public long sessionRecoverWindowMs() {
        return sessionRecoverWindowMs;
    }

    public int dispatchWorkers() {
        return dispatchWorkers;
    }

    public int questionSeconds() {
        return questionSeconds;
    }

    public int questionsPerMatch() {
        return questionsPerMatch;
    }

    public int pointsPerCorrect() {
        return pointsPerCorrect;
    }

    public static final class Builder {
        private String serverName = "Trivia-Hub";
        private int tcpPort = 5000;
        private int httpPort = 8080;
        private int wsPort = 5001;
        private Path statsPath = Path.of("data", "match-history.json");
        private long heartbeatIntervalMs = 5000;
        private long heartbeatTimeoutMs = 20000;
        private long sessionRecoverWindowMs = 60000;
        private int dispatchWorkers = 8;
        private int questionSeconds = 15;
        private int questionsPerMatch = 5;
        private int pointsPerCorrect = 10;

        public Builder serverName(String serverName) {
            this.serverName = Objects.requireNonNull(serverName);
            return this;
        }

        public Builder tcpPort(int tcpPort) {
            this.tcpPort = tcpPort;
            return this;
        }

        public Builder httpPort(int httpPort) {
            this.httpPort = httpPort;
            return this;
        }

        public Builder wsPort(int wsPort) {
            this.wsPort = wsPort;
            return this;
        }

        public Builder statsPath(Path statsPath) {
            this.statsPath = Objects.requireNonNull(statsPath);
            return this;
        }

        public Builder heartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
            return this;
        }

        public Builder heartbeatTimeoutMs(long heartbeatTimeoutMs) {
            this.heartbeatTimeoutMs = heartbeatTimeoutMs;
            return this;
        }

        public Builder sessionRecoverWindowMs(long sessionRecoverWindowMs) {
            this.sessionRecoverWindowMs = sessionRecoverWindowMs;
            return this;
        }

        public Builder dispatchWorkers(int dispatchWorkers) {
            this.dispatchWorkers = Math.max(1, dispatchWorkers);
            return this;
        }

        public Builder questionSeconds(int questionSeconds) {
            this.questionSeconds = questionSeconds;
            return this;
        }

        public Builder questionsPerMatch(int questionsPerMatch) {
            this.questionsPerMatch = questionsPerMatch;
            return this;
        }

        public Builder pointsPerCorrect(int pointsPerCorrect) {
            this.pointsPerCorrect = pointsPerCorrect;
            return this;
        }

        public ServerConfig build() {
            if (heartbeatTimeoutMs < heartbeatIntervalMs) {
                throw new IllegalArgumentException("heartbeat.timeoutMs must be >= heartbeat.intervalMs");
            }
            return new ServerConfig(this);
        }
    }
}
