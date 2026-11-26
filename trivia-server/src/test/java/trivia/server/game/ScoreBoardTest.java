package trivia.server.game;

import org.junit.jupiter.api.Test;
import trivia.shared.model.ScoreEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreBoardTest {

    @Test
    void synchronizedAddPointsIsThreadSafe() throws InterruptedException {
        ScoreBoard board = new ScoreBoard();
        board.register("p1", "Amal");

        int threads = 20;
        int incrementsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        board.addPoints("p1", 1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        List<ScoreEntry> snapshot = new ArrayList<>(board.snapshot());
        assertEquals(1, snapshot.size());
        assertEquals(threads * incrementsPerThread, snapshot.getFirst().getScore());
    }
}
