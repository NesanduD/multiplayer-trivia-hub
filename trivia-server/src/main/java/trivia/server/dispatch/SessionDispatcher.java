package trivia.server.dispatch;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Offloads game/room handling from I/O threads while preserving per-session FIFO order.
 * Selector / WS reader threads only enqueue work; a shared pool runs handlers.
 */
public final class SessionDispatcher implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(SessionDispatcher.class.getName());

    private final ExecutorService workers;
    private final Object lock = new Object();
    private final Map<String, ArrayDeque<Runnable>> queues = new HashMap<>();
    private final Map<String, Boolean> running = new HashMap<>();

    public SessionDispatcher(int workerCount) {
        int n = Math.max(1, workerCount);
        this.workers = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "session-dispatch");
            t.setDaemon(true);
            return t;
        });
    }

    public void submit(String sessionKey, Runnable task) {
        Objects.requireNonNull(sessionKey, "sessionKey");
        Objects.requireNonNull(task, "task");
        boolean startWorker;
        synchronized (lock) {
            ArrayDeque<Runnable> queue = queues.computeIfAbsent(sessionKey, k -> new ArrayDeque<>());
            queue.addLast(wrap(sessionKey, task));
            startWorker = !Boolean.TRUE.equals(running.get(sessionKey));
            if (startWorker) {
                running.put(sessionKey, true);
            }
        }
        if (startWorker) {
            workers.execute(() -> drain(sessionKey));
        }
    }

    private Runnable wrap(String sessionKey, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Dispatch error for session " + sessionKey, ex);
            }
        };
    }

    private void drain(String sessionKey) {
        while (true) {
            Runnable next;
            synchronized (lock) {
                ArrayDeque<Runnable> queue = queues.get(sessionKey);
                if (queue == null || queue.isEmpty()) {
                    running.put(sessionKey, false);
                    queues.remove(sessionKey);
                    return;
                }
                next = queue.pollFirst();
            }
            next.run();
        }
    }

    /** Drop pending work when a transport is gone (optional cleanup). */
    public void clear(String sessionKey) {
        synchronized (lock) {
            queues.remove(sessionKey);
            running.put(sessionKey, false);
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
