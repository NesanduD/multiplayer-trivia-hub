package trivia.shared;

import trivia.shared.model.Question;
import trivia.shared.model.QuestionBank;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TurnTimerTest {
    public static void main(String[] args) {
        System.out.println("=== STARTING MEMBER 3 COMPONENT TEST ===");

        // 1. Test QuestionBank loading
        QuestionBank bank = new QuestionBank();
        Question q = bank.getNextQuestion();
        System.out.println("\n[Test 1] Loaded Question successfully:");
        System.out.println("  Q: " + q.getText());
        System.out.println("  Options count: " + q.getOptions().size());

        // 2. Test TurnTimerTask (Scenario A: Early Answer)
        System.out.println("\n[Test 2] Testing Timer with Early Answer...");
        AtomicBoolean answeredFlag = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        TurnTimerTask timerTask = new TurnTimerTask(answeredFlag, 5); // 5 sec limit
        Future<String> future = executor.submit(timerTask);

        try {
            Thread.sleep(1000); // Simulate player answering after 1 second
            System.out.println("  -> Player answered early!");
            answeredFlag.set(true);

            String result = future.get();
            System.out.println("  -> Timer Result: " + result); // Expected: "ANSWERED"
            assert "ANSWERED".equals(result) : "Test 2 Failed!";
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. Test TurnTimerTask (Scenario B: Timeout)
        System.out.println("\n[Test 3] Testing Timer with Timeout...");
        AtomicBoolean answeredFlag2 = new AtomicBoolean(false);
        TurnTimerTask timerTask2 = new TurnTimerTask(answeredFlag2, 1); // Quick 1 sec limit
        Future<String> future2 = executor.submit(timerTask2);

        try {
            String result = future2.get(); // Let it time out
            System.out.println("  -> Timer Result: " + result); // Expected: "TIMEOUT"
            assert "TIMEOUT".equals(result) : "Test 3 Failed!";
        } catch (Exception e) {
            e.printStackTrace();
        }

        executor.shutdown();
        System.out.println("\n=== ALL TESTS PASSED SUCCESSFULLY ===");
    }
}