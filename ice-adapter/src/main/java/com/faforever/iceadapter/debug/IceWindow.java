package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.LogoUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@EqualsAndHashCode(callSuper = false)
public class IceWindow extends Application {
    private static IceWindow INSTANCE;

    private Parent root;
    private Scene scene;
    private WindowController controller;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        INSTANCE = this;
        this.stage = stage;
        LogoUtils.getLogoFx().ifPresent(logo -> {
            stage.getIcons().add(logo);
        });


        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/debugWindow2.fxml"));
            root = loader.load();

            controller = loader.getController();
            controller.setAdapter(new DebugAdapterImpl(IceAdapter.INSTANCE));
            controller.initialize();
        } catch (IOException e) {
            log.error("Could not load debugger window fxml", e);
        }

        setUserAgentStylesheet(STYLESHEET_MODENA);

        scene = new Scene(root);

        stage.setScene(scene);
        stage.setTitle("FAF ICE adapter - Debugger - Build: %s".formatted(IceAdapter.getVersion()));

        if (Debug.ENABLE_DEBUG_WINDOW) {
            showWindow();
        }

        log.info("Created debug window.");

        if (Debug.ENABLE_INFO_WINDOW) {
            CompletableFuture.runAsync(
                    () -> runOnUIThread(InfoWindow::launch),
                    CompletableFuture.delayedExecutor(
                            Debug.DELAY_UI_MS, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));
        }
    }

    public void showWindow() {
        runOnUIThread(() -> {
            stage.show();
        });
    }


    private void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    public static void launch() {
        if (INSTANCE == null) {
            launch(IceWindow.class, null);
        } else {
            INSTANCE.showWindow();
        }
    }
}
