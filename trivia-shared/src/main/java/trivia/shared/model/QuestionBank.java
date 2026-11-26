package trivia.shared.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QuestionBank {
    private List<Question> questions;
    private int currentIndex;

    public QuestionBank() {
        questions = new ArrayList<>();
        currentIndex = 0;
        loadSampleQuestions();
    }

    private void loadSampleQuestions() {
        questions.add(new Question(
            "What is the capital of Sri Lanka?",
            Arrays.asList("Kandy", "Colombo", "Sri Jayawardenepura Kotte", "Galle"),
            2
        ));
        questions.add(new Question(
            "Which data structure operates on a First-In, First-Out (FIFO) basis?",
            Arrays.asList("Stack", "Queue", "Tree", "Graph"),
            1
        ));
        questions.add(new Question(
            "In Java, which package is used for network programming?",
            Arrays.asList("java.util", "java.io", "java.net", "java.nio"),
            2
        ));
    }

    public synchronized Question getNextQuestion() {
        if (questions.isEmpty()) return null;
        Question q = questions.get(currentIndex);
        currentIndex = (currentIndex + 1) % questions.size();
        return q;
    }
}