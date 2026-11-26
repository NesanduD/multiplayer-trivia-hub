package trivia.server.dispatch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionDispatcherTest {

    @Test
    void preservesPerSessionOrderUnderLoad() throws Exception {
        try (SessionDispatcher dispatcher = new SessionDispatcher(4)) {
            int n = 50;
            CountDownLatch done = new CountDownLatch(n);
            List<Integer> order = new ArrayList<>();

            for (int i = 0; i < n; i++) {
                final int value = i;
                dispatcher.submit("session-a", () -> {
                    synchronized (order) {
                        order.add(value);
                    }
                    done.countDown();
                });
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < n; i++) {
                assertEquals(i, order.get(i));
            }
        }
    }
}
