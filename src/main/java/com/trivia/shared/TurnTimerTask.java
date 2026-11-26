package com.trivia.shared;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public class TurnTimerTask implements Callable<String> {
    private final AtomicBoolean answered;
    private final int timeLimitSec;

    public TurnTimerTask(AtomicBoolean answered, int timeLimitSec) {
        this.answered = answered;
        this.timeLimitSec = timeLimitSec;
    }

    @Override
    public String call() throws Exception {
        int totalMillis = timeLimitSec * 1000;
        int elapsedMillis = 0;
        int interval = 100; // Check every 100 ms for high responsiveness

        while (elapsedMillis < totalMillis) {
            // Check if a player answered early
            if (answered.get()) {
                return "ANSWERED";
            }

            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                // Thread was interrupted (e.g. room closed or game ended)
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            }

            elapsedMillis += interval;
        }

        // Time ran out before anyone answered correctly
        return "TIMEOUT";
    }
}