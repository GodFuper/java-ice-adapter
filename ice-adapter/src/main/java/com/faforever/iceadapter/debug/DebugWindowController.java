package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.util.Callback;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

@Slf4j
@NoArgsConstructor
public class DebugWindowController {
    public static DebugWindowController INSTANCE;

    public HBox genericInfo;
    public Label versionLabel;
    public Label userLabel;
    public Label rpcPortLabel;
    public Label gpgnetPortLabel;
    public Label lobbyPortLabel;
    public TextArea logTextArea;
    public HBox rpcGpgInfo;
    public HBox gpgnetInfo;
    public Label rpcServerStatus;
    public Label rpcClientStatus;
    public HBox rpcInfo;
    public Label gpgnetServerStatus;
    public Label gpgnetClientStatus;
    public Label gameState;
    public TableView peerTable;
    public TableColumn idColumn;
    public TableColumn loginColumn;
    public TableColumn offerColumn;
    public TableColumn connectedColumn;
    public TableColumn buttonReconnect;
    public TableColumn stateColumn;
    public TableColumn rttColumn;
    public TableColumn lastColumn;
    public TableColumn echosRcvColumn;
    public TableColumn invalidEchosRcvColumn;
    public TableColumn localCandColumn;
    public TableColumn remoteCandColumn;

    public Button killAdapterButton;

    public void onKillAdapterClicked(ActionEvent actionEvent) {
        IceAdapter.close(337);
    }

    public void reconnectToPeer(DebugWindow.DebugPeer peer) {
        if (peer != null) {
            CompletableFuture.runAsync(
                    () -> IceAdapter.getGameSessionSafe().reconnectToPeer(peer.getId(), null, null, null), IceAdapter.getExecutor());
        }
    }

    @FXML
    private void initialize() {
        if (Debug.ENABLE_DEBUG_WINDOW_LOG_TEXT_AREA) {
            ((TextAreaLogAppender)
                            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                                    .getAppender("TEXTAREA"))
                    .setTextArea(logTextArea);
        }

        logTextArea
                .textProperty()
                .addListener((observableValue, oldVal, newVal) -> logTextArea.setScrollTop(Double.MAX_VALUE));

        idColumn.setCellValueFactory(new PropertyValueFactory<>("id"));
        loginColumn.setCellValueFactory(new PropertyValueFactory<>("login"));
        offerColumn.setCellValueFactory(new PropertyValueFactory<>("localOffer"));
        connectedColumn.setCellValueFactory(new PropertyValueFactory<>("connected"));
        stateColumn.setCellValueFactory(new PropertyValueFactory<>("state"));
        rttColumn.setCellValueFactory(new PropertyValueFactory<>("averageRtt"));
        lastColumn.setCellValueFactory(new PropertyValueFactory<>("lastReceived"));
        echosRcvColumn.setCellValueFactory(new PropertyValueFactory<>("echosReceived"));
        invalidEchosRcvColumn.setCellValueFactory(new PropertyValueFactory<>("invalidEchosReceived"));
        localCandColumn.setCellValueFactory(new PropertyValueFactory<>("localCandidate"));
        remoteCandColumn.setCellValueFactory(new PropertyValueFactory<>("remoteCandidate"));

        buttonReconnect.setCellFactory(new Callback<TableColumn, TableCell>() {
            @Override
            public TableCell<DebugWindow.DebugPeer, DebugWindow.DebugPeer> call(TableColumn param) {
                return new TableCell<>() {
                    final Button btn = new Button("reconnect");

                    {
                        btn.setOnAction(e -> {
                            DebugWindow.DebugPeer peer = getTableRow().getItem();
                            reconnectToPeer(peer);
                        });
                    }

                    @Override
                    protected void updateItem(DebugWindow.DebugPeer item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(null);
                        if (empty) {
                            setGraphic(null);
                            return;
                        }

                        DebugWindow.DebugPeer peer = getTableView().getItems().get(getIndex());
                        if (peer.isLocalOffer()) {
                            setGraphic(btn);
                        } else {
                            setGraphic(null);
                        }
                    }
                };
            }
        });

        killAdapterButton.setOnAction(this::onKillAdapterClicked);
    }
}
