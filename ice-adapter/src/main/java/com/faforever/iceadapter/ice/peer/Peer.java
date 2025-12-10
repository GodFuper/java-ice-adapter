package com.faforever.iceadapter.ice.peer;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerEventListener;
import com.faforever.iceadapter.ice.peer.modules.EventBusModule;
import com.faforever.iceadapter.ice.peer.modules.PeerConnectivityCheckerModule;
import com.faforever.iceadapter.ice.peer.modules.UseCustomPairModule;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import kotlin.Pair;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.*;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Represents a peer in the current game session which we are connected to
 */
@Data
@Slf4j
@RequiredArgsConstructor
public class Peer {
    private final int remoteId;
    private final String remoteLogin;
    private final boolean localOffer; // Do we offer or are we waiting for a remote offer
    private final int preferredPort;
    private final int lobbyPort;
    private Integer localPort;
    private boolean allowHost = true;
    private boolean allowReflexive = true;
    private boolean allowRelay = true;
    private volatile long lastLostConnect = 0;

    private volatile float rtt = 0.0f;
    private volatile Long lastPacketReceived;
    private AtomicInteger echosReceived = new AtomicInteger(0);
    private AtomicInteger invalidEchosReceived = new AtomicInteger(0);

    public volatile boolean closing = false;

    private DatagramSocket faSocket;

    private volatile KeepAliveStrategy keepAliveStrategy;
    private volatile Agent agent;
    private volatile IceMediaStream mediaStream;
    private volatile Component component;

    private final AtomicInteger awaitingCandidatesEventId = new AtomicInteger(0);
    private volatile IceState iceState = null;

    //    private final PeerIceModule ice = new PeerIceModule(this);
//    private DatagramSocket faSocket; // Socket on which we are listening for FA / sending data to FA
    private final Lock lockSocketSend = new ReentrantLock();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private final Map<PeerModule, ModuleBase> modules = new ConcurrentHashMap<>();

    // Future handle for the FA listener task so we can cancel it cleanly
    private volatile CompletableFuture<Void> faListenerFuture;

    public void initModules(IceAsync iceAsync) {
        for (PeerModule module : PeerModule.getSortedModules()) {
            modules.putIfAbsent(module, module.createModule(new Pair<>(this, iceAsync)));
        }
    }

    public void startModules() {
        for (ModuleBase module : modules.values()) {
            module.start();
        }
    }

    public void stopModules() {
        for (ModuleBase module : modules.values()) {
            module.stop();
        }
    }

    public boolean isConnected() {
        return component != null;
    }

    public void startInitPeer() {
        log.debug("Peer created: {}, localOffer: {}, preferredPort: {}",
                getPeerIdentifier(),
                localOffer,
                preferredPort);

        setIceState(IceState.NEW);
    }

    public void setIceState(IceState iceState) {
        IceState old = this.iceState;
        this.iceState = iceState;
        event(bus -> bus.onIceStateChange(this, old, iceState));
        debug().peerStateChanged(this);
    }

    public void setLastPacketReceived(Long lastPacketReceived) {
        Long old = this.lastPacketReceived;
        this.lastPacketReceived = lastPacketReceived;
        event(bus -> bus.onLastPacketReceived(this, old, lastPacketReceived));
    }

    public void setIceStateWithoutTrigger(IceState iceState) {
        this.iceState = iceState;
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

    public <T extends ModuleBase> Optional<T> getModule(PeerModule module, Class<T> type) {
        ModuleBase foundModule = modules.get(module);
        if (type != null && type.isInstance(foundModule)) {
            return Optional.of(type.cast(foundModule));
        } else {
            log.warn("Could not find module {} - {}", module, type);
            return Optional.empty();
        }
    }

    private void event(Consumer<EventBusModule> consumer) {
        getEventBus().ifPresent(consumer);
    }

    private Optional<EventBusModule> getEventBus() {
        return getModule(PeerModule.EVENT_BUS, EventBusModule.class);
    }

    public void addEventListener(PeerEventListener listener) {
        getEventBus().ifPresent(bus -> bus.register(listener));
    }

    public void removeEventListener(PeerEventListener listener) {
        getEventBus().ifPresent(bus -> bus.unregister(listener));
    }

    /**
     * Starts waiting for data from FA
     */
    @SneakyThrows(SocketException.class)
    private DatagramSocket initForwarding(int port) {
        try {
            DatagramSocket socket = new DatagramSocket(port);
            DatagramSocketUtils.resizeBuffer(socket);
            log.debug("Now forwarding data to peer {}", getPeerIdentifier());
            return socket;
        } catch (SocketException e) {
            log.error("Could not create socket for peer: {}", getPeerIdentifier(), e);
            throw e;
        }
    }

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
    public void setAgent(Agent agent) {
        this.agent = agent;
        event(bus -> bus.onAgentChange(this, agent));
    }

    public void setMediaStream(IceMediaStream mediaStream) {
        this.mediaStream = mediaStream;
        event(bus -> bus.onIceMediaStreamChange(this, mediaStream));
    }

    public void setComponent(Component component) {
        this.component = component;
        event(bus -> bus.onIceComponentChange(this, component));
    }

    /**
     * @return %username%(%id%)
     */
    public String getPeerIdentifier() {
        return "%s(%d)".formatted(remoteLogin, remoteId);
    }

    public CandidatePair getSelectedPair() {
        Optional<UseCustomPairModule> module = getModule(PeerModule.MULTI_PAIRS, UseCustomPairModule.class);

        if (module.isPresent() && module.get().isRunning()) {
            return module.get().getSelectedPair();
        }

        return getActiveComponent()
                .map(Component::getSelectedPair)
                .orElse(null);
    }

    public Optional<Component> getActiveComponent() {
        return Optional.ofNullable(component);
    }

    public Optional<CandidatePair> getActiveCandidatePair() {
        return Optional.ofNullable(component).map(Component::getSelectedPair);
    }

    public Collection<CandidatePair> getCandidatePairs() {
        Collection<CandidatePair> pairs = new ArrayList<>();
        Optional<UseCustomPairModule> module = getModule(PeerModule.MULTI_PAIRS, UseCustomPairModule.class);
        if (module.isPresent() && module.get().isRunning()) {
            pairs = module.get().getSuccessPairs();
        } else {
            Optional.ofNullable(getSelectedPair()).ifPresent(pairs::add);
        }
        return pairs;
    }

    public List<Pair<String, String>> getCandidateTypes() {
        return getCandidatePairs().stream()
                .map(pair -> new Pair<>("%s-%s".formatted(pair.getLocalCandidate().getTransport(),
                        pair.getLocalCandidate().getType()),
                        "%s-%s".formatted(pair.getRemoteCandidate().getType(), pair.getRemoteCandidate().getTransport())))
                .toList();
    }

    public String getStrCandidateTypes(String delimiter) {
        StringJoiner pairCandidates = new StringJoiner(delimiter);
        for (Pair<String, String> pair : getCandidateTypes()) {
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
        return getModule(PeerModule.CONNECTION_CHECKER_MODULE, PeerConnectivityCheckerModule.class)
                .map(PeerConnectivityCheckerModule::getEchosReceived);
    }

    public Optional<Long> countInvalidEchosReceived() {
        return getModule(PeerModule.CONNECTION_CHECKER_MODULE, PeerConnectivityCheckerModule.class)
                .map(PeerConnectivityCheckerModule::getInvalidEchosReceived);
    }

    public void sendToFaSocket(byte[] data, int offset, int length) {
        event(bus -> bus.onSendToFaSocket(this, data, offset, length));
    }

    public void iceDataReceived(byte[] data, int offset, int length) {
        event(bus -> bus.onIceDataReceived(this, data, offset, length));
    }

    public void sendToPeer(byte[] data, int offset, int length) {
        event(bus -> bus.onSendToPeer(this, data, offset, length));
    }

    public void lostConnect() {
        event(bus -> bus.onConnectionLost(this));
    }

    public void close() {
        if (closing) {
            return;
        }

        log.info("Closing peer for player {}", getPeerIdentifier());

        closing = true;
        event(bus -> bus.onClose(this, closing));
        for (ModuleBase module : modules.values()) {
            module.stop();
        }

//        if (faListenerFuture != null) {
//            faListenerFuture.cancel(true);
//        }
//
        log.info("Peer closed: {}", getPeerIdentifier());
    }


}

