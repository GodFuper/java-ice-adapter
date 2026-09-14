package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.ServerPeerView;
import com.faforever.iceadapter.services.UIAdapter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
        offerColumn.setCellValueFactory(cellData -> cellData.getValue().getOffer());

        hostColumn.setCellValueFactory(param -> param.getValue().getAllowHost());
        hostColumn.setCellFactory(createCheckBoxCellFactory());
        reflexiveColumn.setCellValueFactory(peer -> peer.getValue().getAllowReflexive());
        reflexiveColumn.setCellFactory(createCheckBoxCellFactory());
        relayColumn.setCellValueFactory(peer -> peer.getValue().getAllowRelay());
        relayColumn.setCellFactory(createCheckBoxCellFactory());
    }

    private static Callback<TableColumn<ServerPeerView, Boolean>, TableCell<ServerPeerView, Boolean>>
            createCheckBoxCellFactory() {
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

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        var serverPeerList = adapter.getServerPeerInfoList();
        if (tableView.getItems() != serverPeerList) {
            tableView.setItems(serverPeerList);
        }
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(
                () -> {
                    if (root.getScene() != null
                            && root.getScene().getWindow() != null
                            && root.getScene().getWindow().isShowing()) {
                        Platform.runLater(this::updateAllInfo);
                    }
                },
                0,
                500,
                TimeUnit.MILLISECONDS);
    }

    public void dispose() {
        if (updateScheduler != null && !updateScheduler.isShutdown()) {
            updateScheduler.shutdownNow();
        }
    }
}
