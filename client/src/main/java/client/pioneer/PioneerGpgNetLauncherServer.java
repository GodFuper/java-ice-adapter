package client.pioneer;

import client.forgedalliance.FaDataInputStream;
import client.forgedalliance.FaDataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import logging.Logger;
import lombok.Getter;

/**
 * GPGNet Launcher Server for faf-pioneer.
 * Listens on gpgNetClientPort and accepts the TCP connection from faf-adapter.
 * Exchanges binary GPGNet messages to control the lobby and game lifecycle.
 */
public class PioneerGpgNetLauncherServer {

    @Getter
    private final int port;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private FaDataInputStream in;
    private FaDataOutputStream out;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Queue<Runnable> pendingMessages = new ConcurrentLinkedQueue<>();

    @Getter
    private volatile String gameState = "Unknown";

    private final List<Consumer<Boolean>> connectionListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<String, List<Object>>> messageListeners = new CopyOnWriteArrayList<>();

    public PioneerGpgNetLauncherServer(int port) {
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        serverSocket = new ServerSocket(port);
        running.set(true);
        Logger.info("Pioneer GpgNetLauncherServer listening on port " + port);

        Thread acceptThread = new Thread(this::acceptLoop, "Pioneer-GpgNetLauncherServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        connected.set(false);
        pendingMessages.clear();

        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
            clientSocket = null;
        }

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }

        notifyConnectionChanged(false);
        Logger.info("Pioneer GpgNetLauncherServer stopped");
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void addConnectionListener(Consumer<Boolean> listener) {
        connectionListeners.add(listener);
    }

    public void addMessageListener(BiConsumer<String, List<Object>> listener) {
        messageListeners.add(listener);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                Logger.info("Pioneer adapter connected from " + socket.getRemoteSocketAddress());

                synchronized (this) {
                    this.clientSocket = socket;
                    this.in = new FaDataInputStream(socket.getInputStream());
                    this.out = new FaDataOutputStream(socket.getOutputStream());
                    this.connected.set(true);
                }

                notifyConnectionChanged(true);

                // Flush pending messages
                Runnable pending;
                while ((pending = pendingMessages.poll()) != null) {
                    try {
                        pending.run();
                    } catch (Exception e) {
                        Logger.error("Error sending queued GPGNet message", e);
                    }
                }

                // Start reading incoming GPGNet messages
                readMessages(in);

            } catch (IOException e) {
                if (running.get()) {
                    Logger.warning("Error accepting/communicating with Pioneer adapter: " + e.getMessage());
                }
            } finally {
                synchronized (this) {
                    connected.set(false);
                }
                notifyConnectionChanged(false);
            }
        }
    }

    private void readMessages(FaDataInputStream reader) {
        while (running.get() && connected.get()) {
            try {
                String command = reader.readString();
                List<Object> chunks = reader.readChunks();

                Logger.debug("Received GPGNet message from Pioneer: command=" + command + ", chunks=" + chunks);

                if ("GameState".equalsIgnoreCase(command) && !chunks.isEmpty()) {
                    this.gameState = String.valueOf(chunks.get(0));
                    Logger.info("Game state updated to: " + gameState);
                }

                for (BiConsumer<String, List<Object>> listener : messageListeners) {
                    try {
                        listener.accept(command, chunks);
                    } catch (Exception e) {
                        Logger.error("Error in GPGNet message listener", e);
                    }
                }

            } catch (IOException e) {
                Logger.info("Pioneer adapter GPGNet connection closed: " + e.getMessage());
                break;
            }
        }
    }

    private void notifyConnectionChanged(boolean isConnected) {
        for (Consumer<Boolean> listener : connectionListeners) {
            try {
                listener.accept(isConnected);
            } catch (Exception e) {
                Logger.error("Error in connection listener", e);
            }
        }
    }

    // --- Outgoing GPGNet Commands to Pioneer Adapter ---

    public synchronized void createLobby(int lobbyInitMode, int lobbyPort, String localPlayerName, int localPlayerId, int unknownParam) {
        sendMessage("CreateLobby", lobbyInitMode, lobbyPort, localPlayerName, localPlayerId, unknownParam);
    }

    public synchronized void hostGame(String mapName) {
        sendMessage("HostGame", mapName);
    }

    public synchronized void joinGame(String hostAddress, String remotePlayerName, int remotePlayerId) {
        sendMessage("JoinGame", hostAddress, remotePlayerName, remotePlayerId);
    }

    public synchronized void connectToPeer(String peerAddress, String remotePlayerName, int remotePlayerId) {
        sendMessage("ConnectToPeer", peerAddress, remotePlayerName, remotePlayerId);
    }

    public synchronized void disconnectFromPeer(int remotePlayerId) {
        sendMessage("DisconnectFromPeer", remotePlayerId);
    }

    public synchronized void sendGameOption(String key, String value) {
        sendMessage("GameOption", key, value);
    }

    public synchronized void sendMessage(String command, Object... args) {
        if (!connected.get() || out == null) {
            Logger.info("Pioneer adapter not connected yet, queueing GPGNet message: command=" + command + ", args=" + List.of(args));
            pendingMessages.add(() -> sendMessage(command, args));
            return;
        }

        try {
            Logger.info("Sending GPGNet message to Pioneer: command=" + command + ", args=" + List.of(args));
            out.writeMessage(command, args);
            out.flush();
        } catch (IOException e) {
            Logger.error("Failed to write GPGNet message '" + command + "' to Pioneer adapter", e);
        }
    }
}
