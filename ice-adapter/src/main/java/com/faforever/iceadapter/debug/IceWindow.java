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
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/iceWindow.fxml"));
            root = loader.load();

            controller = loader.getController();
            controller.setAdapter(new UIAdapterImpl(IceAdapter.INSTANCE));
            controller.initialize();
        } catch (IOException e) {
            log.error("Could not load debugger window fxml", e);
        }

        setUserAgentStylesheet(STYLESHEET_MODENA);

        scene = new Scene(root);

        stage.setScene(scene);
        stage.setTitle("FAF ICE adapter - Debugger - Build: %s".formatted(IceAdapter.getVersion()));

        showWindow();

        log.info("Created debug window.");
    }

    public void showWindow() {
        runOnUIThread(() -> {
            stage.show();
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
        log.info("Launching ice window.");
        if (INSTANCE == null) {
            try {
                launch(IceWindow.class, null);
            } catch (IllegalStateException e) {
                runOnUIThread(() -> new IceWindow().start(new Stage()));
            }
        } else {
            INSTANCE.showWindow();
        }
    }
}
