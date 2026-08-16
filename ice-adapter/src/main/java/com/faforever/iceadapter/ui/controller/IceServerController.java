package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.IceServerView;
import com.faforever.iceadapter.services.UIAdapter;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
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
public class IceServerController {

    @FXML
    private VBox root;

    @FXML
    private TableView<IceServerView> tableView;

    @FXML
    private TableColumn<IceServerView, String> typeColumn;

    @FXML
    private TableColumn<IceServerView, String> transportColumn;

    @FXML
    private TableColumn<IceServerView, String> addressColumn;

    @FXML
    private TableColumn<IceServerView, String> rttColumn;

    @FXML
    private TableColumn<IceServerView, Boolean> enabledColumn;

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
        enabledColumn.setEditable(true);
        enabledColumn.setCellFactory(col -> new TableCell<IceServerView, Boolean>() {
            private final CheckBox checkBox = new CheckBox();

            {
                checkBox.setOnAction(event -> {
                    IceServerView item = getTableView().getItems().get(getIndex());
                    adapter.setEnabledIceServer(item, checkBox.isSelected());
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);

                setGraphic(null);
                setText(null);

                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    IceServerView server = getTableView().getItems().get(getIndex());
                    if (server.getServer().isTurn()) {
                        checkBox.setSelected(item);
                        setGraphic(checkBox);
                    } else {
                        setText("Auto");
                    }
                }
            }
        });
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        Platform.runLater(() -> {
            if (!Objects.equals(
                    adapter.getIceServersList().size(), tableView.getItems().size())) {
                tableView.getItems().setAll(adapter.getIceServersList());
            }
        });
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(() -> Platform.runLater(this::updateAllInfo), 2, 30, TimeUnit.SECONDS);
    }
}
