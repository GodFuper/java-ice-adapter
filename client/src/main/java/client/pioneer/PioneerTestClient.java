package client.pioneer;

import javafx.application.Application;
import logging.Logger;

/**
 * Test Client launcher for faf-pioneer (analogous to client.TestClient).
 */
public class PioneerTestClient {

    public static int userId = 1;
    public static String userName = "PioneerTester";
    public static long gameId = 12345;
    public static String accessToken = "test-token";
    public static String apiRoot = "http://localhost:8080";
    public static boolean headless = false;
    public static boolean autoStart = true;

    public static void main(String[] args) {
        Logger.info("Starting PioneerTestClient...");

        for (String arg : args) {
            if (arg.startsWith("--user-id=")) {
                userId = Integer.parseInt(arg.substring("--user-id=".length()));
            } else if (arg.startsWith("--user-name=")) {
                userName = arg.substring("--user-name=".length());
            } else if (arg.startsWith("--name=")) {
                userName = arg.substring("--name=".length());
            } else if (arg.startsWith("--game-id=")) {
                gameId = Long.parseLong(arg.substring("--game-id=".length()));
            } else if (arg.startsWith("--access-token=")) {
                accessToken = arg.substring("--access-token=".length());
            } else if (arg.startsWith("--api-root=")) {
                apiRoot = arg.substring("--api-root=".length());
            } else if ("--headless".equalsIgnoreCase(arg) || "--no-gui".equalsIgnoreCase(arg)) {
                headless = true;
            } else if ("--no-autostart".equalsIgnoreCase(arg)) {
                autoStart = false;
            } else if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                printHelp();
                System.exit(0);
            }
        }

        if (headless) {
            Logger.info("Running PioneerTestClient in headless mode for user " + userName + " (" + userId + ")");
            runHeadless();
        } else {
            Logger.info("Launching Pioneer GUI...");
            Application.launch(PioneerGUI.class, args);
        }
    }

    private static void runHeadless() {
        try {
            PioneerTestServerAccessor.init();
            Logger.info("Headless PioneerTestServerAccessor initialized");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Logger.info("Shutting down headless PioneerTestServerAccessor...");
                PioneerTestServerAccessor.stop();
            }));

            // Keep alive
            while (true) {
                Thread.sleep(5000);
                PioneerAdapter adapter = PioneerTestServerAccessor.getAdapter();
                if (adapter != null) {
                    Logger.info("PioneerAdapter running (ID: " + adapter.getUserId() + ")... Status: "
                            + adapter.getStatusText().get() + ", GameState: " + adapter.getGameState().get());
                }
            }
        } catch (Exception e) {
            Logger.error("Fatal error in headless PioneerTestClient", e);
            PioneerTestServerAccessor.stop();
        }
    }

    private static void printHelp() {
        System.out.println("""
                FAF Pioneer Test Client
                Usage:
                  java -cp ... client.pioneer.PioneerTestClient [options]

                Options:
                  --user-id=<id>           User ID (default: 1)
                  --user-name=<name>       Username (default: PioneerTester)
                  --name=<name>            Alias for --user-name
                  --game-id=<id>           Game ID (default: 12345)
                  --access-token=<token>   JWT / access token (default: test-token)
                  --api-root=<url>         Icebreaker API root (default: http://localhost:8080)
                  --headless, --no-gui     Run in CLI headless mode without JavaFX
                  --no-autostart           Do not automatically start adapter and connect to server
                  --help, -h               Show this help message
                """);
    }
}
