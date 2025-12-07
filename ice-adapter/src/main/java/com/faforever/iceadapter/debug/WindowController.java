package com.faforever.iceadapter.debug;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@NoArgsConstructor
public class WindowController {

    @FXML
    private AnchorPane root;

    @FXML
    private Button killAdapterButton;

    @FXML
    private Label versionLabel, userLabel, rpcPortLabel, gpgnetPortLabel, lobbyPortLabel;

    @FXML
    private Label rpcServerStatus, rpcClientStatus, gpgnetServerStatus, gpgnetClientStatus, gameState;

    @FXML
    private TableView<PeerInfo> peerTable;
    @FXML
    private TableColumn<PeerInfo, Integer> idColumn;
    @FXML
    private TableColumn<PeerInfo, String> loginColumn;
    @FXML
    private TableColumn<PeerInfo, String> connectedColumn;
    @FXML
    private TableColumn<PeerInfo, String> pairConColumn;
    @FXML
    private TableColumn<PeerInfo, String> stateColumn;
    @FXML
    private TableColumn<PeerInfo, String> agentStateColumn;
    @FXML
    private TableColumn<PeerInfo, String> offerColumn;
    @FXML
    private TableColumn<PeerInfo, String> rttColumn;
    @FXML
    private TableColumn<PeerInfo, String> lastColumn;
    @FXML
    private TableColumn<PeerInfo, String> echosRcvColumn;
    @FXML
    private TableColumn<PeerInfo, Boolean> hostColumn;

    @FXML
    private TableColumn<PeerInfo, Boolean> reflexiveColumn;
    @FXML
    private TableColumn<PeerInfo, Boolean> relayColumn;

    @FXML
    private VBox peerActionPane;
    @FXML
    private Label peerActionTitle;
    @FXML
    private Button reconnectPeerButton;
    @FXML
    private CheckBox hostCheckBox;
    @FXML
    private CheckBox reflexiveCheckBox;
    @FXML
    private CheckBox relayCheckBox;

    private UIAdapter adapter;
    private ScheduledExecutorService updateScheduler;

    private PeerInfo selectedPeer;

    public void initialize() {
        setupButtonActions();
        setupPeerTable();
        startPeriodicUpdates();
        updateAllInfo();
    }

    public void resizeRoot() {
        Stage stage = (Stage) root.getScene().getWindow();
        stage.sizeToScene();
    }

    public void setAdapter(UIAdapter adapter) {
        this.adapter = adapter;
        updateAllInfo();
    }

    private void setupButtonActions() {
        killAdapterButton.setOnAction(event -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Confirm Close");
            alert.setHeaderText("Close ICE Adapter?");
            alert.setContentText("This will disconnect you from the game.");
            alert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK && adapter != null) {
                    adapter.shutdown();
//                    Platform.exit();
                }
            });
        });
    }

    private void setupPeerTable() {
        idColumn.setCellValueFactory(cellData -> cellData.getValue().getId().asObject());
        loginColumn.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        connectedColumn.setCellValueFactory(cellData -> cellData.getValue().getConnected());

        pairConColumn.setCellValueFactory(cellData -> cellData.getValue().getPairConnection());
        stateColumn.setCellValueFactory(cellData -> cellData.getValue().getState());
        agentStateColumn.setCellValueFactory(cellData -> cellData.getValue().getAgent());
        offerColumn.setCellValueFactory(cellData -> cellData.getValue().getOffer());
        rttColumn.setCellValueFactory(cellData -> cellData.getValue().getRtt());
        lastColumn.setCellValueFactory(cellData -> cellData.getValue().getLastRecv());
        echosRcvColumn.setCellValueFactory(cellData -> cellData.getValue().getEchosReceived());

        hostColumn.setCellValueFactory(param -> param.getValue().getAllowHost());
        hostColumn.setCellFactory(CheckBoxTableCell.forTableColumn(hostColumn));
        reflexiveColumn.setCellValueFactory(peer -> peer.getValue().getAllowReflexive());
        reflexiveColumn.setCellFactory(CheckBoxTableCell.forTableColumn(reflexiveColumn));
        relayColumn.setCellValueFactory(peer -> peer.getValue().getAllowRelay());
        relayColumn.setCellFactory(CheckBoxTableCell.forTableColumn(relayColumn));
    }

    @FXML
    private void closePeerManagerPanel() {
        peerActionPane.setVisible(false);
        peerActionPane.setManaged(false);
        selectedPeer = null;
        peerTable.getSelectionModel().clearSelection();
    }

    private void setSelectedPeer(PeerInfo peer) {

        if (Objects.equals(selectedPeer, peer)) {
            return;
        }

        if (peer == null) {
            // спрятать панель
            peerActionPane.setVisible(false);
            peerActionPane.setManaged(false);
            selectedPeer = null;
            return;
        }
        selectedPeer = peer;
        // показать панель
        peerActionPane.setVisible(true);
        peerActionPane.setManaged(true);

        // обновить заголовок или действия
        peerActionTitle.setText(peer.getLogin().get());
        hostCheckBox.setSelected(peer.getAllowHost().get());
        reflexiveCheckBox.setSelected(peer.getAllowReflexive().get());
        relayCheckBox.setSelected(peer.getAllowRelay().get());

        // действия
        reconnectPeerButton.setOnAction(e -> {
            adapter.reconnect(peer, hostCheckBox.isSelected(), reflexiveCheckBox.isSelected(), relayCheckBox.isSelected());
        });
    }

    private void updateAllInfo() {
        if (adapter == null) return;

        // Пример обновления меток
        versionLabel.setText("Version: %s".formatted(adapter.getVersion()));
        userLabel.setText("User: %s(%s)".formatted(adapter.getUsername(), adapter.getUserId()));
        rpcPortLabel.setText("RPC_PORT: %s".formatted(adapter.getRpcPort()));
        gpgnetPortLabel.setText("GPGNET_PORT: %s".formatted(adapter.getGpgNetPort()));
        lobbyPortLabel.setText("LOBBY_PORT: %s".formatted(adapter.getLobbyPort()));

        // Обновление статусов
        rpcServerStatus.setText("RPCServer: %s".formatted(adapter.getRpcServerStatus()));
        rpcClientStatus.setText("RPCClient: %s".formatted(adapter.getRpcClientStatus()));
        gpgnetServerStatus.setText("GPGNetServer: %s".formatted(adapter.getGpgNetServerStatus()));
        gpgnetClientStatus.setText("GPGNetClient: %s".formatted(adapter.getGpgNetClientStatus()));
        gameState.setText("GameState: %s".formatted(adapter.getGameState()));

        // Обновление таблицы пиров
        Platform.runLater(() -> {
            PeerInfo currentlySelected = peerTable.getSelectionModel().getSelectedItem();

            // Обновляем список
            peerTable.getItems().setAll(adapter.getPeerInfoList());

            // Восстанавливаем выбор
            if (currentlySelected != null) {
                boolean found = false;
                for (PeerInfo p : peerTable.getItems()) {
                    if (Objects.equals(p, currentlySelected)) {
                        peerTable.getSelectionModel().select(p);
                        setSelectedPeer(p);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    closePeerManagerPanel();
                }
            }
        });

//        // Логи (пример — последние N строк)
//        Platform.runLater(() -> {
//            logTextArea.setText(adapter.getLogBuffer());
//            logTextArea.setScrollTop(Double.MAX_VALUE); // прокрутка вниз
//        });
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(() -> {
            Platform.runLater(this::updateAllInfo);
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    public void dispose() {
        if (updateScheduler != null && !updateScheduler.isShutdown()) {
            updateScheduler.shutdown();
        }
    }

    // Метод для закрытия окна
    public void close() {
        Stage stage = (Stage) root.getScene().getWindow();
        stage.close();
        dispose();
    }
}
