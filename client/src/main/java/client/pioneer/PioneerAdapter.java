package client.pioneer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import logging.Logger;
import lombok.Getter;

/**
 * Manages the faf-pioneer (Go adapter) process, built-in MockIcebreaker, and GPGNet launcher server.
 */
public class PioneerAdapter {

    private static final String FAF_PIONEER_DIR = "temp/faf-pioneer";

    @Getter
    private final int userId;

    @Getter
    private final String userName;

    @Getter
    private final long gameId;

    @Getter
    private final String accessToken;

    @Getter
    private final String apiRoot;

    @Getter
    private int gpgNetPort;

    @Getter
    private int gpgNetClientPort;

    @Getter
    private int lobbyPort;

    private Process process;
    private PioneerGpgNetLauncherServer launcherServer;
    @Getter
    private MockIcebreakerHttpServer mockIcebreaker;

    @Getter
    private final BooleanProperty connected = new SimpleBooleanProperty(false);

    @Getter
    private final StringProperty statusText = new SimpleStringProperty("Stopped");

    @Getter
    private final StringProperty gameState = new SimpleStringProperty("Unknown");

    public PioneerAdapter(int userId, String userName, long gameId, String accessToken, String apiRoot) {
        this.userId = userId;
        this.userName = userName;
        this.gameId = gameId;
        this.accessToken = accessToken != null && !accessToken.isEmpty() ? accessToken : "test-token";
        this.apiRoot = apiRoot;
    }

    public synchronized void start() throws Exception {
        if (process != null && process.isAlive()) {
            Logger.warning("Pioneer adapter is already running");
            return;
        }

        // Allocate free ports
        this.gpgNetPort = findFreePort();
        this.gpgNetClientPort = findFreePort();
        this.lobbyPort = findFreePort();

        String effectiveApiRoot = apiRoot;
        if (effectiveApiRoot == null || effectiveApiRoot.isEmpty() || effectiveApiRoot.contains("localhost") || effectiveApiRoot.contains("127.0.0.1")) {
            int icebreakerPort = findFreePort();
            mockIcebreaker = new MockIcebreakerHttpServer(icebreakerPort);
            mockIcebreaker.start();
            effectiveApiRoot = mockIcebreaker.getUrl();
            Logger.info("Using built-in MockIcebreaker at " + effectiveApiRoot);
        }

        statusText.set("Starting GPGNet server on port " + gpgNetClientPort);
        launcherServer = new PioneerGpgNetLauncherServer(gpgNetClientPort);
        launcherServer.addConnectionListener(isConnected -> {
            connected.set(isConnected);
            if (isConnected) {
                statusText.set("Adapter Connected");
                Logger.info("faf-pioneer connected to GPGNet launcher server");
            } else {
                statusText.set("Adapter Disconnected");
            }
        });

        launcherServer.addMessageListener((cmd, chunks) -> {
            if ("GameState".equalsIgnoreCase(cmd) && !chunks.isEmpty()) {
                gameState.set(String.valueOf(chunks.get(0)));
            }
        });

        launcherServer.start();

        // Locate or build faf-adapter binary
        File executable = findOrBuildPioneerExecutable();
        if (executable == null || !executable.exists()) {
            throw new IllegalStateException("faf-adapter executable could not be found or built");
        }

        statusText.set("Launching " + executable.getName());

        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.add("--user-id=" + userId);
        command.add("--user-name=" + userName);
        command.add("--game-id=" + gameId);
        command.add("--access-token=" + accessToken);
        command.add("--api-root=" + effectiveApiRoot);
        command.add("--gpgnet-port=" + gpgNetPort);
        command.add("--gpgnet-client-port=" + gpgNetClientPort);
        command.add("--log-level=0");

        Logger.info("Starting Pioneer process: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        File pioneerDir = executable.getParentFile();
        if (pioneerDir != null) {
            pb.directory(pioneerDir);
        }

        this.process = pb.start();

        // Pipe stdout/stderr to logger
        startProcessLogging(process);

        // Wait up to 5 seconds for faf-adapter to connect to launcher server
        long startWait = System.currentTimeMillis();
        while (!connected.get() && System.currentTimeMillis() - startWait < 5000) {
            if (!process.isAlive()) {
                throw new IllegalStateException("faf-adapter process terminated unexpectedly with exit code " + process.exitValue());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        if (connected.get()) {
            Logger.info("faf-adapter connected to launcher server successfully");
        }
    }

    public synchronized void stop() {
        statusText.set("Stopping");
        if (launcherServer != null) {
            launcherServer.stop();
            launcherServer = null;
        }

        if (mockIcebreaker != null) {
            mockIcebreaker.stop();
            mockIcebreaker = null;
        }

        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            process = null;
        }

        connected.set(false);
        statusText.set("Stopped");
        gameState.set("Unknown");
    }

    public void hostGame(String mapName) {
        if (launcherServer != null) {
            launcherServer.createLobby(0, lobbyPort, userName, userId, 1);
            launcherServer.hostGame(mapName);
        }
    }

    public void joinGame(String hostAddress, String hostPlayerName, int hostPlayerId) {
        if (launcherServer != null) {
            launcherServer.createLobby(0, lobbyPort, userName, userId, 1);
            launcherServer.joinGame(hostAddress, hostPlayerName, hostPlayerId);
        }
    }

    public void connectToPeer(String peerAddress, String peerPlayerName, int peerPlayerId) {
        if (launcherServer != null) {
            launcherServer.connectToPeer(peerAddress, peerPlayerName, peerPlayerId);
        }
    }

    public void disconnectFromPeer(int peerPlayerId) {
        if (launcherServer != null) {
            launcherServer.disconnectFromPeer(peerPlayerId);
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static File findOrBuildPioneerExecutable() {
        List<File> candidates = List.of(
                new File("../" + FAF_PIONEER_DIR + "/faf-adapter.exe"),
                new File(FAF_PIONEER_DIR + "/faf-adapter.exe"),
                new File("../" + FAF_PIONEER_DIR + "/cmd/faf-adapter/faf-adapter.exe"),
                new File(FAF_PIONEER_DIR + "/cmd/faf-adapter/faf-adapter.exe"),
                new File("../" + FAF_PIONEER_DIR + "/faf-adapter"),
                new File(FAF_PIONEER_DIR + "/faf-adapter"));

        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                return candidate;
            }
        }

        // Build with go if available
        File pioneerDir = new File("../" + FAF_PIONEER_DIR);
        if (!pioneerDir.exists()) {
            pioneerDir = new File(FAF_PIONEER_DIR);
        }

        if (pioneerDir.exists()) {
            try {
                Logger.info("Compiling faf-adapter in " + pioneerDir.getAbsolutePath());
                ProcessBuilder pb = new ProcessBuilder("go", "build", "-o", "faf-adapter.exe", "./cmd/faf-adapter");
                pb.directory(pioneerDir);
                Process p = pb.start();
                if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    File built = new File(pioneerDir, "faf-adapter.exe");
                    if (built.exists()) {
                        return built;
                    }
                }
            } catch (Exception e) {
                Logger.error("Failed to build faf-adapter via go", e);
            }
        }

        return null;
    }

    private void startProcessLogging(Process process) {
        Thread outThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Logger.info("[Pioneer-Adapter] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "Pioneer-Stdout");
        outThread.setDaemon(true);
        outThread.start();

        Thread errThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Logger.warning("[Pioneer-Adapter-Err] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "Pioneer-Stderr");
        errThread.setDaemon(true);
        errThread.start();
    }
}
