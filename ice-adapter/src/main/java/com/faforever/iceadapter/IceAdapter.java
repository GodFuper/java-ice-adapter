package com.faforever.iceadapter;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.PeerIceModule;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.ExecutorHolder;
import com.faforever.iceadapter.util.LockUtil;
import com.faforever.iceadapter.util.TrayIcon;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

@CommandLine.Command(
        name = "faf-ice-adapter",
        mixinStandardHelpOptions = true,
        usageHelpAutoWidth = true,
        description = "An ice (RFC 5245) based network bridge between FAF client and ForgedAlliance.exe")
@Slf4j
public class IceAdapter implements Callable<Integer>, AutoCloseable, FafRpcCallbacks {
    private static IceAdapter INSTANCE;
    private static String VERSION = "SNAPSHOT";
    private static volatile GameSession GAME_SESSION;

    @CommandLine.ArgGroup(exclusive = false)
    private IceOptions iceOptions;

    private final ExecutorService executor = ExecutorHolder.getExecutor();
    private static final Lock lockGameSession = new ReentrantLock();

    public static void main(String[] args) {
        new CommandLine(new IceAdapter()).setUnmatchedArgumentsAllowed(true).execute(args);
    }

    @Override
    public Integer call() {
        INSTANCE = this;

        start();
        return 0;
    }

    public void start() {
        determineVersion();
        log.info("Version: {}", VERSION);

        Debug.DELAY_UI_MS = iceOptions.getDelayUi();
        Debug.ENABLE_DEBUG_WINDOW = iceOptions.isDebugWindow();
        Debug.ENABLE_INFO_WINDOW = iceOptions.isInfoWindow();
        Debug.init();

        TrayIcon.create();

        PeerIceModule.setForceRelay(iceOptions.isForceRelay());
        GPGNetServer.init(iceOptions.getGpgnetPort(), iceOptions.getLobbyPort());
        RPCService.init(iceOptions.getRpcPort(), this);

        debug().startupComplete();
    }

    @Override
    public void onHostGame(String mapName) {
        log.info("onHostGame");
        createGameSession();

        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage("HostGame", mapName);
            });
        });
    }

    @Override
    public void onJoinGame(String remotePlayerLogin, int remotePlayerId) {
        log.info("onJoinGame {} {}", remotePlayerId, remotePlayerLogin);
        createGameSession();
        int port = GAME_SESSION.connectToPeer(remotePlayerLogin, remotePlayerId, false, 0);

        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage("JoinGame", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
            });
        });
    }

    @Override
    public void onConnectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer) {
        if (GPGNetServer.isConnected()
                && GPGNetServer.getGameState().isPresent()
                && (GPGNetServer.getGameState().get() == GameState.LAUNCHING
                        || GPGNetServer.getGameState().get() == GameState.ENDED)) {
            log.warn("Game ended or in progress, ABORTING connectToPeer");
            return;
        }

        log.info("onConnectToPeer {} {}, offer: {}", remotePlayerId, remotePlayerLogin, String.valueOf(offer));
        int port = GAME_SESSION.connectToPeer(remotePlayerLogin, remotePlayerId, offer, 0);

        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage("ConnectToPeer", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
            });
        });
    }

    @Override
    public void onDisconnectFromPeer(int remotePlayerId) {
        log.info("onDisconnectFromPeer {}", remotePlayerId);
        GAME_SESSION.disconnectFromPeer(remotePlayerId);

        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage("DisconnectFromPeer", remotePlayerId);
            });
        });
    }

    private static void createGameSession() {
        LockUtil.executeWithLock(lockGameSession, () -> {
            if (GAME_SESSION != null) {
                GAME_SESSION.close();
                GAME_SESSION = null;
            }

            GAME_SESSION = new GameSession();
        });
    }

    /**
     * Triggered by losing gpgnet connection to FA.
     * Closes the active Game/ICE session
     */
    public static void onFAShutdown() {
        LockUtil.executeWithLock(lockGameSession, () -> {
            if (GAME_SESSION != null) {
                log.info("FA SHUTDOWN, closing everything");
                GAME_SESSION.close();
                GAME_SESSION = null;
                // Do not put code outside of this if clause, else it will be executed multiple times
            }
        });
    }

    @Override
    public void close() {
        this.close(0);
    }

    /**
     * Stop the ICE adapter
     */
    public static void close(int status) {
        log.info("close() - stopping the adapter. Status: {}", status);

        onFAShutdown(); // will close gameSession aswell
        GPGNetServer.close();
        RPCService.close();
        Debug.close();
        TrayIcon.close();
        INSTANCE.close();

        INSTANCE.executor.shutdown();
        CompletableFuture.runAsync(
                        INSTANCE.executor::shutdownNow, CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS))
                .thenRunAsync(() -> System.exit(status), CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS));
    }

    public static int getId() {
        return INSTANCE.iceOptions.getId();
    }

    public static String getVersion() {
        return VERSION;
    }

    public static int getGameId() {
        return INSTANCE.iceOptions.getGameId();
    }

    public static String getLogin() {
        return INSTANCE.iceOptions.getLogin();
    }

    public static String getTelemetryServer() {
        return INSTANCE.iceOptions.getTelemetryServer();
    }

    public static int getPingCount() {
        return INSTANCE.iceOptions.getPingCount();
    }

    public static double getAcceptableLatency() {
        return INSTANCE.iceOptions.getAcceptableLatency();
    }

    public static Executor getExecutor() {
        return INSTANCE.executor;
    }

    public static GameSession getGameSession() {
        return GAME_SESSION;
    }

    private void determineVersion() {
        String versionFromGradle = getClass().getPackage().getImplementationVersion();
        if (versionFromGradle != null) {
            VERSION = versionFromGradle;
        }
    }
}
