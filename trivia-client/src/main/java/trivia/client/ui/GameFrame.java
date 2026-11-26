package trivia.client.ui;

import trivia.client.net.GameTcpClient;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GameFrame extends JFrame {
    private final GameTcpClient tcpClient;
    private final String roomId;
    private final LobbyFrame lobbyFrame;

    private final JLabel roomLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("Waiting for players...");
    private final JLabel timerLabel = new JLabel("Time: -");
    private final JLabel questionLabel = new JLabel("Question will appear here");
    private final JTextArea scoreArea = new JTextArea(8, 18);
    private final JTextArea chatArea = new JTextArea(8, 30);
    private final JTextField chatField = new JTextField(20);
    private final JPanel optionsPanel = new JPanel(new GridLayout(0, 1, 4, 4));
    private final ButtonGroup optionGroup = new ButtonGroup();
    private final List<JRadioButton> optionButtons = new ArrayList<>();
    private final JButton startButton = new JButton("Start Game");
    private final JButton submitButton = new JButton("Submit Answer");
    private final JButton leaveButton = new JButton("Leave Room");

    private int currentQIndex = -1;
    private Long endsAt;
    private final Timer countdownTimer;

    public GameFrame(GameTcpClient tcpClient, String roomId, String roomName, LobbyFrame lobbyFrame) {
        super("Trivia Hub — " + roomName);
        this.tcpClient = tcpClient;
        this.roomId = roomId;
        this.lobbyFrame = lobbyFrame;
        questionLabel.setFont(questionLabel.getFont().deriveFont(Font.BOLD, 16f));
        scoreArea.setEditable(false);
        chatArea.setEditable(false);
        buildUi(roomName);
        countdownTimer = new Timer(250, e -> updateTimer());
        countdownTimer.start();
        setSize(860, 560);
        setLocationRelativeTo(lobbyFrame);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                leaveRoom();
            }
        });
    }

    private void buildUi(String roomName) {
        roomLabel.setText("Room: " + roomName + " (" + roomId + ")");

        JPanel top = new JPanel(new BorderLayout());
        top.add(roomLabel, BorderLayout.WEST);
        top.add(statusLabel, BorderLayout.CENTER);
        top.add(timerLabel, BorderLayout.EAST);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        center.add(questionLabel, BorderLayout.NORTH);
        center.add(optionsPanel, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        startButton.addActionListener(e -> startGame());
        submitButton.addActionListener(e -> submitAnswer());
        leaveButton.addActionListener(e -> leaveRoom());
        submitButton.setEnabled(false);
        actions.add(startButton);
        actions.add(submitButton);
        actions.add(leaveButton);
        center.add(actions, BorderLayout.SOUTH);

        JPanel side = new JPanel(new BorderLayout(8, 8));
        scoreArea.setBorder(BorderFactory.createTitledBorder("Leaderboard"));
        side.add(new JScrollPane(scoreArea), BorderLayout.CENTER);

        JPanel chatPanel = new JPanel(new BorderLayout(4, 4));
        chatPanel.setBorder(BorderFactory.createTitledBorder("Chat"));
        chatPanel.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        JPanel chatInput = new JPanel(new BorderLayout(4, 4));
        JButton sendChat = new JButton("Send");
        sendChat.addActionListener(e -> sendChat());
        chatField.addActionListener(e -> sendChat());
        chatInput.add(chatField, BorderLayout.CENTER);
        chatInput.add(sendChat, BorderLayout.EAST);
        chatPanel.add(chatInput, BorderLayout.SOUTH);

        JPanel right = new JPanel(new GridLayout(2, 1, 8, 8));
        right.add(side);
        right.add(chatPanel);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(top, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(right, BorderLayout.EAST);
        setContentPane(root);
    }

    public void onMessage(Message message) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onMessage(message));
            return;
        }
        switch (message.getType()) {
            case ROOM_STATE -> {
                statusLabel.setText("Status: " + message.getStatus()
                        + " | Players: " + String.join(", ", message.getPlayers()));
                startButton.setEnabled("WAITING".equals(message.getStatus()));
            }
            case QUESTION -> showQuestion(message);
            case ANSWER_RESULT -> {
                String result = Boolean.TRUE.equals(message.getCorrect())
                        ? "Correct! +" + message.getPoints()
                        : "Incorrect / already answered";
                statusLabel.setText(result);
                updateScores(message.getScores());
            }
            case SCORE_UPDATE, GAME_OVER -> {
                updateScores(message.getScores());
                if (message.getType() == MessageType.GAME_OVER) {
                    statusLabel.setText("Game over — check leaderboard / stats");
                    submitButton.setEnabled(false);
                    startButton.setEnabled(true);
                    questionLabel.setText("Match finished. Start again when ready.");
                    optionsPanel.removeAll();
                    optionsPanel.revalidate();
                    optionsPanel.repaint();
                }
            }
            case CHAT -> chatArea.append(message.getFrom() + ": " + message.getText() + "\n");
            case PLAYER_LEFT -> chatArea.append("* " + message.getPlayerId() + " left (" + message.getReason() + ")\n");
            default -> {
                // ignore
            }
        }
    }

    private void showQuestion(Message message) {
        currentQIndex = message.getQIndex() == null ? -1 : message.getQIndex();
        endsAt = message.getEndsAt();
        questionLabel.setText("Q" + (currentQIndex + 1) + ": " + message.getText());
        optionsPanel.removeAll();
        optionButtons.clear();
        optionGroup.clearSelection();
        List<String> options = message.getOptions();
        for (int i = 0; i < options.size(); i++) {
            JRadioButton button = new JRadioButton((char) ('A' + i) + ". " + options.get(i));
            button.setActionCommand(String.valueOf(i));
            optionGroup.add(button);
            optionButtons.add(button);
            optionsPanel.add(button);
        }
        optionsPanel.revalidate();
        optionsPanel.repaint();
        submitButton.setEnabled(true);
        startButton.setEnabled(false);
        statusLabel.setText("Answer before the timer runs out");
        updateTimer();
    }

    private void updateScores(List<Map<String, Object>> scores) {
        if (scores == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int rank = 1;
        for (Map<String, Object> score : scores) {
            sb.append(rank++)
                    .append(". ")
                    .append(score.get("playerName"))
                    .append(" — ")
                    .append(score.get("score"))
                    .append('\n');
        }
        scoreArea.setText(sb.toString());
    }

    private void updateTimer() {
        if (endsAt == null) {
            timerLabel.setText("Time: -");
            return;
        }
        long remaining = Math.max(0, endsAt - System.currentTimeMillis());
        timerLabel.setText("Time: " + (remaining / 1000.0) + "s");
        if (remaining == 0) {
            submitButton.setEnabled(false);
        }
    }

    private void startGame() {
        try {
            tcpClient.startGame(roomId);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Start failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void submitAnswer() {
        var selected = optionGroup.getSelection();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select an option first");
            return;
        }
        try {
            int optionIndex = Integer.parseInt(selected.getActionCommand());
            tcpClient.answer(roomId, currentQIndex, optionIndex);
            submitButton.setEnabled(false);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Answer failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sendChat() {
        String text = chatField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            tcpClient.chat(text);
            chatField.setText("");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Chat failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void leaveRoom() {
        try {
            tcpClient.leaveRoom();
        } catch (Exception ignored) {
            // ignore
        }
        countdownTimer.stop();
        lobbyFrame.onLeftRoom();
        dispose();
    }
}
