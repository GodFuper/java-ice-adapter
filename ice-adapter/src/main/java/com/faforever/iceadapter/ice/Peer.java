package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.modules.FAModule;
import com.faforever.iceadapter.ice.modules.IceModule;
import com.faforever.iceadapter.services.IceTrigger;
import com.faforever.iceadapter.util.IceUtils;
import kotlin.Pair;
import kotlin.Triple;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.*;

import java.net.DatagramSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Represents a peer in the current game session which we are connected to
 */
@Data
@Slf4j
@RequiredArgsConstructor
public class Peer {
    private final IceGameSession iceSession;
    private final IceTrigger iceTrigger;

    private final int remoteId;
    private final String remoteLogin;
    private final boolean localOffer; // Do we offer or are we waiting for a remote offer
    private final int preferredPort;
    private boolean allowHost = true;
    private boolean allowReflexive = true;
    private boolean allowRelay = true;

    private volatile float rtt = 0.0f;
    private volatile Long lastPacketReceived;

    public volatile boolean closing = false;

    private volatile Agent agent;
    private volatile IceMediaStream mediaStream;

    private final AtomicInteger awaitingCandidatesEventId = new AtomicInteger(0);
    private volatile IceState iceState = IceState.NEW;
    private volatile boolean connected = false;

    //    private final PeerIceModule ice = new PeerIceModule(this);
//    private DatagramSocket faSocket; // Socket on which we are listening for FA / sending data to FA
    private final Lock lockSocketSend = new ReentrantLock();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private final Map<IceModule, ModuleBase> modules = new ConcurrentHashMap<>();

    // Future handle for the FA listener task so we can cancel it cleanly
    private volatile CompletableFuture<Void> faListenerFuture;

    public void initModules(IceGameSession iceGameSession, IcePeerAdapter icePeerAdapter) {
        for (IceModule module : IceModule.values()) {
            modules.putIfAbsent(module, module.getCreateModule()
                    .apply(new Triple<>(this, iceGameSession, icePeerAdapter)));
        }
    }

    public void startModules() {
        for (ModuleBase module : modules.values()) {
            module.start();
        }
    }

    public void init() {
        log.debug(
                "Peer created: {}, localOffer: {}, preferredPort: {}", getPeerIdentifier(), localOffer, preferredPort);

//        faSocket = initForwarding(preferredPort);
//

        // Start FA listener and keep a handle so we can cancel it during shutdown
//        faListenerFuture = CompletableFuture.runAsync(this::faListener, IceAdapter.getExecutor());

        setIceState(IceState.NEW);
    }

    public void setIceState(IceState iceState) {
        IceState old = this.iceState;
        this.iceState = iceState;
        iceTrigger.onChangeIceState(this, old, iceState);
        debug().peerStateChanged(this);
    }

    public void setAllows(boolean allowHost, boolean allowReflexive, boolean allowRelay) {
        this.allowHost = allowHost;
        this.allowReflexive = allowReflexive;
        this.allowRelay = allowRelay;
    }

    public Lock getLock(String lockName) {
        return locks.computeIfAbsent(lockName, k -> new ReentrantLock());
    }

    public <T extends ModuleBase> Optional<T> getModule(IceModule module, Class<T> type) {
        ModuleBase foundModule = modules.get(module);
        if (type != null && type.isInstance(foundModule)) {
            return Optional.of(type.cast(foundModule));
        } else {
            return Optional.empty();
        }
    }

    public void reconnect() {
//        ice.reconnect();
    }

    public int getLocalPort() {
        DatagramSocket socket = getModule(IceModule.FA_SOCKET_MODULE, FAModule.class).map(FAModule::getSocket).orElse(null);
        return socket != null ? socket.getLocalPort() : 0;
    }

    /**
     * Starts waiting for data from FA
     */
//    @SneakyThrows(SocketException.class)
//    private DatagramSocket initForwarding(int port) {
//        try {
//            DatagramSocket socket = new DatagramSocket(port);
//            DatagramSocketUtils.resizeBuffer(socket);
//            log.debug("Now forwarding data to peer {}", getPeerIdentifier());
//            return socket;
//        } catch (SocketException e) {
//            log.error("Could not create socket for peer: {}", getPeerIdentifier(), e);
//            throw e;
//        }
//    }

    /**
     * This method get's invoked by the thread listening for data from FA
     */
//    private void faListener() {
//        byte[] data = new byte[MAX_SIZE_PACKET];
//        while (!closing) {
//            try {
//                DatagramPacket packet = new DatagramPacket(data, data.length);
//                faSocket.receive(packet);
//
//                // Defensive copy of payload to avoid races with the receive buffer
//                byte[] copy = new byte[packet.getLength()];
//                System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, packet.getLength());
//
//                // Forward to ICE - this method will drop packets if ICE isn't ready
//                ice.onFaDataReceived(copy);
//            } catch (SocketException se) {
//                // socket closed or network error
//                if (closing) {
//                    log.debug("FA listener shutting down for peer {}: {}", getPeerIdentifier(), se.toString());
//                } else {
//                    log.warn("SocketException in FA listener for peer {}: {}", getPeerIdentifier(), se.toString());
//                    // Try to trigger ICE reconnect safely
//                    try {
//                        ice.onConnectionLost();
//                    } catch (Exception ex) {
//                        log.debug("Error while requesting ICE reconnect after socket exception", ex);
//                    }
//                }
//                break;
//            } catch (IOException e) {
//                if (closing) {
//                    log.debug(
//                            "Ignoring error while receiving packet because the connection was closed as peer {}",
//                            getPeerIdentifier());
//                } else {
//                    log.debug(
//                            "Error while reading from local FA as peer (probably disconnecting from peer) {}",
//                            getPeerIdentifier(),
//                            e);
//                    try {
//                        ice.onConnectionLost();
//                    } catch (Exception ex) {
//                        log.debug("Error while requesting ICE reconnect after IO error", ex);
//                    }
//                }
//                break;
//            }
//        }
//        log.debug("No longer listening for messages from FA for peer {}", getPeerIdentifier());
//    }

    /**
     * @return %username%(%id%)
     */
    public String getPeerIdentifier() {
        return "%s(%d)".formatted(remoteLogin, remoteId);
    }

    public List<Pair<CandidateType, CandidateType>> getCandidateTypes() {
        return Optional.ofNullable(mediaStream)
                .map(IceUtils::getActiveComponents)
                .orElse(List.of())
                .stream()
                .map(Component::getSelectedPair)
                .map(pair -> new Pair<>(pair.getLocalCandidate().getType(), pair.getRemoteCandidate().getType()))
                .toList();
    }

    public String getStrCandidateTypes(String delimiter) {
        StringJoiner pairCandidates = new StringJoiner(delimiter);
        for (Pair<CandidateType, CandidateType> pair : getCandidateTypes()) {
            pairCandidates.add("%s<->%s".formatted(pair.getFirst(), pair.getSecond()));
        }
        return pairCandidates.toString();
    }

    public IceState getState() {
        return iceState;
    }

    public Optional<IceProcessingState> getAgentState() {
        return Optional.ofNullable(agent)
                .map(Agent::getState);
    }

    public Optional<Float> getAverageRtt() {
        return Optional.of(getRtt());
    }

    public Optional<Long> getLastReceived() {
        return Optional.ofNullable(getLastPacketReceived());
    }

    public Optional<Long> countEchosReceived() {
        return getModule(IceModule.CONNECTION_CHECKER_MODULE, ConnectivityModule.class)
                .map(ConnectivityModule::getEchosReceived);
    }

    public Optional<Long> countInvalidEchosReceived() {
        return getModule(IceModule.CONNECTION_CHECKER_MODULE, ConnectivityModule.class)
                .map(ConnectivityModule::getInvalidEchosReceived);
    }

    public void close() {
        if (closing) {
            return;
        }

        log.info("Closing peer for player {}", getPeerIdentifier());

        closing = true;

        for (ModuleBase module : modules.values()) {
            module.stop();
        }

//        if (faListenerFuture != null) {
//            faListenerFuture.cancel(true);
//        }
//
//        try {
//            if (faSocket != null && !faSocket.isClosed()) {
//                faSocket.close();
//            }
//        } catch (Exception e) {
//            log.debug("Error closing faSocket for {}", getPeerIdentifier(), e);
//        }

        log.info("Peer closed: {}", getPeerIdentifier());
    }
}

