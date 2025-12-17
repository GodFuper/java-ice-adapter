package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.LogoUtils;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import static javafx.application.Application.STYLESHEET_MODENA;
import static javafx.application.Application.setUserAgentStylesheet;

@Slf4j
public class IceServerTableView {

    public static IceServerTableView INSTANCE;

    private IceServerController controller;

    private Stage stage;
    private Parent root;

    public void start(Stage primaryStage) {
        INSTANCE = this;
        this.stage = primaryStage;
        LogoUtils.getLogoFx().ifPresent(logo -> {
            stage.getIcons().add(logo);
        });
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/IceServerTable.fxml"));
            root = loader.load();
            controller = loader.getController();
            controller.setAdapter(new UIAdapterImpl(IceAdapter.INSTANCE));
            controller.initialize();
        } catch (Exception e) {
            log.error("Failed to load FXML", e);
            return;
        }

        setUserAgentStylesheet(STYLESHEET_MODENA);

        Scene scene = new Scene(root);
        primaryStage.setTitle("ICE Server Manager");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> minimize());
        primaryStage.show();
    }

    // Wrapper class to make OneIceServer JavaFX-property friendly


    public void showWindow() {
        runOnUIThread(() -> {
            this.stage.show();
            Platform.setImplicitExit(true);
        });
    }

    public void minimize() {
        Platform.setImplicitExit(false);
        runOnUIThread(this.stage::hide);
    }

    private static void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    public static void launch() {
        log.info("Launching IceServerTableApp window.");
        if (INSTANCE == null) {
            runOnUIThread(() -> new IceServerTableView().start(new Stage()));
        } else {
            INSTANCE.showWindow();
        }
    }
}
