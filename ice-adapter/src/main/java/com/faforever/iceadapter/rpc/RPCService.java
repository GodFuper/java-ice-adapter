package com.faforever.iceadapter.rpc;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.FafRpcCallbacks;
import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.debug.InfoWindow;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nbarraille.jjsonrpc.JJsonPeer;
import com.nbarraille.jjsonrpc.TcpServer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles communication between client and adapter, opens a server for the client to connect to
 */
@Slf4j
public class RPCService {

    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static TcpServer tcpServer;
    private static volatile boolean skipRPCMessages = false;

    public static void init(int port, FafRpcCallbacks callbacks) {
        Debug.RPC_PORT = port;
        log.info("Creating RPC server on port {}", port);

        RPCHandler rpcHandler = new RPCHandler(port, callbacks);
        tcpServer = new TcpServer(port, rpcHandler);
        tcpServer.start();

        debug().rpcStarted(tcpServer.getFirstPeer());
        tcpServer.getFirstPeer().thenAccept(firstPeer -> {
            firstPeer.onConnectionLost(() -> {
                GameState gameState = GPGNetServer.getGameState().orElse(null);
                if (gameState == GameState.LAUNCHING) {
                    skipRPCMessages = true;
                    log.warn("Lost connection to first RPC Peer. GameState: LAUNCHING, NOT STOPPING!");
                    if (InfoWindow.INSTANCE == null) {
                        Debug.ENABLE_INFO_WINDOW = true;
                        Debug.init();
                    }
                    InfoWindow.INSTANCE.show();
                } else {
                    log.info(
                            "Lost connection to first RPC Peer. GameState: {}, Stopping adapter...",
                            gameState.getName());
                    callbacks.close();
                }
            });
        });
    }

    public static void onConnectionStateChanged(String newState) {
        if (!skipRPCMessages) {
            getPeerOrWait().sendNotification("onConnectionStateChanged", Arrays.asList(newState));
        }
    }

    public static void onGpgNetMessageReceived(String header, List<Object> chunks) {
        if (!skipRPCMessages) {
            getPeerOrWait().sendNotification("onGpgNetMessageReceived", Arrays.asList(header, chunks));
        }
    }

    public static void onIceMsg(CandidatesMessage candidatesMessage) {
        if (!skipRPCMessages) {
            try {
                getPeerOrWait()
                        .sendNotification(
                                "onIceMsg",
                                Arrays.asList(
                                        candidatesMessage.srcId(),
                                        candidatesMessage.destId(),
                                        objectMapper.writeValueAsString(candidatesMessage)));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void onIceConnectionStateChanged(long localPlayerId, long remotePlayerId, String state) {
        if (!skipRPCMessages) {
            getPeerOrWait()
                    .sendNotification(
                            "onIceConnectionStateChanged", Arrays.asList(localPlayerId, remotePlayerId, state));
        }
    }

    public static void onConnected(long localPlayerId, long remotePlayerId, boolean connected) {
        if (!skipRPCMessages) {
            getPeerOrWait().sendNotification("onConnected", Arrays.asList(localPlayerId, remotePlayerId, connected));
        }
    }

    /**
     * Blocks until a peer is connected (the client)
     *
     * @return the currently connected peer (the client)
     */
    public static JJsonPeer getPeerOrWait() {
        try {
            return tcpServer.getFirstPeer().get();
        } catch (Exception e) {
            log.error("Error on fetching first peer", e);
        }
        return null;
    }

    public static CompletableFuture<JJsonPeer> getPeerFuture() {
        return tcpServer.getFirstPeer();
    }

    public static void close() {
        tcpServer.stop();
    }
}
