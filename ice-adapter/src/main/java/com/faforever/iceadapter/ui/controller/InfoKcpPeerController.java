package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.KcpPeerView;
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
public class InfoKcpPeerController {

    @FXML
    private VBox root;

    @FXML
    private TabPane tabPane;

    // Connection Tab
    @FXML
    private TableView<KcpPeerView> connectionTable;

    @FXML
    private TableColumn<KcpPeerView, Integer> peerIdColumn1;

    @FXML
    private TableColumn<KcpPeerView, String> loginColumn1;

    @FXML
    private TableColumn<KcpPeerView, String> srttMsColumn;

    @FXML
    private TableColumn<KcpPeerView, String> rttvarMsColumn;

    @FXML
    private TableColumn<KcpPeerView, String> rtoMsColumn;

    @FXML
    private TableColumn<KcpPeerView, String> cwndColumn;

    @FXML
    private TableColumn<KcpPeerView, String> ssthreshColumn;

    @FXML
    private TableColumn<KcpPeerView, String> unackedPacketsColumn;

    // Windows Tab
    @FXML
    private TableView<KcpPeerView> windowsTable;

    @FXML
    private TableColumn<KcpPeerView, Integer> peerIdColumn2;

    @FXML
    private TableColumn<KcpPeerView, String> loginColumn2;

    @FXML
    private TableColumn<KcpPeerView, String> sndWndColumn;

    @FXML
    private TableColumn<KcpPeerView, String> rcvWndColumn;

    @FXML
    private TableColumn<KcpPeerView, String> waitSndColumn;

    // Sequence Tab
    @FXML
    private TableView<KcpPeerView> sequenceTable;

    @FXML
    private TableColumn<KcpPeerView, Integer> peerIdColumn3;

    @FXML
    private TableColumn<KcpPeerView, String> loginColumn3;

    @FXML
    private TableColumn<KcpPeerView, String> sndNxtColumn;

    @FXML
    private TableColumn<KcpPeerView, String> sndUnaColumn;

    @FXML
    private TableColumn<KcpPeerView, String> rcvNxtColumn;

    // Queues Tab
    @FXML
    private TableView<KcpPeerView> queuesTable;

    @FXML
    private TableColumn<KcpPeerView, Integer> peerIdColumn4;

    @FXML
    private TableColumn<KcpPeerView, String> loginColumn4;

    @FXML
    private TableColumn<KcpPeerView, String> sndQueueSizeColumn;

    @FXML
    private TableColumn<KcpPeerView, String> rcvQueueSizeColumn;

    @FXML
    private TableColumn<KcpPeerView, String> nextUpdateMsColumn;

    // Statistics Tab
    @FXML
    private TableView<KcpPeerView> statisticsTable;

    @FXML
    private TableColumn<KcpPeerView, Integer> peerIdColumn5;

    @FXML
    private TableColumn<KcpPeerView, String> loginColumn5;

    @FXML
    private TableColumn<KcpPeerView, String> xmitColumn;

    @FXML
    private TableColumn<KcpPeerView, String> maxSegXmitColumn;

    @FXML
    private TableColumn<KcpPeerView, String> resendCountColumn;

    @FXML
    private TableColumn<KcpPeerView, String> fastResendCountColumn;

    @FXML
    private TableColumn<KcpPeerView, String> fastackCountColumn;

    @FXML
    private TableColumn<KcpPeerView, String> bytesSentColumn;

    @FXML
    private TableColumn<KcpPeerView, String> bytesReceivedColumn;

    // Resync Tab
    @FXML
    private TableView<KcpPeerView> resyncTable;

    @FXML
    private TableColumn<KcpPeerView, Integer> peerIdColumn6;

    @FXML
    private TableColumn<KcpPeerView, String> loginColumn6;

    @FXML
    private TableColumn<KcpPeerView, String> deadLinkDetectedColumn;

    @FXML
    private TableColumn<KcpPeerView, String> consecutiveSoftResyncColumn;

    @FXML
    private TableColumn<KcpPeerView, String> receiveGapSinceColumn;

    @FXML
    private TableColumn<KcpPeerView, String> softDroppedSegmentsColumn;

    @FXML
    private TableColumn<KcpPeerView, String> softResyncCountColumn;

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
        srttMsColumn.setCellValueFactory(cellData -> cellData.getValue().getSrttMs());
        rttvarMsColumn.setCellValueFactory(cellData -> cellData.getValue().getRttvarMs());
        rtoMsColumn.setCellValueFactory(cellData -> cellData.getValue().getRtoMs());
        cwndColumn.setCellValueFactory(cellData -> cellData.getValue().getCwnd());
        ssthreshColumn.setCellValueFactory(cellData -> cellData.getValue().getSsthresh());
        unackedPacketsColumn.setCellValueFactory(cellData -> cellData.getValue().getUnackedPackets());

        // Windows Tab
        peerIdColumn2.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn2.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        sndWndColumn.setCellValueFactory(cellData -> cellData.getValue().getSndWnd());
        rcvWndColumn.setCellValueFactory(cellData -> cellData.getValue().getRcvWnd());
        waitSndColumn.setCellValueFactory(cellData -> cellData.getValue().getWaitSnd());

        // Sequence Tab
        peerIdColumn3.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn3.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        sndNxtColumn.setCellValueFactory(cellData -> cellData.getValue().getSndNxt());
        sndUnaColumn.setCellValueFactory(cellData -> cellData.getValue().getSndUna());
        rcvNxtColumn.setCellValueFactory(cellData -> cellData.getValue().getRcvNxt());

        // Queues Tab
        peerIdColumn4.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn4.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        sndQueueSizeColumn.setCellValueFactory(cellData -> cellData.getValue().getSndQueueSize());
        rcvQueueSizeColumn.setCellValueFactory(cellData -> cellData.getValue().getRcvQueueSize());
        nextUpdateMsColumn.setCellValueFactory(cellData -> cellData.getValue().getNextUpdateMs());

        // Statistics Tab
        peerIdColumn5.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn5.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        xmitColumn.setCellValueFactory(cellData -> cellData.getValue().getXmit());
        maxSegXmitColumn.setCellValueFactory(cellData -> cellData.getValue().getMaxSegXmit());
        resendCountColumn.setCellValueFactory(cellData -> cellData.getValue().getResendCount());
        fastResendCountColumn.setCellValueFactory(cellData -> cellData.getValue().getFastResendCount());
        fastackCountColumn.setCellValueFactory(cellData -> cellData.getValue().getFastackCount());
        bytesSentColumn.setCellValueFactory(cellData -> cellData.getValue().getBytesSentBytes());
        bytesReceivedColumn.setCellValueFactory(cellData -> cellData.getValue().getBytesReceivedBytes());

        // Resync Tab
        peerIdColumn6.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn6.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        deadLinkDetectedColumn.setCellValueFactory(cellData -> cellData.getValue().getDeadLinkDetected());
        consecutiveSoftResyncColumn.setCellValueFactory(cellData -> cellData.getValue().getConsecutiveSoftResync());
        receiveGapSinceColumn.setCellValueFactory(cellData -> cellData.getValue().getReceiveGapSince());
        softDroppedSegmentsColumn.setCellValueFactory(cellData -> cellData.getValue().getSoftDroppedSegments());
        softResyncCountColumn.setCellValueFactory(cellData -> cellData.getValue().getSoftResyncCount());
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        Platform.runLater(() -> {
            var kcpPeerList = adapter.getKcpPeerInfoList();
            if (!Objects.equals(kcpPeerList, connectionTable.getItems())) {
                connectionTable.setItems(kcpPeerList);
                windowsTable.setItems(kcpPeerList);
                sequenceTable.setItems(kcpPeerList);
                queuesTable.setItems(kcpPeerList);
                statisticsTable.setItems(kcpPeerList);
                resyncTable.setItems(kcpPeerList);
            }

            connectionTable.refresh();
            windowsTable.refresh();
            sequenceTable.refresh();
            queuesTable.refresh();
            statisticsTable.refresh();
            resyncTable.refresh();
        });
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(
                () -> {
                    Platform.runLater(this::updateAllInfo);
                },
                0,
                500,
                TimeUnit.MILLISECONDS);
    }
}
