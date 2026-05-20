package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.ice.peer.IceAgentStrategy;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.UIAdapter;
import com.faforever.iceadapter.ui.IceServerWindow;
import com.faforever.iceadapter.ui.InfoServerPeerWindow;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;
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
    private TableColumn<PeerView, Integer> selectedRelayPeerColumn;
    @FXML
    private TableColumn<PeerView, Boolean> relaySupport;

    @FXML
    private VBox selectPeerActionPane;
    @FXML
    private VBox peerActionPane;
    @FXML
    private Label peerActionTitle;
    @FXML
    private Button reconnectPeerButton;
    @FXML
    private Label actionsPeerLabel;
    @FXML
    private ComboBox<AllowCombination> allowCombinationComboBox;
    @FXML
    private ComboBox<IceAgentStrategy> connectionStrategyComboBox;

    @FXML
    private VBox pairCandidateInfoAreaPane;

    @FXML
    private TextArea pairCandidateInfoArea;

    @FXML
    private ComboBox<PeerView> relayPeerComboBox;

    private UIAdapter adapter;
    private ScheduledExecutorService updateScheduler;

    private PeerView selectedPeer;

    public void openSettingsStunAndTurn() {
        CompletableFuture.runAsync(
                () -> runOnUIThread(IceServerWindow::launch));
    }

    public void openPanelServerPeers() {
        CompletableFuture.runAsync(
                () -> runOnUIThread(InfoServerPeerWindow::launch));
    }

    public void initialize() {
        setupButtonActions();
        setupPeerTable();
        startPeriodicUpdates();
        initPanes();
    }

    private void initPanes() {

        relayPeerComboBox.setOnAction(event -> {
            PeerView newValue = relayPeerComboBox.getValue();
            if (adapter != null) {
                adapter.setRelayPeer(selectedPeer, newValue);
            }
        });

        allowCombinationComboBox.setOnAction(event -> {
            AllowCombination newValue = allowCombinationComboBox.getValue();
            if (newValue != null && adapter != null) {
                adapter.setAllowCombination(selectedPeer, newValue);
            }
        });
        connectionStrategyComboBox.setOnAction(event -> {
            IceAgentStrategy newValue = connectionStrategyComboBox.getValue();
            if (newValue != null && adapter != null) {
                adapter.setStrategy(selectedPeer, newValue);
            }
        });
    }

    public void setAdapter(UIAdapter adapter) {
        this.adapter = adapter;
        Platform.runLater(this::updateAllInfo);
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

        // Настройка столбца выбранного пира
        selectedRelayPeerColumn.setCellValueFactory(cellData -> {
            PeerView peer = cellData.getValue();
            IntegerProperty selectedRelayPeerId = peer.getAdditionalInfo().getRelayPeerId();
            return selectedRelayPeerId.asObject();
        });
        selectedRelayPeerColumn.setCellFactory(column -> new TableCell<PeerView, Integer>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item == -1) {
                    setText("-");
                } else {
                    setText(String.valueOf(item));
                }
            }
        });
        relaySupport.setCellValueFactory(peer -> peer.getValue().getPeerRelaySupport());
        relaySupport.setCellFactory(CheckBoxTableCell.forTableColumn(relaySupport));

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
        relayPeerComboBox.setCellFactory(comboBox -> new ListCell<PeerView>() {
            @Override
            protected void updateItem(PeerView item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("Disable");
                } else {
                    setText(item.prettyPrint());
                }
            }
        });
        relayPeerComboBox.setButtonCell(new ListCell<PeerView>() {
            @Override
            protected void updateItem(PeerView item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("Disable");
                } else {
                    setText(item.prettyPrint());
                }
            }
        });

        reconnectPeerButton.setOnAction(e -> {
            if (selectedPeer != null) {
                adapter.reconnect(selectedPeer);
            }
        });

        allowCombinationComboBox.getItems().setAll(AllowCombination.values());
        connectionStrategyComboBox.getItems().setAll(IceAgentStrategy.values());
    }

    @FXML
    private void closePeerManagerPanel() {
        peerActionPane.setVisible(false);
        selectPeerActionPane.setVisible(true);
        selectedPeer = null;
        peerTable.getSelectionModel().clearSelection();
    }

    private void setVisible(Region region, boolean visible) {
        region.setVisible(visible);
        region.setManaged(visible);
    }

    private void setSelectedPeer(PeerView peer) {

        setVisible(actionsPeerLabel, adapter == null || adapter.isEnabledManualStrategyConnection() || adapter.isEnabledManualCombinationConnection());
        setVisible(connectionStrategyComboBox, adapter == null || adapter.isEnabledManualStrategyConnection());
        setVisible(allowCombinationComboBox, adapter == null || adapter.isEnabledManualCombinationConnection());
        setVisible(pairCandidateInfoAreaPane, adapter == null || adapter.isEnabledAdditionalPeerInfo());

        peerActionPane.setVisible(peer != null);
        selectPeerActionPane.setVisible(peer == null);
        selectPeerActionPane.setManaged(false);
        if (peer == null) {
            selectedPeer = null;
            return;
        }

        if (!Objects.equals(selectedPeer, peer)) {
            selectedPeer = peer;
        }

        ObservableList<PeerView> items = FXCollections.observableArrayList();
        items.add(null);
        items.addAll(adapter.getRelayPeersInfoList(peer.getId().get()));
        setItems(relayPeerComboBox, items);

        peerActionTitle.setText(peer.getLogin().get());

        int selectedId = peer.getAdditionalInfo().getRelayPeerId().get();
        PeerView peerToSelect = adapter != null ? adapter.getPeerInfo(selectedId) : null;
        selectComboBox(relayPeerComboBox, peerToSelect);

        selectComboBox(allowCombinationComboBox, peer.getAdditionalInfo().getCombination());

        selectComboBox(connectionStrategyComboBox, peer.getAdditionalInfo().getAgentStrategy());

        updatePairCandidateInfo(peer.getAdditionalInfo().getGetFullCandidateInfo().get());
    }

    private <T> void setItems(ComboBox<T> comboBox, ObservableList<T> items) {
        var oldGetOnAction = comboBox.getOnAction();
        if (!Objects.equals(comboBox.getItems(), items)) {
            comboBox.setOnAction(null);
            comboBox.setItems(items);
            comboBox.setOnAction(oldGetOnAction);
        }
    }

    private <T> void selectComboBox(ComboBox<T> comboBox, T select) {
        var oldGetOnAction = comboBox.getOnAction();
        if (!Objects.equals(comboBox.getValue(), select)) {
            comboBox.setOnAction(null);
            if (select == null) {
                comboBox.getSelectionModel().selectFirst();
            } else {
                comboBox.getSelectionModel().select(select);
            }
            comboBox.setOnAction(oldGetOnAction);
        }
    }

    private void updatePairCandidateInfo(String info) {
        if (StringUtils.isEmpty(info)) {
            pairCandidateInfoArea.setText("No candidate information available.");
        } else if (!Objects.equals(info, pairCandidateInfoArea.getText())) {
            pairCandidateInfoArea.setText(info);
            pairCandidateInfoArea.setScrollTop(0);
        }
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
            var peerList = adapter.getPeerInfoList();
            if (!Objects.equals(peerList, peerTable.getItems())) {
                peerTable.setItems(peerList);
            }
            PeerView currentlySelected = peerTable.getSelectionModel().getSelectedItem();

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
            peerTable.refresh();
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
