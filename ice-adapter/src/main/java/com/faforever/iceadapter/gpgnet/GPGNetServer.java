package com.faforever.iceadapter.gpgnet;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.LockUtil;
import com.faforever.iceadapter.util.NetworkToolbox;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GPGNetServer implements AutoCloseable {
    private static GPGNetServer INSTANCE;

    private final Lock lockSocket = new ReentrantLock();

    private int gpgnetPort;
    private int lobbyPort;
    private RPCService rpcService;
    private ServerSocket serverSocket;
    private volatile GPGNetClient currentClient;

    // Used by other services to get a callback on FA connecting
    private volatile CompletableFuture<GPGNetClient> clientFuture = new CompletableFuture<>();

    public void sendToGpgNet(String header, Object... args) {
        clientFuture.thenAccept(gpgNetClient ->
                gpgNetClient.getLobbyFuture().thenRun(() -> gpgNetClient.sendGpgnetMessage(header, args)));
    }

    @Setter
    private volatile LobbyInitMode lobbyInitMode = LobbyInitMode.NORMAL;

    public static LobbyInitMode getLobbyInitMode() {
        return INSTANCE.lobbyInitMode;
    }

    public void init(int gpgnetPort, int lobbyPort, RPCService rpcService) {
        INSTANCE = this;
        this.rpcService = rpcService;

        // Автоматический выбор портов
        this.gpgnetPort = gpgnetPort != 0 ? gpgnetPort : NetworkToolbox.findFreeTCPPort(20000, 65536);
        this.lobbyPort = lobbyPort != 0 ? lobbyPort : NetworkToolbox.findFreeUDPPort(20000, 65536);

        log.info("Using GPGNET_PORT: {}, LOBBY_PORT: {}", this.gpgnetPort, this.lobbyPort);

        try {
            this.serverSocket = new ServerSocket(this.gpgnetPort);
            CompletableFuture.runAsync(this::acceptThread, IceAdapter.getExecutor());
            log.info("GPGNetServer started");
        } catch (IOException e) {
            log.error("Failed to start GPGNetServer on port {}", this.gpgnetPort, e);
            IceAdapter.close(-1);
        }
    }

    /**
     * Represents a client (a game instance) connected to this GPGNetServer
     */
    @Getter
    public class GPGNetClient {
        private volatile GameState gameState = GameState.NONE;
        private final Socket socket;
        private final Thread listenerThread;
        private volatile boolean stopping = false;
        private FaDataOutputStream gpgnetOut;
        private final Lock lockStream = new ReentrantLock();
        private final CompletableFuture<GPGNetClient> lobbyFuture = new CompletableFuture<>();

        private GPGNetClient(Socket socket) {
            this.socket = socket;
            try {
                gpgnetOut = new FaDataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                log.error("Failed to create output stream to FA", e);
            }

            listenerThread = Thread.startVirtualThread(this::listenerThread);
            rpcService.onConnectionStateChanged("Connected");
            log.info("GPGNetClient connected");
        }

        private void processGpgnetMessage(String command, List<Object> args) {
            switch (command) {
                case "GameState" -> {
                    String stateName = (String) args.get(0);
                    gameState = GameState.getByName(stateName);
                    log.debug("GameState changed: {}", gameState.getName());

                    if (gameState == GameState.IDLE) {
                        sendGpgnetMessage(
                                "CreateLobby",
                                lobbyInitMode.getId(),
                                GPGNetServer.getLobbyPort(),
                                IceAdapter.getLogin(),
                                IceAdapter.getId(),
                                1
                        );
                    } else if (gameState == GameState.LOBBY) {
                        lobbyFuture.complete(this);
                    }

                    debug().gameStateChanged();
                }
                case "GameEnded" -> {
                    var session = IceAdapter.getGameSession();
                    if (session != null) {
                        session.setGameEnded(true);
                        log.info("GameEnded received, stopping reconnects");
                    }
                }
                default -> {
                    // No need to log, as we are not processing all messages but just forward them via RPC
                }
            }
            log.debug("Received GPGNet message: {} {}", command, formatArgs(args));
            rpcService.onGpgNetMessageReceived(command, args);
        }

        /**
         * Send a message to this FA instance via GPGNet
         */
        public void sendGpgnetMessage(String command, Object... args) {
            LockUtil.executeWithLock(lockStream, () -> {
                if (gpgnetOut == null) {
                    return;
                }
                try {
                    gpgnetOut.writeMessage(command, args);
                    log.info("Sent GPGNet message: {} {}", command, formatArgs(args));
                } catch (IOException e) {
                    log.error("Error sending to FA", e);
                    onGpgnetConnectionLost();
                }
            });
        }

        private void listenerThread() {
            log.debug("Starting GPGNet input listener");
            boolean triggerActive = false;

            try (var gpgnetIn = new FaDataInputStream(socket.getInputStream())) {
                while (!Thread.currentThread().isInterrupted() && !stopping && (triggerActive || currentClient == this)) {
                    String command = gpgnetIn.readString();
                    List<Object> args = gpgnetIn.readChunks();

                    processGpgnetMessage(command, args);

                    if (!triggerActive && currentClient == this) {
                        triggerActive = true;
                    }
                }
            } catch (IOException e) {
                if (!(e instanceof SocketException && e.getMessage().contains("Socket closed"))) {
                    log.error("GPGNet input error", e);
                } else {
                    log.error("GPGNet error", e);
                }
            } finally {
                log.debug("GPGNet listener stopped");
                GPGNetServer.this.onGpgnetConnectionLost();
            }
        }

        public void close() {
            if (stopping) return;
            stopping = true;

            listenerThread.interrupt();
            try {
                socket.close();
            } catch (IOException e) {
                log.warn("Error closing GPGNetClient socket", e);
            }

            log.debug("GPGNetClient closed");
        }
    }

    /**
     * Closes all connections to the current client, removes this client.
     * To be called on encountering an error during the communication with the game instance
     * or on receiving an incoming connection request while still connected to a different instance.
     * THIS TRIGGERS A DISCONNECT FROM ALL PEERS AND AN ICE SHUTDOWN.
     */
    private void onGpgnetConnectionLost() {
        log.info("GPGNet connection lost");
        LockUtil.executeWithLock(lockSocket, () -> {
            if (currentClient == null) {
                return;
            }

            currentClient = null;
            if (clientFuture.isDone()) {
                clientFuture = new CompletableFuture<>();
            }

            rpcService.onConnectionStateChanged("Disconnected");
            debug().gpgnetConnectedDisconnected();
        });
    }

    /**
     * Listens for incoming connections from a game instance
     */
    private void acceptThread() {
        log.info("GPGNetServer accept loop started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = serverSocket.accept();
                log.debug("New connection from FA detected");

                LockUtil.executeWithLock(lockSocket, () -> {
                    if (currentClient != null) {
                        log.info("Replacing existing GPGNetClient");
                        currentClient.close();
                    }

                    currentClient = new GPGNetClient(socket);
                    clientFuture.complete(currentClient);
                    debug().gpgnetConnectedDisconnected();
                });
            } catch (SocketException e) {
                if (!serverSocket.isClosed()) {
                    log.error("Unexpected socket exception in accept loop", e);
                }
                break; // Сервер закрыт — выходим
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.error("IO error in accept loop", e);
                }
            }
        }
        log.info("GPGNetServer accept loop stopped");
    }

    /**
     * @return whether the game is connected via GPGNET
     */
    public static boolean isConnected() {
        return INSTANCE != null && INSTANCE.currentClient != null;
    }


    public static String getGameStateString() {
        return getGameState().map(GameState::getName).orElse("");
    }

    public static Optional<GameState> getGameState() {
        return Optional.ofNullable(INSTANCE)
                .map(s -> s.currentClient)
                .map(GPGNetClient::getGameState);
    }

    public static int getGpgnetPort() {
        return INSTANCE.gpgnetPort;
    }

    public static int getLobbyPort() {
        return INSTANCE.lobbyPort;
    }

    /**
     * Stops the GPGNetServer and thereby the connection to a currently connected client
     */
    @Override
    public void close() {
        log.info("Shutting down GPGNetServer");
        LockUtil.executeWithLock(lockSocket, () -> {
            if (currentClient != null) {
                currentClient.close();
                currentClient = null;
                clientFuture = new CompletableFuture<>();
            }

            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    log.error("Failed to close server socket", e);
                }
            }
        });
        log.info("GPGNetServer stopped");
    }

    // Утилита: форматирует аргументы в строку
    private static String formatArgs(Object... args) {
        return Stream.of(args)
                .map(arg -> arg instanceof Double d ? d.intValue() + "" : String.valueOf(arg))
                .collect(java.util.stream.Collectors.joining(" "));
    }
}