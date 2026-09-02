package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.PairView;
import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.services.UIAdapter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PairSelectorDialogController {
    @FXML
    private AnchorPane root;
    @FXML
    private TableView<PairView> pairTable;
    @FXML
    private Label peerRttLabel;
    @FXML
    private TableColumn<PairView, String> localTypeColumn;
    @FXML
    private TableColumn<PairView, String> localAddressColumn;
    @FXML
    private TableColumn<PairView, String> remoteTypeColumn;
    @FXML
    private TableColumn<PairView, String> remoteAddressColumn;
    @FXML
    private TableColumn<PairView, String> pairTypeColumn;
    @FXML
    private TableColumn<PairView, Boolean> isActiveColumn;
    @FXML
    private Button closeButton;
    @FXML
    private Button selectButton;

    private UIAdapter adapter;
    private PeerView selectedPeer;
    private ObservableList<PairView> pairViews = FXCollections.observableArrayList();

    public void setAdapter(UIAdapter adapter, PeerView peer) {
        this.adapter = adapter;
        this.selectedPeer = peer;
        updatePairs();
    }

    @FXML
    private void initialize() {
        setupColumns();
    }

    private void setupColumns() {
        localTypeColumn.setCellValueFactory(d -> d.getValue().localType());
        localAddressColumn.setCellValueFactory(d -> d.getValue().localAddress());
        remoteTypeColumn.setCellValueFactory(d -> d.getValue().remoteType());
        remoteAddressColumn.setCellValueFactory(d -> d.getValue().remoteAddress());
        pairTypeColumn.setCellValueFactory(d -> d.getValue().pairType());
        isActiveColumn.setCellValueFactory(d -> d.getValue().isActive());
        isActiveColumn.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item ? "\u2713" : "");
            }
        });
    }

    @FXML
    private void onSelectClicked() {
        PairView selected = pairTable.getSelectionModel().getSelectedItem();
        if (selected != null && adapter != null && selectedPeer != null) {
            adapter.selectPair(selectedPeer, selected.getCandidatePair());
            // Сохраняем текущий выбор
            PairView currentSelection = selected;
            Platform.runLater(() -> {
                updatePairs();
                // Восстанавливаем выбор
                pairTable.getSelectionModel().select(currentSelection);
            });
        }
    }

    private void updatePairs() {
        if (adapter == null || selectedPeer == null) {
            log.warn("updatePairs: adapter={}, selectedPeer={}", adapter, selectedPeer);
            return;
        }

        // RTT берём из selectedPeer (уже обновляется через PeerEventListener)
        String rttText = selectedPeer.getRtt().get();
        peerRttLabel.setText("RTT: " + (rttText != null ? rttText + " ms" : "- ms"));

        pairViews.setAll(adapter.getSucceededPairs(selectedPeer));
        pairTable.setItems(pairViews);
    }

    @FXML
    private void onCloseClicked() {
        Stage stage = (Stage) root.getScene().getWindow();
        stage.close();
    }
}
