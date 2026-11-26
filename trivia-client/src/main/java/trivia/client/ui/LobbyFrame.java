package trivia.client.ui;

import trivia.client.discovery.ServerDiscoveryListener;
import trivia.client.http.StatsApiClient;
import trivia.client.net.ConnectionState;
import trivia.client.net.GameTcpClient;
import trivia.shared.discovery.DiscoveryMessage;
import trivia.shared.protocol.Message;
import trivia.shared.protocol.MessageType;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LobbyFrame extends JFrame {
    private final GameTcpClient tcpClient;
    private final ServerDiscoveryListener discoveryListener;
    private final StatsApiClient statsApiClient;
    private final DefaultListModel<String> serverListModel = new DefaultListModel<>();
    private final DefaultListModel<String> roomListModel = new DefaultListModel<>();
    private final Map<String, DiscoveryMessage> serverByLabel = new HashMap<>();
    private final Map<String, String> roomIdByLabel = new HashMap<>();

    private final JTextField nameField = new JTextField("Player", 12);
    private final JTextField hostField = new JTextField("127.0.0.1", 12);
    private final JTextField portField = new JTextField("5000", 5);
    private final JTextField roomNameField = new JTextField("Trivia Room", 12);
    private final JLabel statusLabel = new JLabel("Disconnected");
    private final JList<String> serverList = new JList<>(serverListModel);
    private final JList<String> roomList = new JList<>(roomListModel);

    private GameFrame gameFrame;
    private String currentRoomId;

    public LobbyFrame(GameTcpClient tcpClient,
                      ServerDiscoveryListener discoveryListener,
                      StatsApiClient statsApiClient) {
        super("Trivia Hub — Lobby");
        this.tcpClient = tcpClient;
        this.discoveryListener = discoveryListener;
        this.statsApiClient = statsApiClient;
        buildUi();
        wireEvents();
        setSize(780, 520);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel connectPanel = new JPanel(new GridBagLayout());
        connectPanel.setBorder(BorderFactory.createTitledBorder("Connect"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0; connectPanel.add(new JLabel("Name"), c);
        c.gridx = 1; connectPanel.add(nameField, c);
        c.gridx = 2; connectPanel.add(new JLabel("Host"), c);
        c.gridx = 3; connectPanel.add(hostField, c);
        c.gridx = 4; connectPanel.add(new JLabel("Port"), c);
        c.gridx = 5; connectPanel.add(portField, c);

        JButton connectBtn = new JButton("Connect");
        JButton disconnectBtn = new JButton("Disconnect");
        c.gridx = 6; connectPanel.add(connectBtn, c);
        c.gridx = 7; connectPanel.add(disconnectBtn, c);

        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());

        JPanel center = new JPanel(new BorderLayout(8, 8));

        JPanel discoveryPanel = new JPanel(new BorderLayout());
        discoveryPanel.setBorder(BorderFactory.createTitledBorder("Discovered Servers (UDP Multicast)"));
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        discoveryPanel.add(new JScrollPane(serverList), BorderLayout.CENTER);
        JButton useServerBtn = new JButton("Use Selected Server");
        useServerBtn.addActionListener(e -> useSelectedServer());
        discoveryPanel.add(useServerBtn, BorderLayout.SOUTH);
        discoveryPanel.setPreferredSize(new Dimension(280, 200));

        JPanel roomsPanel = new JPanel(new BorderLayout());
        roomsPanel.setBorder(BorderFactory.createTitledBorder("Game Rooms"));
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomsPanel.add(new JScrollPane(roomList), BorderLayout.CENTER);

        JPanel roomActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        roomActions.add(new JLabel("Room name"));
        roomActions.add(roomNameField);
        JButton createBtn = new JButton("Create Room");
        JButton joinBtn = new JButton("Join Room");
        JButton refreshBtn = new JButton("Refresh Rooms");
        JButton statsBtn = new JButton("Stats");
        createBtn.addActionListener(e -> createRoom());
        joinBtn.addActionListener(e -> joinRoom());
        refreshBtn.addActionListener(e -> refreshRooms());
        statsBtn.addActionListener(e -> openStats());
        roomActions.add(createBtn);
        roomActions.add(joinBtn);
        roomActions.add(refreshBtn);
        roomActions.add(statsBtn);
        roomsPanel.add(roomActions, BorderLayout.SOUTH);

        center.add(discoveryPanel, BorderLayout.WEST);
        center.add(roomsPanel, BorderLayout.CENTER);

        root.add(connectPanel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private void wireEvents() {
        tcpClient.addStateListener(state -> SwingUtilities.invokeLater(() ->
                statusLabel.setText("TCP: " + state)));
        tcpClient.addErrorListener(err -> SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, err, "Network Error", JOptionPane.ERROR_MESSAGE)));
        tcpClient.addMessageListener(this::onMessage);

        discoveryListener.addListener(servers -> SwingUtilities.invokeLater(() -> updateServers(servers)));
        Timer refreshTimer = new Timer(2000, e -> updateServers(discoveryListener.currentServers()));
        refreshTimer.start();
    }

    private void onMessage(Message message) {
        SwingUtilities.invokeLater(() -> {
            switch (message.getType()) {
                case WELCOME -> statusLabel.setText("Registered as " + message.getPlayerName()
                        + " (" + message.getPlayerId() + ")");
                case ROOM_LIST -> updateRooms(message);
                case ROOM_CREATED -> {
                    currentRoomId = message.getRoomId();
                    statusLabel.setText("Created room " + message.getRoomName());
                }
                case ROOM_STATE -> openGameIfNeeded(message);
                case QUESTION, ANSWER_RESULT, SCORE_UPDATE, CHAT, GAME_OVER, PLAYER_LEFT -> {
                    if (gameFrame != null) {
                        gameFrame.onMessage(message);
                    }
                }
                case ERROR -> JOptionPane.showMessageDialog(this, message.getError(), "Server",
                        JOptionPane.WARNING_MESSAGE);
                default -> {
                    // ignore
                }
            }
        });
    }

    private void openGameIfNeeded(Message roomState) {
        currentRoomId = roomState.getRoomId();
        if (gameFrame == null || !gameFrame.isDisplayable()) {
            gameFrame = new GameFrame(tcpClient, roomState.getRoomId(), roomState.getRoomName(), this);
            gameFrame.setVisible(true);
        }
        gameFrame.onMessage(roomState);
    }

    private void updateServers(List<DiscoveryMessage> servers) {
        String selected = serverList.getSelectedValue();
        serverListModel.clear();
        serverByLabel.clear();
        for (DiscoveryMessage server : servers) {
            String label = server.getServerName() + " — " + server.getHost() + ":" + server.getTcpPort()
                    + " [" + server.getPlayers() + " players]";
            serverListModel.addElement(label);
            serverByLabel.put(label, server);
        }
        if (selected != null && serverListModel.contains(selected)) {
            serverList.setSelectedValue(selected, true);
        }
    }

    private void updateRooms(Message message) {
        roomListModel.clear();
        roomIdByLabel.clear();
        if (message.getRooms() == null) {
            return;
        }
        for (Map<String, Object> room : message.getRooms()) {
            String id = String.valueOf(room.get("roomId"));
            String name = String.valueOf(room.get("roomName"));
            String status = String.valueOf(room.get("status"));
            String players = String.valueOf(room.get("players"));
            String max = String.valueOf(room.get("maxPlayers"));
            String label = name + " (" + players + "/" + max + ") [" + status + "]";
            roomListModel.addElement(label);
            roomIdByLabel.put(label, id);
        }
    }

    private void useSelectedServer() {
        String selected = serverList.getSelectedValue();
        if (selected == null) {
            return;
        }
        DiscoveryMessage server = serverByLabel.get(selected);
        if (server == null) {
            return;
        }
        hostField.setText(server.getHost());
        portField.setText(String.valueOf(server.getTcpPort()));
        statsApiClient.setBaseUrl("http://" + server.getHost() + ":" + server.getHttpPort());
    }

    private void connect() {
        try {
            if (tcpClient.getState() != ConnectionState.DISCONNECTED) {
                tcpClient.close();
            }
            int port = Integer.parseInt(portField.getText().trim());
            tcpClient.connect(hostField.getText().trim(), port, nameField.getText().trim());
            statsApiClient.setBaseUrl("http://" + hostField.getText().trim() + ":8080");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Connect failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void disconnect() {
        tcpClient.close();
        statusLabel.setText("Disconnected");
    }

    private void createRoom() {
        try {
            tcpClient.createRoom(roomNameField.getText().trim(), 4);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Create failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void joinRoom() {
        String selected = roomList.getSelectedValue();
        if (selected == null) {
            return;
        }
        String roomId = roomIdByLabel.get(selected);
        try {
            tcpClient.joinRoom(roomId);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Join failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshRooms() {
        try {
            tcpClient.listRooms();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Refresh failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openStats() {
        StatsFrame statsFrame = new StatsFrame(statsApiClient);
        statsFrame.setVisible(true);
    }

    public void onLeftRoom() {
        currentRoomId = null;
        refreshRooms();
    }
}
