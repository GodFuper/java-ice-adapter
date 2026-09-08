package com.faforever.iceadapter.ice.peer;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.ice.peer.modules.EventBusModule;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.KcpStatistics;
import com.faforever.iceadapter.ice.peer.modules.other.AutoSettingAllowCandidates;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.CollectionUtils;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import com.faforever.iceadapter.webrtc.WebRtcSignalingService;
import kotlin.Pair;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.*;

import java.net.DatagramSocket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Represents a peer in the current game session which we are connected to
 */
@Data
@Slf4j
@RequiredArgsConstructor
public abstract class Peer {
    private final int remoteId;
    private final String remoteLogin;
    private final boolean localOffer; // Do we offer or are we waiting for a remote offer
    private final int preferredPort;
    private final int lobbyPort;
    private final Set<PeerModule> disabledModules;
    private IceGameSession gameSession;

    private String peerIdentifier;

    private volatile long lastLostConnect = 0;

    private List<Integer> bestRelays = List.of();

    private final Map<Integer, RelayPing> rtts = new ConcurrentHashMap<>();
    private volatile boolean additionalPacketForwarding = false;

    private volatile float rtt = 0.0f;
    private volatile Long lastEcho;
    private volatile Long lastPacketReceived;
    private volatile Long relayLastPacketReceived;
    private AtomicInteger echosReceived = new AtomicInteger(0);
    private AtomicInteger invalidPacket = new AtomicInteger(0);

    private volatile boolean closing = false;

    private volatile DatagramSocket faSocket;

    private volatile boolean autoRelay = false;
    private volatile boolean connected = false;
    private volatile KeepAliveStrategy keepAliveStrategy;
    private volatile Agent agent;
    private volatile IceMediaStream mediaStream;
    private volatile Component component;

    // WebRTC fields (used when --transport=webrtc)
    private volatile WebRtcSession webRtcSession;
    private volatile WebRtcSignalingService webRtcSignalingService;
    
    private IceAgentStrategy agentStrategy = IceAgentStrategy.FIRST;
    private AllowCombination combination = AllowCombination.ALL;
    private boolean disableConnectService = false;
    private PeerSendMode sendMode = PeerSendMode.DIRECT_ONLY;

    private final AtomicInteger awaitingCandidatesEventId = new AtomicInteger(0);
    private final AtomicInteger reInitEventId = new AtomicInteger(0);
    private volatile IceState iceState = null;

    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private final Map<PeerModule, ModuleBase> modules = new ConcurrentHashMap<>();

    private final KcpStatistics kcpStatistics = new KcpStatistics();

    private int version = 1;

    public abstract int getFromId();

    public Optional<Integer> getRelayPeerId() {
        return Optional.empty();
    }

    public boolean isAllowRelay() {
        return false;
    }

    public Integer getLocalPort() {
        return faSocket != null ? faSocket.getLocalPort() : 0;
    }

    public void init() {
        peerIdentifier = "%s(%d)".formatted(remoteLogin, remoteId);
    }

    public void initModules() {
        PeerModule.getSortedModules().stream()
                .filter(Predicate.not(disabledModules::contains))
                .forEach((module) -> {
                    modules.putIfAbsent(module, module.createModule(this));
                });
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
        return iceState == IceState.CONNECTED && component != null || connected;
    }

    public void startInitPeer() {
        log.debug(
                "Peer created: {}, localOffer: {}, preferredPort: {}", getPeerIdentifier(), localOffer, preferredPort);

        setIceState(IceState.NEW);
    }

    public void setSendMode(PeerSendMode sendMode) {
        PeerSendMode oldMode = this.sendMode;
        if (Objects.equals(oldMode, sendMode)) {
            return;
        }
        this.sendMode = sendMode;
        event(bus -> bus.onPeerSendModeChange(this, oldMode, sendMode));
    }

    public boolean isKcpTransportEnabled() {
        return sendMode == PeerSendMode.BOTH || sendMode == PeerSendMode.KCP_ONLY;
    }

    public boolean isDirectTransportEnabled() {
        return sendMode == PeerSendMode.DIRECT_ONLY || sendMode == PeerSendMode.BOTH;
    }

    public void setIceState(IceState iceState) {
        IceState old = this.iceState;
        if (Objects.equals(old, iceState)) {
            return;
        }
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

    public WebRtcSession getWebRtcSession() {
        return webRtcSession;
    }

    public void setWebRtcSession(WebRtcSession webRtcSession) {
        this.webRtcSession = webRtcSession;
    }

    public WebRtcSignalingService getWebRtcSignalingService() {
        return webRtcSignalingService;
    }

    public void setWebRtcSignalingService(WebRtcSignalingService webRtcSignalingService) {
        this.webRtcSignalingService = webRtcSignalingService;
    }

    public void setRelayPeer(Peer relay) {
        event(bus -> bus.onRelayPeerChange(this, relay));
    }

    public void setCombination(AllowCombination combination) {
        setCombination(combination, false);
    }

    public void setCombination(AllowCombination combination, boolean disableAutomatic) {
        this.combination = combination;
        event(bus -> bus.onCombinationChange(this, combination));

        if (disableAutomatic) {
            getModule(PeerModule.AUTO_SETTING_ALLOW_CANDIDATE, AutoSettingAllowCandidates.class)
                    .ifPresent(ModuleBase::disable);
        }
    }

    public void addServerPeer(ServerPeer serverPeer) {
        event(bus -> bus.onAddServerPeer(this, serverPeer));
    }

    public void sendToRpc(CandidatesMessage message) {
        event(bus -> bus.onSendToRpc(this, message));
    }

    public void iceMessageFromRPC(CandidatesMessage message) {
        event(bus -> bus.onIceMessageFromRPC(this, message));
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        event(bus -> bus.onConnectingChange(this, connected));
    }

    public CandidatePair getSelectedPair() {
        return getActiveComponent().map(Component::getSelectedPair).orElse(null);
    }

    public String getFullInfoSelectedPair() {
        if (webRtcSession != null) {
            WebRtcSession.SessionStats s = webRtcSession.getStats();
            return "WebRTC DataChannel: %s\nLocal: %s (%s)\nRemote: %s (%s)\nRTT: %.1f ms\nBytes: %d sent / %d recv\nMessages: %d sent / %d recv"
                    .formatted(s.getDataChannelState(), s.getLocalAddress(), s.getLocalCandidateType(),
                            s.getRemoteAddress(), s.getRemoteCandidateType(),
                            s.getRttMs(), s.getBytesSent(), s.getBytesReceived(),
                            s.getMessagesSent(), s.getMessagesReceived());
        }
        return CandidateUtil.infoCandidate(getSelectedPair());
    }

    public float getRtt() {
        if (webRtcSession != null) {
            return webRtcSession.getStats().getRttMs();
        }
        return rtt;
    }

    public Optional<Component> getActiveComponent() {
        return Optional.ofNullable(component);
    }

    public Optional<CandidatePair> getActiveCandidatePair() {
        return Optional.ofNullable(component).map(Component::getSelectedPair);
    }

    public Collection<CandidatePair> getCandidatePairs() {
        Collection<CandidatePair> pairs = new ArrayList<>();
        Optional.ofNullable(getSelectedPair()).ifPresent(pairs::add);
        return pairs;
    }

    public List<Pair<String, String>> getCandidateTypes() {
        if (webRtcSession != null) {
            WebRtcSession.SessionStats s = webRtcSession.getStats();
            return List.of(new Pair<>(s.getLocalCandidateType(), s.getRemoteCandidateType()));
        }
        List<Pair<String, String>> candidates = new ArrayList<>();
        for (CandidatePair pair : getCandidatePairs()) {
            candidates.add(new Pair<>(
                    String.valueOf(pair.getLocalCandidate().getType()),
                    String.valueOf(pair.getRemoteCandidate().getType())));
        }

        return candidates;
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
        return Optional.ofNullable(agent).map(Agent::getState);
    }

    public Optional<Float> getAverageRtt() {
        return Optional.of(getRtt());
    }

    public Optional<Long> getLastReceived() {
        return Optional.ofNullable(getLastPacketReceived());
    }

    public Optional<Long> getRelayLastReceived() {
        return Optional.ofNullable(getRelayLastPacketReceived());
    }

    public Integer countEchosReceived() {
        return echosReceived.get();
    }

    public Integer countInvalidEchosReceived() {
        return invalidPacket.get();
    }

    public void handleData(byte[] data) {
        event(bus -> bus.onHandleData(this, data));
    }

    public void handleCommand(CommandBase command) {
        event(bus -> bus.onHandleCommand(this, command));
    }

    public void sendToPeer(byte[] data) {
        event(bus -> bus.onSendToPeer(this, data));
    }

    public void sendCommand(CommandBase command) {
        sendCommand(command, false);
    }

    public void sendCommand(CommandBase command, boolean force) {
        event(bus -> bus.onSendCommand(this, command, force));
    }

    public void lostConnect(boolean clearIceState) {
        event(bus -> bus.onConnectionLost(this, clearIceState));
    }

    public void lostConnect() {
        lostConnect(false);
    }

    public void reconnect() {
        lostConnect();
    }

    public void setLastEcho(long echo) {
        Long lastEcho = this.lastEcho;
        this.lastEcho = echo;
        event(bus -> bus.onChangeEcho(this, lastEcho, echo));
    }

    public boolean isSupportCommand() {
        return version >= 2;
    }

    public boolean isSupportRelay() {
        return version >= 2 && isAllowRelay();
    }

    public boolean isCanSelectForRelayPeerById(int id) {
        return id != remoteId && isSupportRelay() && getRelayPeerId().isEmpty();
    }

    public boolean existBestRelays() {
        return !CollectionUtils.isEmpty(getBestRelays());
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

        if (webRtcSession != null) {
            try {
                webRtcSession.close();
            } catch (Exception e) {
                log.warn("Error closing webRtcSession for peer {}", getPeerIdentifier(), e);
            }
            webRtcSession = null;
        }

        log.info("Peer closed: {}", getPeerIdentifier());
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Peer peer)) return false;
        return getRemoteId() == peer.getRemoteId() && getFromId() == peer.getFromId();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getRemoteId(), getFromId());
    }
}
