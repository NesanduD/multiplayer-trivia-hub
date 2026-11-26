package com.trivia.shared;

public class Question {
    private String questionText;
    private String[] options; // 4 options
    private int correctIndex; // 0 to 3
    private int timeLimitSec; // e.g. 15 seconds

    public Question(String questionText, String[] options, int correctIndex, int timeLimitSec) {
        this.questionText = questionText;
        this.options = options;
        this.correctIndex = correctIndex;
        this.timeLimitSec = timeLimitSec;
    }

    public String getQuestionText() {
        return questionText;
    }

    public String[] getOptions() {
        return options;
    }

    public int getCorrectIndex() {
        return correctIndex;
    }

    public int getTimeLimitSec() {
        return timeLimitSec;
    }

    // Formats question into the protocol string: text|opt0|opt1|opt2|opt3|time
    public String toProtocolString() {
        return questionText + "|" + options[0] + "|" + options[1] + "|" + options[2] + "|" + options[3] + "|" + timeLimitSec;
    }
}