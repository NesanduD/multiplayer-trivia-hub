package trivia.client;

import trivia.client.discovery.ServerDiscoveryListener;
import trivia.client.http.StatsApiClient;
import trivia.client.net.GameTcpClient;
import trivia.client.ui.LobbyFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.logging.Logger;

/**
 * Boots Swing client with TCP, UDP discovery, and HTTP stats integration.
 */
public final class TriviaClientMain {
    private static final Logger LOG = Logger.getLogger(TriviaClientMain.class.getName());

    private TriviaClientMain() {
    }

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        GameTcpClient tcpClient = new GameTcpClient();
        ServerDiscoveryListener discoveryListener = new ServerDiscoveryListener();
        StatsApiClient statsApiClient = new StatsApiClient();

        // Best-effort: UI still opens if multicast discovery is unavailable
        discoveryListener.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            tcpClient.close();
            discoveryListener.close();
        }));

        SwingUtilities.invokeLater(() -> {
            LobbyFrame lobby = new LobbyFrame(tcpClient, discoveryListener, statsApiClient);
            lobby.setVisible(true);
            if (!discoveryListener.isActive()) {
                LOG.warning("UDP discovery inactive — connect with 127.0.0.1:5000");
            }
            LOG.info("Trivia Hub client UI started");
        });
    }
}
