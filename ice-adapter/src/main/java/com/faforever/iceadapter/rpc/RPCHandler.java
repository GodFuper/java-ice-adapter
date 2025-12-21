package com.faforever.iceadapter.rpc;

import com.faforever.iceadapter.FafRpcCallbacks;
import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.IceStatus;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.gpgnet.LobbyInitMode;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Handles calls from JsonRPC (the client)
 */
@Slf4j
@RequiredArgsConstructor
public class RPCHandler {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Lock lockStatus = new ReentrantLock();
    private final int rpcPort;
    private final FafRpcCallbacks callbacks;
    private final GPGNetServer gpgNetServer;

    public void hostGame(String mapName) {
        callbacks.onHostGame(mapName);
    }

    public void joinGame(String remotePlayerLogin, long remotePlayerId) {
        callbacks.onJoinGame(remotePlayerLogin, (int) remotePlayerId);
    }

    public void connectToPeer(String remotePlayerLogin, long remotePlayerId, boolean offer) {
        callbacks.onConnectToPeer(remotePlayerLogin, (int) remotePlayerId, offer);
    }

    public void disconnectFromPeer(long remotePlayerId) {
        callbacks.onDisconnectFromPeer((int) remotePlayerId);
    }

    public void setLobbyInitMode(String lobbyInitMode) {
        gpgNetServer.setLobbyInitMode(LobbyInitMode.getByName(lobbyInitMode));
        log.debug("LobbyInitMode set to {}", lobbyInitMode);
    }

    public void iceMsg(long remotePlayerId, Object msg) {
        log.info("IceMsg received {}", msg);
        boolean err = true;
        CandidatesMessage message;
        try {
            message = objectMapper.readValue((String) msg, CandidatesMessage.class);
        } catch (Exception e) {
            log.error("Failed to parse iceMsg {}", msg, e);
            return;
        }
        int idFrom = message.srcId();
        int idTo = message.destId();
        int myId = IceAdapter.getId();
        if (myId != idTo) {
            log.error("The iceMsg {} is not meant for {}. IceMsg ignored", message, idTo);
            return;
        }

        if (remotePlayerId != idFrom) {
            log.error("The sender {} != {} does not match the IceMsg source. IceMsg ignored", remotePlayerId, idFrom);
            return;
        }

        GameSession gameSession = IceAdapter.getGameSessionSafe();
        if (gameSession == null) {
            log.error("The gameSession is null. IceMsg ignored. {}", message);
            return;
        }

        Peer peer = gameSession.getPeers().get((int) remotePlayerId);
        if (peer == null) {
            log.error("Peer not found for id: {}. IceMsg ignored. {}", remotePlayerId, message);
            return;
        }
        gameSession.onIceMessageFromRPC(peer, message);
    }

    public void sendToGpgNet(String header, Object... args) {
        callbacks.sendToGpgNet(header, args);
    }

    public void setIceServers(List<Map<String, Object>> iceServers) {
        GameSession gameSession = IceAdapter.getGameSessionSafe();
        if (gameSession == null) {
            return;
        }
        gameSession.setIceServers(iceServers);
    }

    @Deprecated(forRemoval = true)
    @SneakyThrows
    public String status() {
        IceStatus.IceGPGNetState gpgpnet = new IceStatus.IceGPGNetState(
                gpgNetServer.getStaticGpgNetPort(), gpgNetServer.isConnected(), gpgNetServer.getGameState().orElse(GameState.NONE).getName(), "-");

        List<IceStatus.IceRelay> relays = new ArrayList<>();
        GameSession gameSession = IceAdapter.getGameSessionSafe();
        if (gameSession != null) {
            lockStatus.lock();
            try {
                gameSession.getPeers().values().stream()
                        .map(peer -> {
                            Optional<CandidatePair> pair = peer.getActiveCandidatePair();
                            IceStatus.IceRelay.IceRelayICEState iceRelayICEState =
                                    new IceStatus.IceRelay.IceRelayICEState(
                                            peer.isLocalOffer(),
                                            peer.getIceState().getMessage(),
                                            "",
                                            "",
                                            peer.isConnected(),
                                            pair.map(CandidatePair::getLocalCandidate)
                                                    .map(Candidate::getHostAddress)
                                                    .map(TransportAddress::toString)
                                                    .orElse(""),
                                            pair.map(CandidatePair::getRemoteCandidate)
                                                    .map(Candidate::getHostAddress)
                                                    .map(TransportAddress::toString)
                                                    .orElse(""),
                                            pair.map(CandidatePair::getLocalCandidate)
                                                    .map(Candidate::getType)
                                                    .map(CandidateType::toString)
                                                    .orElse(""),
                                            pair.map(CandidatePair::getRemoteCandidate)
                                                    .map(Candidate::getType)
                                                    .map(CandidateType::toString)
                                                    .orElse(""),
                                            -1.0);

                            return new IceStatus.IceRelay(
                                    peer.getRemoteId(),
                                    peer.getRemoteLogin(),
                                    peer.getLocalPort(),
                                    iceRelayICEState);
                        })
                        .forEach(relays::add);
            } finally {
                lockStatus.unlock();
            }
        }

        IceStatus status = new IceStatus(
                IceAdapter.getVersion(),
                GameSession.getAllServers().size(),
                gpgNetServer.getStaticLobbyPort(),
                gpgNetServer.getLobbyInitMode().getName(),
                new IceStatus.IceOptions(
                        IceAdapter.getId(), IceAdapter.getLogin(), rpcPort, gpgNetServer.getStaticGpgNetPort()),
                gpgpnet,
                relays.toArray(new IceStatus.IceRelay[relays.size()]));

        return objectMapper.writeValueAsString(status);
    }

    public void quit() {
        log.warn("Close requested, stopping...");
        callbacks.close();
    }
}
