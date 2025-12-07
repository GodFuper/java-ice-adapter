package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class Debug {
    // TODO
    public static boolean ENABLE_DEBUG_WINDOW_LOG_TEXT_AREA =
            false; // disabled as this causes high memory and cpu load, should be replaced by limiting the number of
    // lines in the text area

    public static boolean ENABLE_DEBUG_WINDOW = false;
    public static boolean ENABLE_INFO_WINDOW = false;
    public static int DELAY_UI_MS = 0; // delays the launch of the user interface by X ms

    public static int RPC_PORT;

    private static TelemetryDebugger telemetryDebugger;

    private static final DebugFacade debugFacade = new DebugFacade();

    public static void register(Debugger debugger) {
        debugFacade.add(debugger);
    }

    public static void remove(Debugger debugger) {
        debugFacade.remove(debugger);
    }

    public static void init() {
        telemetryDebugger =
                new TelemetryDebugger(IceAdapter.getTelemetryServer(), IceAdapter.getGameId(), IceAdapter.getId());
        register(telemetryDebugger);
        if (isJavaFxSupported()) {
            if (Debug.ENABLE_DEBUG_WINDOW) {
                CompletableFuture.runAsync(IceWindow::launch);
            }
            if (Debug.ENABLE_INFO_WINDOW) {
                CompletableFuture.runAsync(InfoWindow::launch);
            }
        } else {
            log.info("No JavaFX support detected. Running without debug window.");
        }
    }

    public static void close() {
        if (telemetryDebugger != null) {
            telemetryDebugger.close();
            telemetryDebugger = null;
        }
    }

    public static Debugger debug() {
        return debugFacade;
    }

    public static boolean isJavaFxSupported() {
        try {
            Debug.class.getClassLoader().loadClass("javafx.application.Application");
            return true;
        } catch (ClassNotFoundException e) {
            log.warn("Could not create debug window, no JavaFX found.");
            return false;
        }
    }
}
