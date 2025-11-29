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
    private static volatile IceAdapter INSTANCE;
    private static String VERSION = "SNAPSHOT";
    private static volatile GameSession GAME_SESSION;

    @CommandLine.ArgGroup(exclusive = false)
    private IceOptions iceOptions;

    private GPGNetServer gpgNetServer;
    private RPCService rpcService;

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
        gpgNetServer = new GPGNetServer();
        rpcService = new RPCService();
        gpgNetServer.init(iceOptions.getGpgnetPort(), iceOptions.getLobbyPort(), rpcService);
        rpcService.init(iceOptions.getRpcPort(), gpgNetServer, this);

        PeerIceModule.setRpcService(rpcService);

        debug().startupComplete();
    }

    @Override
    public void onHostGame(String mapName) {
        log.info("onHostGame");
        createGameSession();
        // query session in a thread-safe manner
        GameSession gs = getGameSessionSafe();
        if (gs != null) {
            sendToGpgNet("HostGame", mapName);
        } else {
            log.warn("onHostGame: failed to create game session");
        }
    }

    @Override
    public void onJoinGame(String remotePlayerLogin, int remotePlayerId) {
        log.info("onJoinGame {} {}", remotePlayerId, remotePlayerLogin);
        createGameSession();
        GameSession gs = getGameSessionSafe();
        if (gs != null) {
            int port = gs.connectToPeer(remotePlayerLogin, remotePlayerId, false, 0);
            sendToGpgNet("JoinGame", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
        } else {
            log.warn("onJoinGame: GAME_SESSION was null after createGameSession");
        }
    }

    @Override
    public void onConnectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer) {
        if (gpgNetServer.isConnected()
                && gpgNetServer.getGameState().isPresent()
                && (gpgNetServer.getGameState().get() == GameState.LAUNCHING
                || gpgNetServer.getGameState().get() == GameState.ENDED)) {
            log.warn("Game ended or in progress, ABORTING connectToPeer");
            return;
        }

        log.info("onConnectToPeer {} {}, offer: {}", remotePlayerId, remotePlayerLogin, offer);

        GameSession gs = getGameSessionSafe();
        if (gs == null) {
            log.warn("onConnectToPeer: no active GAME_SESSION, creating one");
            createGameSession();
            gs = getGameSessionSafe();
            if (gs == null) {
                log.error("onConnectToPeer: failed to create GAME_SESSION, aborting");
                return;
            }
        }

        int port;
        try {
            port = gs.connectToPeer(remotePlayerLogin, remotePlayerId, offer, 0);
        } catch (RuntimeException e) {
            log.error("connectToPeer failed for {} {}: {}", remotePlayerId, remotePlayerLogin, e.toString());
            return;
        }

        sendToGpgNet("ConnectToPeer", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
    }

    @Override
    public void onDisconnectFromPeer(int remotePlayerId) {
        log.info("onDisconnectFromPeer {}", remotePlayerId);
        GameSession gs = getGameSessionSafe();
        if (gs != null) {
            gs.disconnectFromPeer(remotePlayerId);
        } else {
            log.warn("onDisconnectFromPeer: GAME_SESSION is null");
        }

        sendToGpgNet("DisconnectFromPeer", remotePlayerId);
    }

    private static void createGameSession() {
        LockUtil.executeWithLock(lockGameSession, () -> {
            if (GAME_SESSION != null) {
                try {
                    GAME_SESSION.close();
                } catch (Exception e) {
                    log.warn("Error closing previous GAME_SESSION", e);
                }
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
                try {
                    GAME_SESSION.close();
                } catch (Exception e) {
                    log.warn("Error while closing GAME_SESSION during onFAShutdown", e);
                }
                GAME_SESSION = null;
                // Do not put code outside of this if clause, else it will be executed multiple times
            }
        });
    }

    @Override
    public void close() {
        close(0);
    }

    /**
     * Stop the ICE adapter
     */
    public static void close(int status) {
        IceAdapter instance = INSTANCE;
        if (instance == null) {
            log.warn("close() called but INSTANCE is null");
            System.exit(status);
            return;
        }

        log.info("close() - stopping the adapter. Status: {}", status);

        onFAShutdown(); // will close gameSession aswell

        try {
            instance.gpgNetServer.close();
        } catch (Exception e) {
            log.warn("Error closing GPGNetServer", e);
        }
        try {
            instance.rpcService.close();
        } catch (Exception e) {
            log.warn("Error closing RPCService", e);
        }

        Debug.close();
        TrayIcon.close();

        // Shutdown the executor gracefully. Don't schedule shutdownNow on the same executor.
        instance.executor.shutdown();
        try {
            if (!instance.executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                log.info("Executor did not terminate in 500ms, requesting shutdownNow");
                instance.executor.shutdownNow();
                // give a short grace before exit
                instance.executor.awaitTermination(250, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for executor termination", e);
            instance.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.exit(status);
    }

    @Override
    public void sendToGpgNet(String header, Object... args) {
        if (gpgNetServer != null) {
            gpgNetServer.sendToGpgNet(header, args);
        } else {
            log.warn("sendToGpgNet called but gpgNetServer is null: {}", header);
        }
    }

    public static int getId() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getId() : 0;
    }

    public static String getVersion() {
        return VERSION;
    }

    public static int getGameId() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getGameId() : 0;
    }

    public static String getLogin() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getLogin() : "";
    }

    public static String getTelemetryServer() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getTelemetryServer() : null;
    }

    public static int getPingCount() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getPingCount() : 0;
    }

    public static double getAcceptableLatency() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getAcceptableLatency() : Double.MAX_VALUE;
    }

    public static Executor getExecutor() {
        IceAdapter instance = INSTANCE;
        return instance != null ? instance.executor : Executors.newSingleThreadExecutor();
    }

    public static GameSession getGameSession() {
        return GAME_SESSION;
    }

    private static GameSession getGameSessionSafe() {
        // quick non-blocking read followed by a locked check to avoid races
        if (GAME_SESSION == null) return null;
        final GameSession[] holder = new GameSession[1];
        LockUtil.executeWithLock(lockGameSession, () -> holder[0] = GAME_SESSION);
        return holder[0];
    }

    private void determineVersion() {
        String versionFromGradle = getClass().getPackage().getImplementationVersion();
        if (versionFromGradle != null) {
            VERSION = versionFromGradle;
        }
    }
}
