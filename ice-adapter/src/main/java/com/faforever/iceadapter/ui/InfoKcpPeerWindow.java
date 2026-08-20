package com.faforever.iceadapter.ui;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.LogoUtils;
import com.faforever.iceadapter.services.impl.UIAdapterImpl;
import com.faforever.iceadapter.ui.controller.InfoKcpPeerController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import static javafx.application.Application.STYLESHEET_MODENA;
import static javafx.application.Application.setUserAgentStylesheet;

@Slf4j
public class InfoKcpPeerWindow {

    public static InfoKcpPeerWindow INSTANCE;

    private InfoKcpPeerController controller;

    private Stage stage;
    private Parent root;

    public void start(Stage primaryStage) {
        INSTANCE = this;
        this.stage = primaryStage;
        LogoUtils.getLogoFx().ifPresent(logo -> {
            stage.getIcons().add(logo);
        });
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/KcpStatsTable.fxml"));
            root = loader.load();
            controller = loader.getController();
            controller.setAdapter(new UIAdapterImpl(IceAdapter.INSTANCE));
            controller.initialize();
        } catch (Exception e) {
            log.error("Failed to load KCP stats FXML", e);
            return;
        }

        setUserAgentStylesheet(STYLESHEET_MODENA);

        Scene scene = new Scene(root);
        primaryStage.setTitle("KCP Statistics");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> minimize());
        primaryStage.show();
    }

    public void showWindow() {
        runOnUIThread(() -> {
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
    }

    public void minimize() {
        Platform.setImplicitExit(false);
        runOnUIThread(stage::hide);
    }

    private static void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    public static void launch() {
        log.info("Launching KCP Statistics window.");
        if (INSTANCE == null) {
            runOnUIThread(() -> new InfoKcpPeerWindow().start(new Stage()));
        } else {
            INSTANCE.showWindow();
        }
    }
}
