package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.LogoUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
@EqualsAndHashCode(callSuper = false)
public class InfoWindow extends Application {

    public static InfoWindow INSTANCE;

    private Stage stage;
    private Parent root;
    private Scene scene;
    private InfoWindowController controller;

    private static final int WIDTH = 533;
    private static final int HEIGHT = 330;

    @Override
    public void start(Stage stage) {
        INSTANCE = this;
        this.stage = stage;
        LogoUtils.getLogoFx().ifPresent(logo -> {
            stage.getIcons().add(logo);
        });
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/infoWindow.fxml"));
            root = loader.load();
            controller = loader.getController();
        } catch (IOException e) {
            log.error("Could not load debugger window fxml", e);
        }

        setUserAgentStylesheet(STYLESHEET_MODENA);

        scene = new Scene(root, WIDTH, HEIGHT);

        stage.setScene(scene);
        stage.setTitle("FAF ICE adapter");
        stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
            @Override
            public void handle(WindowEvent event) {
                minimize();
            }
        });
        stage.show();

        log.info("Created info window.");
    }

    public void minimize() {
        Platform.setImplicitExit(false);
        runOnUIThread(this.stage::hide);
    }

    public void showWindow() {
        runOnUIThread(() -> {
            this.stage.show();
            Platform.setImplicitExit(true);
        });
    }

    private static void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    public static void launch() {
        log.info("Launching info window.");
        if (INSTANCE == null) {
            try {
                launch(InfoWindow.class, null);
            } catch (IllegalStateException e) {
                runOnUIThread(() -> new InfoWindow().start(new Stage()));
            }
        } else {
            INSTANCE.showWindow();
        }
    }
}
