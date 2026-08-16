package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.ServerPeerView;
import com.faforever.iceadapter.services.UIAdapter;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.VBox;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@NoArgsConstructor
public class InfoServerPeerController {

    @FXML
    private VBox root;

    @FXML
    private TableView<ServerPeerView> tableView;

    @FXML
    private TableColumn<ServerPeerView, String> main;

    @FXML
    private TableColumn<ServerPeerView, String> remote;

    @FXML
    private TableColumn<ServerPeerView, String> pairConColumn;

    @FXML
    private TableColumn<ServerPeerView, String> stateColumn;

    @FXML
    private TableColumn<ServerPeerView, String> agentStateColumn;

    @FXML
    private TableColumn<ServerPeerView, String> offerColumn;

    @FXML
    private TableColumn<ServerPeerView, Boolean> hostColumn;

    @FXML
    private TableColumn<ServerPeerView, Boolean> reflexiveColumn;

    @FXML
    private TableColumn<ServerPeerView, Boolean> relayColumn;

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
        main.setCellValueFactory(cellData -> cellData.getValue().getMain());
        remote.setCellValueFactory(cellData -> cellData.getValue().getRemote());

        pairConColumn.setCellValueFactory(cellData -> cellData.getValue().getPairConnection());
        stateColumn.setCellValueFactory(cellData -> cellData.getValue().getState());
        agentStateColumn.setCellValueFactory(cellData -> cellData.getValue().getAgent());
        offerColumn.setCellValueFactory(cellData -> cellData.getValue().getOffer());

        hostColumn.setCellValueFactory(param -> param.getValue().getAllowHost());
        hostColumn.setCellFactory(CheckBoxTableCell.forTableColumn(hostColumn));
        reflexiveColumn.setCellValueFactory(peer -> peer.getValue().getAllowReflexive());
        reflexiveColumn.setCellFactory(CheckBoxTableCell.forTableColumn(reflexiveColumn));
        relayColumn.setCellValueFactory(peer -> peer.getValue().getAllowRelay());
        relayColumn.setCellFactory(CheckBoxTableCell.forTableColumn(relayColumn));
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        Platform.runLater(() -> {
            var serverPeerList = adapter.getServerPeerInfoList();
            if (!Objects.equals(serverPeerList, tableView.getItems())) {
                tableView.setItems(serverPeerList);
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
