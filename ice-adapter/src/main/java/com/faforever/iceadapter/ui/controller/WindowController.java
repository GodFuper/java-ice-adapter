package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.ice.peer.IceAgentStrategy;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.UIAdapter;
import com.faforever.iceadapter.ui.IceServerWindow;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
    private TableView<PeerView> peerTable;
    @FXML
    private TableColumn<PeerView, Integer> idColumn;
    @FXML
    private TableColumn<PeerView, String> loginColumn;
    @FXML
    private TableColumn<PeerView, String> pairConColumn;
    @FXML
    private TableColumn<PeerView, String> reconnectColumn;
    @FXML
    private TableColumn<PeerView, String> stateColumn;
    @FXML
    private TableColumn<PeerView, String> agentStateColumn;
    @FXML
    private TableColumn<PeerView, String> offerColumn;
    @FXML
    private TableColumn<PeerView, String> rttColumn;
    @FXML
    private TableColumn<PeerView, String> lastColumn;
    @FXML
    private TableColumn<PeerView, String> echosRcvColumn;
    @FXML
    private TableColumn<PeerView, Boolean> hostColumn;

    @FXML
    private TableColumn<PeerView, Boolean> reflexiveColumn;
    @FXML
    private TableColumn<PeerView, Boolean> relayColumn;

    @FXML
    private VBox peerActionPane;
    @FXML
    private Label peerActionTitle;
    @FXML
    private Button reconnectPeerButton;
    @FXML
    private ComboBox<AllowCombination> allowCombinationComboBox;
    @FXML
    private ComboBox<IceAgentStrategy> connectionStrategyComboBox;

    @FXML
    private TextArea pairCandidateInfoArea;

    private UIAdapter adapter;
    private ScheduledExecutorService updateScheduler;

    private PeerView selectedPeer;

    public void openSettingsStunAndTurn() {
        CompletableFuture.runAsync(
                () -> runOnUIThread(IceServerWindow::launch));
    }

    public void initialize() {
        setupButtonActions();
        setupPeerTable();
        startPeriodicUpdates();
        updateAllInfo();
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
                }
            });
        });
    }

    private void setupPeerTable() {
        idColumn.setCellValueFactory(cellData -> cellData.getValue().getId().asObject());
        loginColumn.setCellValueFactory(cellData -> cellData.getValue().getLogin());

        pairConColumn.setCellValueFactory(cellData -> cellData.getValue().getPairConnection());
        stateColumn.setCellValueFactory(cellData -> cellData.getValue().getState());
        agentStateColumn.setCellValueFactory(cellData -> cellData.getValue().getAgent());
        offerColumn.setCellValueFactory(cellData -> cellData.getValue().getOffer());
        rttColumn.setCellValueFactory(cellData -> cellData.getValue().getRtt());
        lastColumn.setCellValueFactory(cellData -> cellData.getValue().getLastRecv());
        echosRcvColumn.setCellValueFactory(cellData -> cellData.getValue().getEchosReceived());

        hostColumn.setCellValueFactory(param -> param.getValue().getAdditionalInfo().getAllowHost());
        hostColumn.setCellFactory(CheckBoxTableCell.forTableColumn(hostColumn));
        reflexiveColumn.setCellValueFactory(peer -> peer.getValue().getAdditionalInfo().getAllowReflexive());
        reflexiveColumn.setCellFactory(CheckBoxTableCell.forTableColumn(reflexiveColumn));
        relayColumn.setCellValueFactory(peer -> peer.getValue().getAdditionalInfo().getAllowRelay());
        relayColumn.setCellFactory(CheckBoxTableCell.forTableColumn(relayColumn));

        reconnectColumn.setCellFactory(param -> new TableCell<>() {
            private final Button button = new Button("Reconnect");

            {
                button.setOnAction(event -> {
                    PeerView peer = getTableView().getItems().get(getIndex());
                    if (peer != null && adapter != null) {
                        adapter.reconnect(peer);
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    PeerView peer = getTableView().getItems().get(getIndex());
                    if (peer != null) {
                        button.setDisable(!peer.getConnected().get());
                        setGraphic(button);
                    } else {
                        setGraphic(null);
                    }
                }
            }
        });
    }

    @FXML
    private void closePeerManagerPanel() {
        peerActionPane.setVisible(false);
        peerActionPane.setManaged(false);
        selectedPeer = null;
        peerTable.getSelectionModel().clearSelection();
    }

    private void setSelectedPeer(PeerView peer) {

        if (!isAdditionalPanelEnabled()) {
            return;
        }

        if (Objects.equals(selectedPeer, peer)) {
            return;
        }

        if (peer == null) {
            // Hide panel
            peerActionPane.setVisible(false);
            peerActionPane.setManaged(false);
            selectedPeer = null;
            return;
        }
        selectedPeer = peer;
        // Show panel
        peerActionPane.setVisible(true);
        peerActionPane.setManaged(true);

        peerActionTitle.setText(peer.getLogin().get());

        allowCombinationComboBox.getItems().setAll(AllowCombination.values());
        allowCombinationComboBox.setValue(peer.getAdditionalInfo().getCombination());
        allowCombinationComboBox.setVisible(adapter.isEnabledManualCombinationConnection());

        connectionStrategyComboBox.getItems().setAll(IceAgentStrategy.values());
        connectionStrategyComboBox.setValue(peer.getAdditionalInfo().getAgentStrategy());
        connectionStrategyComboBox.setVisible(adapter.isEnabledManualStrategyConnection());

        allowCombinationComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!Objects.equals(oldValue, newValue)) {
                adapter.setAllowCombination(peer, newValue);
            }
        });

        connectionStrategyComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!Objects.equals(oldValue, newValue)) {
                adapter.setStrategy(peer, newValue);
            }
        });

        reconnectPeerButton.setOnAction(e -> {
            adapter.reconnect(peer);
        });

        updatePairCandidateInfo(peer.getAdditionalInfo().getGetFullCandidateInfo().get());
    }

    private boolean isAdditionalPanelEnabled() {
        return Optional.ofNullable(adapter)
                .map(adapter -> adapter.isEnabledAdditionalPeerInfo()
                        || adapter.isEnabledManualCombinationConnection()
                        || adapter.isEnabledManualStrategyConnection())
                .orElse(false);
    }

    private void updatePairCandidateInfo(String info) {
        if (StringUtils.isEmpty(info)) {
            pairCandidateInfoArea.setText("No candidate information available.");
        } else {
            pairCandidateInfoArea.setText(info);
        }
        pairCandidateInfoArea.setScrollTop(0);
        pairCandidateInfoArea.setVisible(adapter.isEnabledAdditionalPeerInfo());
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        versionLabel.setText("Version: %s".formatted(adapter.getVersion()));
        userLabel.setText("User: %s(%s)".formatted(adapter.getUsername(), adapter.getUserId()));
        rpcPortLabel.setText("RPC_PORT: %s".formatted(adapter.getRpcPort()));
        gpgnetPortLabel.setText("GPGNET_PORT: %s".formatted(adapter.getGpgNetPort()));
        lobbyPortLabel.setText("LOBBY_PORT: %s".formatted(adapter.getLobbyPort()));

        rpcServerStatus.setText("RPCServer: %s".formatted(adapter.getRpcServerStatus()));
        rpcClientStatus.setText("RPCClient: %s".formatted(adapter.getRpcClientStatus()));
        gpgnetServerStatus.setText("GPGNetServer: %s".formatted(adapter.getGpgNetServerStatus()));
        gpgnetClientStatus.setText("GPGNetClient: %s".formatted(adapter.getGpgNetClientStatus()));
        gameState.setText("GameState: %s".formatted(adapter.getGameState()));

        Platform.runLater(() -> {
            PeerView currentlySelected = peerTable.getSelectionModel().getSelectedItem();

            peerTable.getItems().setAll(adapter.getPeerInfoList());

            if (currentlySelected != null) {
                boolean found = false;
                for (PeerView p : peerTable.getItems()) {
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

    public void close() {
        Stage stage = (Stage) root.getScene().getWindow();
        stage.close();
        dispose();
    }

    private static void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }
}
