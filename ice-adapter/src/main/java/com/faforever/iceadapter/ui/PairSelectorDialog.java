package com.faforever.iceadapter.ui;

import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.services.UIAdapter;
import com.faforever.iceadapter.ui.controller.PairSelectorDialogController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PairSelectorDialog {

    public static void launch(UIAdapter adapter, PeerView peer) {
        try {
            FXMLLoader loader = new FXMLLoader(PairSelectorDialog.class.getResource("/PairSelectorDialog.fxml"));
            Parent root = loader.load();

            PairSelectorDialogController controller = loader.getController();
            controller.setAdapter(adapter, peer);

            Stage stage = new Stage();
            Scene scene = new Scene(root);
            stage.setScene(scene);
            stage.setTitle("Select Active Pair");
            stage.initModality(Modality.WINDOW_MODAL);
            stage.showAndWait();
        } catch (Exception e) {
            log.error("Could not load PairSelectorDialog", e);
        }
    }
}
