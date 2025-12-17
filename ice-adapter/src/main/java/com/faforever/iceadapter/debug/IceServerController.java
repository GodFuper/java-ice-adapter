package com.faforever.iceadapter.debug;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.VBox;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@NoArgsConstructor
public class IceServerController {

    @FXML
    private VBox root;

    @FXML
    private TableView<OneIceServerWrapper> tableView;

    @FXML
    private TableColumn<OneIceServerWrapper, String> typeColumn;
    @FXML
    private TableColumn<OneIceServerWrapper, String> transportColumn;
    @FXML
    private TableColumn<OneIceServerWrapper, String> addressColumn;
    @FXML
    private TableColumn<OneIceServerWrapper, String> rttColumn;
    @FXML
    private TableColumn<OneIceServerWrapper, Boolean> enabledColumn;

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
        typeColumn.setCellValueFactory(data -> data.getValue().getType());
        transportColumn.setCellValueFactory(data -> data.getValue().getTransport());
        addressColumn.setCellValueFactory(data -> data.getValue().getAddress());
        rttColumn.setCellValueFactory(data -> data.getValue().getRtt());
        enabledColumn.setCellValueFactory(data -> data.getValue().getEnabled());
        enabledColumn.setCellFactory(CheckBoxTableCell.forTableColumn(enabledColumn));
        enabledColumn.setEditable(true);
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        Platform.runLater(() -> {
            tableView.getItems().setAll(adapter.getIceServersList());
        });
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(() -> {
            Platform.runLater(this::updateAllInfo);
        }, 0, 1, TimeUnit.MINUTES);
    }

}
