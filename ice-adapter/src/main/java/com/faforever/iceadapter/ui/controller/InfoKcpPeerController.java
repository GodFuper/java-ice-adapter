package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.KcpPeerView;
import com.faforever.iceadapter.services.UIAdapter;
import javafx.application.Platform;
import javafx.fxml.FXML;
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
    private TableView<KcpPeerView> tableView;

    @FXML
    private TableColumn<KcpPeerView, Integer> peerIdColumn;

    @FXML
    private TableColumn<KcpPeerView, String> loginColumn;

    @FXML
    private TableColumn<KcpPeerView, String> convColumn;

    @FXML
    private TableColumn<KcpPeerView, String> srttMsColumn;

    @FXML
    private TableColumn<KcpPeerView, String> rttvarMsColumn;

    @FXML
    private TableColumn<KcpPeerView, String> rtoMsColumn;

    @FXML
    private TableColumn<KcpPeerView, String> cwndColumn;

    @FXML
    private TableColumn<KcpPeerView, String> sndWndColumn;

    @FXML
    private TableColumn<KcpPeerView, String> rcvWndColumn;

    @FXML
    private TableColumn<KcpPeerView, String> waitSndColumn;

    @FXML
    private TableColumn<KcpPeerView, String> stateColumn;

    @FXML
    private TableColumn<KcpPeerView, String> nextUpdateMsColumn;

    @FXML
    private TableColumn<KcpPeerView, String> bytesSentColumn;

    @FXML
    private TableColumn<KcpPeerView, String> bytesReceivedColumn;

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
        peerIdColumn.setCellValueFactory(cellData -> cellData.getValue().getPeerId().asObject());
        loginColumn.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        convColumn.setCellValueFactory(cellData -> cellData.getValue().getConv());

        srttMsColumn.setCellValueFactory(cellData -> cellData.getValue().getSrttMs());
        rttvarMsColumn.setCellValueFactory(cellData -> cellData.getValue().getRttvarMs());
        rtoMsColumn.setCellValueFactory(cellData -> cellData.getValue().getRtoMs());
        cwndColumn.setCellValueFactory(cellData -> cellData.getValue().getCwnd());
        sndWndColumn.setCellValueFactory(cellData -> cellData.getValue().getSndWnd());
        rcvWndColumn.setCellValueFactory(cellData -> cellData.getValue().getRcvWnd());
        waitSndColumn.setCellValueFactory(cellData -> cellData.getValue().getWaitSnd());
        stateColumn.setCellValueFactory(cellData -> cellData.getValue().getState());
        nextUpdateMsColumn.setCellValueFactory(cellData -> cellData.getValue().getNextUpdateMs());
        bytesSentColumn.setCellValueFactory(cellData -> cellData.getValue().getBytesSentBytes());
        bytesReceivedColumn.setCellValueFactory(cellData -> cellData.getValue().getBytesReceivedBytes());
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        Platform.runLater(() -> {
            var kcpPeerList = adapter.getKcpPeerInfoList();
            if (!Objects.equals(kcpPeerList, tableView.getItems())) {
                tableView.setItems(kcpPeerList);
            }

            tableView.refresh();
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
