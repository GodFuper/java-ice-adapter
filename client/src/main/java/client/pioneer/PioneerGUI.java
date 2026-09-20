package client.pioneer;

import client.forgedalliance.ForgedAlliance;
import data.ForgedAlliancePeer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import logging.Logger;
import lombok.Getter;

/**
 * JavaFX GUI for testing faf-pioneer (analogous to client.GUI).
 */
public class PioneerGUI extends Application {

    @Getter
    private static PioneerGUI instance;

    private PioneerAdapter adapter;
    private ForgedAlliance forgedAlliance;

    private final ObservableList<ForgedAlliancePeer> peers = FXCollections.observableArrayList();
    private final TextArea logArea = new TextArea();
    private Label statusBadge;
    private Label gameStateLabel;

    @Override
    public void start(Stage primaryStage) {
        instance = this;
        primaryStage.setTitle("FAF Pioneer Client - Test Interface");

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-font-family: 'Segoe UI', sans-serif; -fx-background-color: #f4f4f9;");

        // Top info header
        HBox header = createHeader();

        // Control Toolbar
        HBox toolbar = createToolbar(primaryStage);

        // Peer Table
        TableView<ForgedAlliancePeer> table = createPeerTable();

        // Log Console
        VBox logBox = createLogConsole();

        root.getChildren().addAll(header, toolbar, new Label("Connected Peers:"), table, logBox);
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(logBox, Priority.SOMETIMES);

        Scene scene = new Scene(root, 900, 650);
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> {
            if (adapter != null) {
                adapter.stop();
            }
            if (forgedAlliance != null) {
                forgedAlliance.stop();
            }
            Platform.exit();
            System.exit(0);
        });

        // If PioneerTestClient parameters are configured, auto-start
        if (PioneerTestClient.autoStart) {
            PioneerTestServerAccessor.init();
        }

        // Periodically update peer list in table from either local or accessor's forgedAlliance
        Thread peerUpdater = new Thread(() -> {
            while (true) {
                ForgedAlliance activeFa = forgedAlliance != null ? forgedAlliance : PioneerTestServerAccessor.getForgedAlliance();
                if (activeFa != null && activeFa.isRunning()) {
                    Platform.runLater(() -> peers.setAll(activeFa.getPeers()));
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "Pioneer-GUI-Peer-Updater");
        peerUpdater.setDaemon(true);
        peerUpdater.start();
    }

    private HBox createHeader() {
        HBox header = new HBox(15);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10));
        header.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e0e0e0; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label userLabel = new Label("User: " + PioneerTestClient.userName + " (" + PioneerTestClient.userId + ")");
        userLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label gameLabel = new Label("Game ID: " + PioneerTestClient.gameId);
        gameLabel.setStyle("-fx-font-size: 13px;");

        gameStateLabel = new Label("Game State: Unknown");
        gameStateLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #555555;");

        statusBadge = new Label("Adapter: Stopped");
        statusBadge.setPadding(new Insets(4, 8, 4, 8));
        statusBadge.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333333; -fx-font-weight: bold; -fx-background-radius: 4;");

        header.getChildren().addAll(userLabel, new Separator(), gameLabel, new Separator(), gameStateLabel, new Separator(), statusBadge);
        return header;
    }

    private HBox createToolbar(Stage stage) {
        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button startAdapterBtn = new Button("Start Adapter");
        startAdapterBtn.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white; -fx-font-weight: bold;");
        startAdapterBtn.setOnAction(e -> startAdapter());

        Button stopAdapterBtn = new Button("Stop Adapter");
        stopAdapterBtn.setStyle("-fx-background-color: #c62828; -fx-text-fill: white;");
        stopAdapterBtn.setOnAction(e -> stopAdapter());

        Button hostBtn = new Button("Host Game");
        hostBtn.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog("SCMP_001");
            dialog.setTitle("Host Game");
            dialog.setHeaderText("Host game with map name:");
            dialog.setContentText("Map:");
            dialog.showAndWait().ifPresent(map -> {
                if (adapter != null) {
                    adapter.hostGame(map);
                    appendLog("HostGame requested with map: " + map);
                }
            });
        });

        Button joinBtn = new Button("Join Game");
        joinBtn.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog("127.0.0.1:18000,HostPlayer,1");
            dialog.setTitle("Join Game");
            dialog.setHeaderText("Join host (Address,PlayerName,PlayerID):");
            dialog.showAndWait().ifPresent(input -> {
                String[] parts = input.split(",");
                if (parts.length == 3 && adapter != null) {
                    adapter.joinGame(parts[0].trim(), parts[1].trim(), Integer.parseInt(parts[2].trim()));
                    appendLog("JoinGame sent to " + parts[0]);
                }
            });
        });

        Button connectPeerBtn = new Button("Connect Peer");
        connectPeerBtn.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog("127.0.0.1:18000,PeerPlayer,2");
            dialog.setTitle("Connect to Peer");
            dialog.setHeaderText("Connect to peer (Address,PlayerName,PlayerID):");
            dialog.showAndWait().ifPresent(input -> {
                String[] parts = input.split(",");
                if (parts.length == 3 && adapter != null) {
                    adapter.connectToPeer(parts[0].trim(), parts[1].trim(), Integer.parseInt(parts[2].trim()));
                    appendLog("ConnectToPeer sent to " + parts[0]);
                }
            });
        });

        Button simGameBtn = new Button("Start Sim Game");
        simGameBtn.setOnAction(e -> startSimulatedGame());

        toolbar.getChildren().addAll(startAdapterBtn, stopAdapterBtn, new Separator(), hostBtn, joinBtn, connectPeerBtn, new Separator(), simGameBtn);
        return toolbar;
    }

    private TableView<ForgedAlliancePeer> createPeerTable() {
        TableView<ForgedAlliancePeer> table = new TableView<>();
        table.setItems(peers);

        TableColumn<ForgedAlliancePeer, Integer> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("remoteId"));
        idCol.setPrefWidth(60);

        TableColumn<ForgedAlliancePeer, String> nameCol = new TableColumn<>("Username");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("remoteUsername"));
        nameCol.setPrefWidth(140);

        TableColumn<ForgedAlliancePeer, String> latencyCol = new TableColumn<>("Latency (Avg)");
        latencyCol.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().getAverageLatency() == 0
                        ? "-"
                        : cell.getValue().getAverageLatency() + " ms"));
        latencyCol.setPrefWidth(120);

        TableColumn<ForgedAlliancePeer, String> jitterCol = new TableColumn<>("Jitter");
        jitterCol.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().getJitter() == 0
                        ? "-"
                        : cell.getValue().getJitter() + " ms"));
        jitterCol.setPrefWidth(100);

        TableColumn<ForgedAlliancePeer, String> connCol = new TableColumn<>("Connected");
        connCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().isConnected() ? "YES" : "NO"));
        connCol.setPrefWidth(100);

        table.getColumns().addAll(idCol, nameCol, latencyCol, jitterCol, connCol);
        return table;
    }

    private VBox createLogConsole() {
        VBox box = new VBox(5);
        Label label = new Label("Log Console:");
        logArea.setEditable(false);
        logArea.setPrefHeight(160);
        logArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        box.getChildren().addAll(label, logArea);
        return box;
    }

    public void bindAdapter(PioneerAdapter newAdapter) {
        this.adapter = newAdapter;
        if (adapter != null) {
            adapter.getStatusText().addListener((obs, oldV, newV) -> Platform.runLater(() -> {
                statusBadge.setText("Adapter: " + newV);
                if (adapter.getConnected().get()) {
                    statusBadge.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
                } else {
                    statusBadge.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333333; -fx-font-weight: bold; -fx-background-radius: 4;");
                }
            }));

            adapter.getGameState().addListener((obs, oldV, newV) -> Platform.runLater(() -> gameStateLabel.setText("Game State: " + newV)));
            appendLog("Pioneer adapter bound (User ID: " + adapter.getUserId() + ", GPGNet port: " + adapter.getGpgNetPort() + ")");
        }
    }

    private void initAdapterFromContext() {
        PioneerAdapter newAdapter = new PioneerAdapter(
                PioneerTestClient.userId,
                PioneerTestClient.userName,
                PioneerTestClient.gameId,
                PioneerTestClient.accessToken,
                PioneerTestClient.apiRoot);
        bindAdapter(newAdapter);
    }

    public void startAdapter() {
        if (adapter == null) {
            initAdapterFromContext();
        }
        new Thread(() -> {
            try {
                appendLog("Starting faf-pioneer adapter...");
                adapter.start();
                appendLog("Pioneer adapter process started (GPGNet port: " + adapter.getGpgNetPort() + ")");
            } catch (Exception e) {
                appendLog("ERROR starting Pioneer adapter: " + e.getMessage());
                Logger.error("Failed to start Pioneer adapter", e);
            }
        }, "PioneerAdapterStarter").start();
    }

    public void stopAdapter() {
        if (adapter != null) {
            adapter.stop();
            appendLog("Pioneer adapter stopped");
        }
        if (forgedAlliance != null) {
            forgedAlliance.stop();
            forgedAlliance = null;
            appendLog("Simulated game stopped");
        }
    }

    public void startSimulatedGame() {
        if (adapter == null || !adapter.getConnected().get()) {
            appendLog("WARN: Cannot start simulated game, adapter is not connected yet");
            return;
        }

        new Thread(() -> {
            try {
                appendLog("Starting simulated ForgedAlliance game on GPGNet port " + adapter.getGpgNetPort() + " and lobby port " + adapter.getLobbyPort());
                forgedAlliance = new ForgedAlliance(adapter.getGpgNetPort(), adapter.getLobbyPort());
                appendLog("Simulated ForgedAlliance connected to Pioneer GPGNet");

                // Periodically update peer list in table
                new Thread(() -> {
                    while (forgedAlliance != null && forgedAlliance.isRunning()) {
                        Platform.runLater(() -> {
                            peers.setAll(forgedAlliance.getPeers());
                        });
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }, "Pioneer-Peer-Updater").start();

            } catch (Exception e) {
                appendLog("ERROR starting simulated game: " + e.getMessage());
            }
        }, "PioneerGameStarter").start();
    }

    public void appendLog(String text) {
        Platform.runLater(() -> {
            logArea.appendText(text + "\n");
        });
    }
}
