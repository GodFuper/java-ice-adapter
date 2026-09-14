package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.UIAdapter;
import com.faforever.iceadapter.ui.IceServerWindow;
import com.faforever.iceadapter.ui.InfoServerPeerWindow;
import com.faforever.iceadapter.ui.InfoWebRtcPeerWindow;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Callback;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

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
    private TableColumn<PeerView, String> offerColumn;

    @FXML
    private TableColumn<PeerView, String> rttColumn;

    @FXML
    private TableColumn<PeerView, String> lastColumn;

    @FXML
    private TableColumn<PeerView, String> lastRelayColumn;

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
    private VBox pairCandidateInfoAreaPane;

    @FXML
    private TextArea pairCandidateInfoArea;

    @FXML
    private ComboBox<PeerView> relayPeerComboBox;

    @FXML
    private CheckBox additionalPacketForwardingCheckbox;

    private UIAdapter adapter;
    private ScheduledExecutorService updateScheduler;

    private PeerView selectedPeer;

    private String lastVersion;
    private String lastUser;
    private int lastRpcPort = Integer.MIN_VALUE;
    private int lastGpgnetPort = Integer.MIN_VALUE;
    private int lastLobbyPort = Integer.MIN_VALUE;
    private String lastRpcServerStatus;
    private String lastRpcClientStatus;
    private String lastGpgnetServerStatus;
    private String lastGpgnetClientStatus;
    private String lastGameState;

    public void openSettingsStunAndTurn() {
        CompletableFuture.runAsync(() -> runOnUIThread(IceServerWindow::launch));
    }

    public void openPanelServerPeers() {
        CompletableFuture.runAsync(() -> runOnUIThread(InfoServerPeerWindow::launch));
    }

    public void openPanelWebRtcPeers() {
        CompletableFuture.runAsync(() -> runOnUIThread(InfoWebRtcPeerWindow::launch));
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
        offerColumn.setCellValueFactory(cellData -> cellData.getValue().getOffer());
        rttColumn.setCellValueFactory(cellData -> cellData.getValue().getRtt());
        lastColumn.setCellValueFactory(cellData -> cellData.getValue().getLastRecv());
        lastRelayColumn.setCellValueFactory(cellData -> cellData.getValue().getLastRelayRecv());
        echosRcvColumn.setCellValueFactory(cellData -> cellData.getValue().getEchosReceived());

        hostColumn.setCellValueFactory(
                param -> param.getValue().getAdditionalInfo().getAllowHost());
        hostColumn.setCellFactory(createCheckBoxCellFactory());
        reflexiveColumn.setCellValueFactory(
                peer -> peer.getValue().getAdditionalInfo().getAllowReflexive());
        reflexiveColumn.setCellFactory(createCheckBoxCellFactory());
        relayColumn.setCellValueFactory(
                peer -> peer.getValue().getAdditionalInfo().getAllowRelay());
        relayColumn.setCellFactory(createCheckBoxCellFactory());

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
        relaySupport.setCellFactory(createCheckBoxCellFactory());

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

        additionalPacketForwardingCheckbox.setOnAction(e -> {
            if (selectedPeer != null && adapter != null) {
                adapter.setAdditionalPacketForwarding(selectedPeer, additionalPacketForwardingCheckbox.isSelected());
            }
        });

        allowCombinationComboBox.getItems().setAll(AllowCombination.values());
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

        setVisible(actionsPeerLabel, adapter == null || adapter.isEnabledManualCombinationConnection());
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

        List<PeerView> relayPeers = adapter.getRelayPeersInfoList(peer.getId().get());
        if (relayPeerComboBox.getItems().size() != relayPeers.size() + 1) {
            ObservableList<PeerView> items = FXCollections.observableArrayList();
            items.add(null);
            items.addAll(relayPeers);
            setItems(relayPeerComboBox, items);
        }

        peerActionTitle.setText(peer.getLogin().get());

        int selectedId = peer.getAdditionalInfo().getRelayPeerId().get();
        PeerView peerToSelect = adapter != null ? adapter.getPeerInfo(selectedId) : null;
        selectComboBox(relayPeerComboBox, peerToSelect);

        selectComboBox(allowCombinationComboBox, peer.getAdditionalInfo().getCombination());

        selectCheckBox(
                additionalPacketForwardingCheckbox,
                peer.getAdditionalInfo().getSendDirectAndRelay().get());
        updatePairCandidateInfo(
                peer.getAdditionalInfo().getGetFullCandidateInfo().get());
    }

    private <T> void setItems(ComboBox<T> comboBox, ObservableList<T> items) {
        var oldGetOnAction = comboBox.getOnAction();
        if (!Objects.equals(comboBox.getItems(), items)) {
            comboBox.setOnAction(null);
            comboBox.setItems(items);
            comboBox.setOnAction(oldGetOnAction);
        }
    }

    private <T> void selectCheckBox(CheckBox checkBox, boolean select) {
        var oldGetOnAction = checkBox.getOnAction();
        if (!Objects.equals(checkBox.isSelected(), select)) {
            checkBox.setOnAction(null);
            checkBox.setSelected(select);
            checkBox.setOnAction(oldGetOnAction);
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
        }
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        String version = adapter.getVersion();
        if (!Objects.equals(version, lastVersion)) {
            lastVersion = version;
            versionLabel.setText("Version: " + version);
        }

        String user = adapter.getUsername();
        int userId = adapter.getUserId();
        String userKey = user + ":" + userId;
        if (!Objects.equals(userKey, lastUser)) {
            lastUser = userKey;
            userLabel.setText("User: %s(%s)".formatted(user, userId));
        }

        int rpcPort = adapter.getRpcPort();
        if (rpcPort != lastRpcPort) {
            lastRpcPort = rpcPort;
            rpcPortLabel.setText("RPC_PORT: " + rpcPort);
        }

        int gpgnetPort = adapter.getGpgNetPort();
        if (gpgnetPort != lastGpgnetPort) {
            lastGpgnetPort = gpgnetPort;
            gpgnetPortLabel.setText("GPGNET_PORT: " + gpgnetPort);
        }

        int lobbyPort = adapter.getLobbyPort();
        if (lobbyPort != lastLobbyPort) {
            lastLobbyPort = lobbyPort;
            lobbyPortLabel.setText("LOBBY_PORT: " + lobbyPort);
        }

        String rpcServer = adapter.getRpcServerStatus();
        if (!Objects.equals(rpcServer, lastRpcServerStatus)) {
            lastRpcServerStatus = rpcServer;
            rpcServerStatus.setText("RPCServer: " + rpcServer);
        }

        String rpcClient = adapter.getRpcClientStatus();
        if (!Objects.equals(rpcClient, lastRpcClientStatus)) {
            lastRpcClientStatus = rpcClient;
            rpcClientStatus.setText("RPCClient: " + rpcClient);
        }

        String gpgServer = adapter.getGpgNetServerStatus();
        if (!Objects.equals(gpgServer, lastGpgnetServerStatus)) {
            lastGpgnetServerStatus = gpgServer;
            gpgnetServerStatus.setText("GPGNetServer: " + gpgServer);
        }

        String gpgClient = adapter.getGpgNetClientStatus();
        if (!Objects.equals(gpgClient, lastGpgnetClientStatus)) {
            lastGpgnetClientStatus = gpgClient;
            gpgnetClientStatus.setText("GPGNetClient: " + gpgClient);
        }

        String gState = adapter.getGameState();
        if (!Objects.equals(gState, lastGameState)) {
            lastGameState = gState;
            gameState.setText("GameState: " + gState);
        }

        var peerList = adapter.getPeerInfoList();
        if (peerTable.getItems() != peerList) {
            peerTable.setItems(peerList);
        }
        PeerView currentlySelected = peerTable.getSelectionModel().getSelectedItem();

        if (currentlySelected != null) {
            boolean found = false;
            for (PeerView p : peerTable.getItems()) {
                if (Objects.equals(p, currentlySelected)) {
                    setSelectedPeer(p);
                    found = true;
                    break;
                }
            }
            if (!found) {
                closePeerManagerPanel();
            }
        }
    }

    private static Callback<TableColumn<PeerView, Boolean>, TableCell<PeerView, Boolean>> createCheckBoxCellFactory() {
        return col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                checkBox.setDisable(true);
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    checkBox.setSelected(item);
                    setGraphic(checkBox);
                }
            }
        };
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(
                () -> Platform.runLater(this::updateAllInfo), 0, 500, TimeUnit.MILLISECONDS);
    }

    public void dispose() {
        if (updateScheduler != null && !updateScheduler.isShutdown()) {
            updateScheduler.shutdownNow();
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
