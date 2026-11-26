package trivia.client.ui;

import trivia.client.http.StatsApiClient;
import trivia.shared.dto.MatchResult;
import trivia.shared.dto.PlayerStats;
import trivia.shared.model.ScoreEntry;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.util.List;

public class StatsFrame extends JFrame {
    private final StatsApiClient statsApiClient;
    private final JTextArea output = new JTextArea(20, 60);

    public StatsFrame(StatsApiClient statsApiClient) {
        super("Trivia Hub — Stats (HTTP REST)");
        this.statsApiClient = statsApiClient;
        output.setEditable(false);
        buildUi();
        setSize(720, 480);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        refreshLeaderboard();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton leaderboardBtn = new JButton("GET /api/leaderboard");
        JButton matchesBtn = new JButton("GET /api/matches");
        leaderboardBtn.addActionListener(e -> refreshLeaderboard());
        matchesBtn.addActionListener(e -> refreshMatches());
        buttons.add(leaderboardBtn);
        buttons.add(matchesBtn);

        root.add(buttons, BorderLayout.NORTH);
        root.add(new JScrollPane(output), BorderLayout.CENTER);
        setContentPane(root);
    }

    private void refreshLeaderboard() {
        output.setText("Loading leaderboard from " + statsApiClient.getBaseUrl() + " ...\n");
        new SwingWorker<List<PlayerStats>, Void>() {
            @Override
            protected List<PlayerStats> doInBackground() throws Exception {
                return statsApiClient.leaderboard();
            }

            @Override
            protected void done() {
                try {
                    List<PlayerStats> stats = get();
                    StringBuilder sb = new StringBuilder("HTTP 200 — Leaderboard\n\n");
                    int rank = 1;
                    for (PlayerStats player : stats) {
                        sb.append(rank++)
                                .append(". ")
                                .append(player.getPlayerName())
                                .append(" | total=")
                                .append(player.getTotalScore())
                                .append(" | matches=")
                                .append(player.getMatchesPlayed())
                                .append(" | wins=")
                                .append(player.getWins())
                                .append('\n');
                    }
                    if (stats.isEmpty()) {
                        sb.append("(no matches recorded yet)\n");
                    }
                    output.setText(sb.toString());
                } catch (Exception ex) {
                    showError(ex);
                }
            }
        }.execute();
    }

    private void refreshMatches() {
        output.setText("Loading matches from " + statsApiClient.getBaseUrl() + " ...\n");
        new SwingWorker<List<MatchResult>, Void>() {
            @Override
            protected List<MatchResult> doInBackground() throws Exception {
                return statsApiClient.matches();
            }

            @Override
            protected void done() {
                try {
                    List<MatchResult> matches = get();
                    StringBuilder sb = new StringBuilder("HTTP 200 — Match History\n\n");
                    for (MatchResult match : matches) {
                        sb.append(match.getMatchId())
                                .append(" | room=")
                                .append(match.getRoomName())
                                .append(" | ended=")
                                .append(Instant.ofEpochMilli(match.getEndedAt()))
                                .append('\n');
                        for (ScoreEntry score : match.getScores()) {
                            sb.append("   - ")
                                    .append(score.getPlayerName())
                                    .append(": ")
                                    .append(score.getScore())
                                    .append('\n');
                        }
                        sb.append('\n');
                    }
                    if (matches.isEmpty()) {
                        sb.append("(no matches recorded yet)\n");
                    }
                    output.setText(sb.toString());
                } catch (Exception ex) {
                    showError(ex);
                }
            }
        }.execute();
    }

    private void showError(Exception ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        output.setText("Request failed: " + cause.getMessage());
        JOptionPane.showMessageDialog(this, cause.getMessage(), "HTTP Error", JOptionPane.ERROR_MESSAGE);
    }
}
