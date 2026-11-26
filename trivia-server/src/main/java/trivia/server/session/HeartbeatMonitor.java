package trivia.server.session;

import trivia.server.config.ServerConfig;
import trivia.shared.api.ClientSession;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Detects idle transports (no app-level PING / traffic) and expired soft-disconnect seats.
 * Complements TCP close detection: half-open sockets may stay up without application traffic.
 */
public final class HeartbeatMonitor implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(HeartbeatMonitor.class.getName());

    private final SessionRegistry registry;
    private final ServerConfig config;
    private final BiConsumer<ClientSession, String> onIdleTimeout;
    private final Runnable onRecoverExpirySweep;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    public HeartbeatMonitor(SessionRegistry registry,
                            ServerConfig config,
                            ScheduledExecutorService scheduler,
                            BiConsumer<ClientSession, String> onIdleTimeout,
                            Runnable onRecoverExpirySweep) {
        this.registry = registry;
        this.config = config;
        this.scheduler = scheduler;
        this.onIdleTimeout = onIdleTimeout;
        this.onRecoverExpirySweep = onRecoverExpirySweep;
    }

    public void start() {
        long period = Math.max(100L, Math.min(config.heartbeatIntervalMs(), config.heartbeatTimeoutMs() / 2));
        future = scheduler.scheduleAtFixedRate(this::tick, period, period, TimeUnit.MILLISECONDS);
        LOG.info(() -> "Heartbeat monitor started (timeout=" + config.heartbeatTimeoutMs()
                + "ms, recover=" + config.sessionRecoverWindowMs() + "ms)");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long timeout = config.heartbeatTimeoutMs();
        for (ClientSession session : registry.liveTransports()) {
            if (!session.isOpen()) {
                continue;
            }
            long last = session.getLastHeartbeatMs();
            if (last > 0 && now - last > timeout) {
                LOG.info(() -> "Idle timeout for " + session.getPlayerName()
                        + " connection=" + session.getConnectionId());
                onIdleTimeout.accept(session, "HEARTBEAT_TIMEOUT");
            }
        }
        onRecoverExpirySweep.run();
    }

    @Override
    public void close() {
        if (future != null) {
            future.cancel(false);
        }
    }
}
