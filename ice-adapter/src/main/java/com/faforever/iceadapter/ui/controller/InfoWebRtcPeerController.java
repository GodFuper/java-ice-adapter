package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.WebRtcPeerView;
import com.faforever.iceadapter.services.UIAdapter;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@NoArgsConstructor
public class InfoWebRtcPeerController {

    @FXML
    private VBox root;

    @FXML
    private TabPane tabPane;

    // Connection Tab
    @FXML
    private TableView<WebRtcPeerView> connectionTable;

    @FXML
    private TableColumn<WebRtcPeerView, Integer> peerIdColumn1;

    @FXML
    private TableColumn<WebRtcPeerView, String> loginColumn1;

    @FXML
    private TableColumn<WebRtcPeerView, String> peerConnColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> iceConnColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> dtlsStateColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> rttMsColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> echoRttMsColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> pairStateColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> nominatedColumn;

    // Candidates Tab
    @FXML
    private TableView<WebRtcPeerView> candidatesTable;

    @FXML
    private TableColumn<WebRtcPeerView, Integer> peerIdColumn2;

    @FXML
    private TableColumn<WebRtcPeerView, String> loginColumn2;

    @FXML
    private TableColumn<WebRtcPeerView, String> localTypeColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> localAddressColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> remoteTypeColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> remoteAddressColumn;

    // DataChannel Tab
    @FXML
    private TableView<WebRtcPeerView> dataChannelTable;

    @FXML
    private TableColumn<WebRtcPeerView, Integer> peerIdColumn3;

    @FXML
    private TableColumn<WebRtcPeerView, String> loginColumn3;

    @FXML
    private TableColumn<WebRtcPeerView, String> channelLabelColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> channelStateColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> messagesSentColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> messagesRecvColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> bytesSentColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> bytesRecvColumn;

    // Transport Tab
    @FXML
    private TableView<WebRtcPeerView> transportTable;

    @FXML
    private TableColumn<WebRtcPeerView, Integer> peerIdColumn4;

    @FXML
    private TableColumn<WebRtcPeerView, String> loginColumn4;

    @FXML
    private TableColumn<WebRtcPeerView, String> outBitrateColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> inBitrateColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> packetsSentColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> packetsRecvColumn;

    @FXML
    private TableColumn<WebRtcPeerView, String> packetsDiscardedColumn;

    private UIAdapter adapter;
    private ScheduledExecutorService updateScheduler;

    public void setAdapter(UIAdapter adapter) {
        this.adapter = adapter;
        updateAllInfo();
    }

    public void initialize() {
        initColumns();
        startPeriodicUpdates();
    }

    private void initColumns() {
        // Connection Tab
        peerIdColumn1.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn1.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        peerConnColumn.setCellValueFactory(cellData -> cellData.getValue().getPeerConnectionState());
        iceConnColumn.setCellValueFactory(cellData -> cellData.getValue().getIceConnectionState());
        dtlsStateColumn.setCellValueFactory(cellData -> cellData.getValue().getDtlsState());
        rttMsColumn.setCellValueFactory(cellData -> cellData.getValue().getRttMs());
        echoRttMsColumn.setCellValueFactory(cellData -> cellData.getValue().getEchoRttMs());
        pairStateColumn.setCellValueFactory(cellData -> cellData.getValue().getSelectedPairState());
        nominatedColumn.setCellValueFactory(cellData -> cellData.getValue().getNominated());

        // Candidates Tab
        peerIdColumn2.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn2.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        localTypeColumn.setCellValueFactory(cellData -> cellData.getValue().getLocalCandidateType());
        localAddressColumn.setCellValueFactory(cellData -> cellData.getValue().getLocalAddress());
        remoteTypeColumn.setCellValueFactory(cellData -> cellData.getValue().getRemoteCandidateType());
        remoteAddressColumn.setCellValueFactory(cellData -> cellData.getValue().getRemoteAddress());

        // DataChannel Tab
        peerIdColumn3.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn3.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        channelLabelColumn.setCellValueFactory(cellData -> cellData.getValue().getDataChannelLabel());
        channelStateColumn.setCellValueFactory(cellData -> cellData.getValue().getDataChannelState());
        messagesSentColumn.setCellValueFactory(cellData -> cellData.getValue().getMessagesSent());
        messagesRecvColumn.setCellValueFactory(cellData -> cellData.getValue().getMessagesReceived());
        bytesSentColumn.setCellValueFactory(cellData -> cellData.getValue().getBytesSent());
        bytesRecvColumn.setCellValueFactory(cellData -> cellData.getValue().getBytesReceived());

        // Transport Tab
        peerIdColumn4.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn4.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        outBitrateColumn.setCellValueFactory(cellData -> cellData.getValue().getAvailableOutgoingBitrate());
        inBitrateColumn.setCellValueFactory(cellData -> cellData.getValue().getAvailableIncomingBitrate());
        packetsSentColumn.setCellValueFactory(cellData -> cellData.getValue().getPacketsSent());
        packetsRecvColumn.setCellValueFactory(cellData -> cellData.getValue().getPacketsReceived());
        packetsDiscardedColumn.setCellValueFactory(cellData -> cellData.getValue().getPacketsDiscarded());
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        Platform.runLater(() -> {
            var webRtcPeerList = adapter.getWebRtcPeerInfoList();
            if (!Objects.equals(webRtcPeerList, connectionTable.getItems())) {
                connectionTable.setItems(webRtcPeerList);
                candidatesTable.setItems(webRtcPeerList);
                dataChannelTable.setItems(webRtcPeerList);
                transportTable.setItems(webRtcPeerList);
            }

            connectionTable.refresh();
            candidatesTable.refresh();
            dataChannelTable.refresh();
            transportTable.refresh();
        });
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(
                () -> Platform.runLater(this::updateAllInfo),
                0,
                500,
                TimeUnit.MILLISECONDS);
    }
}
