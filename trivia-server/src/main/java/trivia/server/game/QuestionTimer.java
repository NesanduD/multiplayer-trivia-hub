package trivia.server.game;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Schedules question deadlines using Callable/Future (lecture concurrency APIs).
 */
public class QuestionTimer {
    private final ScheduledExecutorService scheduler;

    public QuestionTimer(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public Future<Boolean> scheduleTimeout(long delayMs, Callable<Boolean> onTimeout) {
        return scheduler.schedule(onTimeout, delayMs, TimeUnit.MILLISECONDS);
    }
}
