package com.faforever.iceadapter.webrtc;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.IceServer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.ExecutorHolder;
import dev.onvoid.webrtc.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages the full WebRTC lifecycle for ONE peer connection.
 * Handles SDP offer/answer exchange, ICE candidate exchange, and data channel lifecycle.
 */
@Slf4j
@RequiredArgsConstructor
public class WebRtcSession implements PeerConnectionObserver, RTCDataChannelObserver {

    private static final long GATHER_TIMEOUT_MS = 1500;

    public static final String CHANNEL_GAME_DATA = "gameData";
    public static final String CHANNEL_CONTROL_DATA = "controlData";

    private final WebRtcConnectionFactory factory;

    @Getter
    private volatile RTCPeerConnection peerConnection;

    private volatile RTCDataChannel dataChannel;
    private volatile RTCDataChannel gameDataChannel;
    private volatile RTCDataChannel controlDataChannel;
    private final Queue<byte[]> pendingControlMessages = new ConcurrentLinkedQueue<>();

    @Getter
    private volatile boolean remoteIsJavaAdapter = false;

    @Getter
    private RTCConfiguration config;

    @Getter
    private AllowCombination allowCombination = AllowCombination.ALL;

    // Callbacks
    private volatile DataChannelMessageHandler messageHandler;
    private volatile SessionStateHandler stateHandler;

    // State
    @Getter
    private boolean isOfferer;
    /**
     * -- GETTER --
     * Check if the session is connected.
     */
    @Getter
    private volatile boolean connected = false;

    @Getter
    private volatile boolean closed = false;

    private volatile boolean initialized = false;

    // Pending ICE candidates received before remote description is set
    private final List<RTCIceCandidate> pendingCandidates = new ArrayList<>();

    // Latches for async operations
    private final CountDownLatch connectedLatch = new CountDownLatch(1);

    // Vanilla ICE candidate gathering (gathered before sending offer/answer)
    private final List<CandidatePacket> gatheredCandidatePackets = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gatheringLatch;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DataChannelStats {
        private String label = "";
        private String state = "closed";
        private long messagesSent = 0;
        private long messagesReceived = 0;
        private long bytesSent = 0;
        private long bytesReceived = 0;
    }

    @Data
    public static class SessionStats {
        private volatile float rttMs = 0.0f;
        private volatile String localCandidateType = "";
        private volatile String remoteCandidateType = "";
        private volatile String localAddress = "";
        private volatile String remoteAddress = "";
        private volatile String peerConnectionState = "-";
        private volatile String iceConnectionState = "-";
        private volatile String dtlsState = "-";
        private volatile String candidatePairState = "-";
        private volatile boolean nominated = false;
        private volatile long packetsSent = 0;
        private volatile long packetsReceived = 0;
        private volatile long packetsDiscardedOnSend = 0;
        private volatile double availableOutgoingBitrate = 0.0;
        private volatile double availableIncomingBitrate = 0.0;

        private final Map<String, DataChannelStats> dataChannels = new ConcurrentHashMap<>();

        // Fallback fields for backwards compatibility / mock convenience
        private volatile String dataChannelState = null;
        private volatile String dataChannelLabel = null;
        private volatile Long bytesSent = null;
        private volatile Long bytesReceived = null;
        private volatile Long messagesSent = null;
        private volatile Long messagesReceived = null;

        public String getDataChannelState() {
            if (dataChannelState != null && dataChannels.isEmpty()) {
                return dataChannelState;
            }
            if (dataChannels.isEmpty()) {
                return "closed";
            }
            Set<String> uniqueStates = dataChannels.values().stream()
                    .map(DataChannelStats::getState)
                    .collect(Collectors.toSet());
            if (uniqueStates.size() == 1) {
                return uniqueStates.iterator().next();
            }
            return dataChannels.values().stream()
                    .map(dc -> dc.getLabel() + ": " + dc.getState())
                    .collect(Collectors.joining(", "));
        }

        public String getDataChannelLabel() {
            if (dataChannelLabel != null && dataChannels.isEmpty()) {
                return dataChannelLabel;
            }
            if (dataChannels.isEmpty()) {
                return "-";
            }
            return String.join(", ", dataChannels.keySet());
        }

        public long getBytesSent() {
            if (bytesSent != null && dataChannels.isEmpty()) {
                return bytesSent;
            }
            return dataChannels.values().stream()
                    .mapToLong(DataChannelStats::getBytesSent)
                    .sum();
        }

        public long getBytesReceived() {
            if (bytesReceived != null && dataChannels.isEmpty()) {
                return bytesReceived;
            }
            return dataChannels.values().stream()
                    .mapToLong(DataChannelStats::getBytesReceived)
                    .sum();
        }

        public long getMessagesSent() {
            if (messagesSent != null && dataChannels.isEmpty()) {
                return messagesSent;
            }
            return dataChannels.values().stream()
                    .mapToLong(DataChannelStats::getMessagesSent)
                    .sum();
        }

        public long getMessagesReceived() {
            if (messagesReceived != null && dataChannels.isEmpty()) {
                return messagesReceived;
            }
            return dataChannels.values().stream()
                    .mapToLong(DataChannelStats::getMessagesReceived)
                    .sum();
        }

        public void setDataChannel(
                String label, String state, long msgSent, long msgRecv, long bytesSent, long bytesRecv) {
            dataChannels.put(label, new DataChannelStats(label, state, msgSent, msgRecv, bytesSent, bytesRecv));
        }

        public void setDataChannelState(String state) {
            this.dataChannelState = state;
            if (!dataChannels.isEmpty()) {
                for (DataChannelStats dc : dataChannels.values()) {
                    dc.setState(state);
                }
            }
        }

        public void setDataChannelLabel(String label) {
            this.dataChannelLabel = label;
        }

        public void setBytesSent(long bytesSent) {
            this.bytesSent = bytesSent;
        }

        public void setBytesReceived(long bytesReceived) {
            this.bytesReceived = bytesReceived;
        }

        public void setMessagesSent(long messagesSent) {
            this.messagesSent = messagesSent;
        }

        public void setMessagesReceived(long messagesReceived) {
            this.messagesReceived = messagesReceived;
        }
    }

    @Getter
    private final SessionStats stats = new SessionStats();

    private ScheduledFuture<?> statsFuture;

    /**
     * Query WebRTC statistics from libwebrtc peer connection.
     */
    public void updateStats() {
        RTCPeerConnection pc = peerConnection;
        if (pc == null || closed) {
            return;
        }
        try {
            try {
                if (pc.getConnectionState() != null) {
                    stats.setPeerConnectionState(
                            pc.getConnectionState().toString());
                }
                if (pc.getIceConnectionState() != null) {
                    stats.setIceConnectionState(
                            pc.getIceConnectionState().toString());
                }
            } catch (Exception ignored) {
            }

            if (gameDataChannel != null && gameDataChannel.getState() != null) {
                String state = gameDataChannel.getState().toString().toLowerCase();
                stats.getDataChannels().compute(CHANNEL_GAME_DATA, (k, v) -> {
                    if (v == null) {
                        return new DataChannelStats(CHANNEL_GAME_DATA, state, 0, 0, 0, 0);
                    }
                    v.setState(state);
                    return v;
                });
            }
            if (controlDataChannel != null && controlDataChannel.getState() != null) {
                String state = controlDataChannel.getState().toString().toLowerCase();
                stats.getDataChannels().compute(CHANNEL_CONTROL_DATA, (k, v) -> {
                    if (v == null) {
                        return new DataChannelStats(CHANNEL_CONTROL_DATA, state, 0, 0, 0, 0);
                    }
                    v.setState(state);
                    return v;
                });
            }

            pc.getStats(report -> {
                Map<String, RTCStats> statsMap = report.getStats();
                String selectedPairId = null;

                for (RTCStats s : statsMap.values()) {
                    if (s.getType() == RTCStatsType.TRANSPORT) {
                        Object pairId = s.getAttributes().get("selectedCandidatePairId");
                        if (pairId != null) {
                            selectedPairId = pairId.toString();
                        }
                        Object dtls = s.getAttributes().get("dtlsState");
                        if (dtls != null) {
                            stats.setDtlsState(dtls.toString());
                        }
                    }
                }

                RTCStats selectedPair = null;
                if (selectedPairId != null) {
                    selectedPair = statsMap.get(selectedPairId);
                }

                if (selectedPair == null) {
                    for (RTCStats s : statsMap.values()) {
                        if (s.getType() == RTCStatsType.CANDIDATE_PAIR) {
                            Object nominated = s.getAttributes().get("nominated");
                            Object state = s.getAttributes().get("state");
                            if (Boolean.TRUE.equals(nominated) || "succeeded".equals(state)) {
                                selectedPair = s;
                                break;
                            }
                        }
                    }
                }

                if (selectedPair != null) {
                    Map<String, Object> attrs = selectedPair.getAttributes();
                    Object rttObj = attrs.get("currentRoundTripTime");
                    if (rttObj instanceof Number num) {
                        stats.setRttMs((float) (num.doubleValue() * 1000.0));
                    }
                    Object stateObj = attrs.get("state");
                    if (stateObj != null) {
                        stats.setCandidatePairState(stateObj.toString());
                    }
                    Object nomObj = attrs.get("nominated");
                    if (nomObj instanceof Boolean b) {
                        stats.setNominated(b);
                    }
                    Object pktSent = attrs.get("packetsSent");
                    if (pktSent instanceof Number n) {
                        stats.setPacketsSent(n.longValue());
                    }
                    Object pktRecv = attrs.get("packetsReceived");
                    if (pktRecv instanceof Number n) {
                        stats.setPacketsReceived(n.longValue());
                    }
                    Object pktDisc = attrs.get("packetsDiscardedOnSend");
                    if (pktDisc instanceof Number n) {
                        stats.setPacketsDiscardedOnSend(n.longValue());
                    }
                    Object outBitrate = attrs.get("availableOutgoingBitrate");
                    if (outBitrate instanceof Number n) {
                        stats.setAvailableOutgoingBitrate(n.doubleValue());
                    }
                    Object inBitrate = attrs.get("availableIncomingBitrate");
                    if (inBitrate instanceof Number n) {
                        stats.setAvailableIncomingBitrate(n.doubleValue());
                    }

                    Object localId = attrs.get("localCandidateId");
                    if (localId != null && statsMap.containsKey(localId.toString())) {
                        Map<String, Object> localAttrs =
                                statsMap.get(localId.toString()).getAttributes();
                        stats.setLocalCandidateType(String.valueOf(localAttrs.get("candidateType")));
                        stats.setLocalAddress(localAttrs.get("address") + ":" + localAttrs.get("port"));
                    }

                    Object remoteId = attrs.get("remoteCandidateId");
                    if (remoteId != null && statsMap.containsKey(remoteId.toString())) {
                        Map<String, Object> remoteAttrs =
                                statsMap.get(remoteId.toString()).getAttributes();
                        stats.setRemoteCandidateType(String.valueOf(remoteAttrs.get("candidateType")));
                        stats.setRemoteAddress(remoteAttrs.get("address") + ":" + remoteAttrs.get("port"));
                    }
                }

                for (RTCStats s : statsMap.values()) {
                    if (s.getType() == RTCStatsType.DATA_CHANNEL) {
                        Map<String, Object> attrs = s.getAttributes();
                        String state = String.valueOf(attrs.get("state"));
                        Object labelObj = attrs.get("label");
                        String label = labelObj != null ? labelObj.toString() : "";
                        long sentBytes = attrs.get("bytesSent") instanceof Number n ? n.longValue() : 0;
                        long recvBytes = attrs.get("bytesReceived") instanceof Number n ? n.longValue() : 0;
                        long sentMsgs = attrs.get("messagesSent") instanceof Number n ? n.longValue() : 0;
                        long recvMsgs = attrs.get("messagesReceived") instanceof Number n ? n.longValue() : 0;

                        if (!label.isEmpty()) {
                            stats.getDataChannels()
                                    .put(
                                            label,
                                            new DataChannelStats(
                                                    label, state, sentMsgs, recvMsgs, sentBytes, recvBytes));
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.trace("Error querying WebRTC stats: {}", e.getMessage());
        }
    }

    /**
     * Callback for incoming data on a data channel.
     */
    @FunctionalInterface
    public interface DataChannelMessageHandler {
        void onMessage(String channelLabel, byte[] data, boolean isBinary);
    }

    /**
     * Legacy 2-argument callback for tests and backwards compatibility.
     */
    @FunctionalInterface
    public interface LegacyDataChannelMessageHandler {
        void onMessage(byte[] data, boolean isBinary);
    }

    /**
     * Callback for session state changes.
     */
    public interface SessionStateHandler {
        void onConnected();

        void onDisconnected();

        void onError(String error);

        default void onOfferCreated(String sdp) {}

        default void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
            onOfferCreated(sdp);
        }

        default void onAnswerCreated(String sdp) {}

        default void onAnswerCreated(String sdp, List<CandidatePacket> candidates) {
            onAnswerCreated(sdp);
        }

        void onRemoteDescriptionSet();

        default void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {}
    }

    /**
     * Initialize the session with configuration and callbacks.
     */
    public synchronized void init(
            boolean offerer,
            List<IceServer> iceServers,
            LegacyDataChannelMessageHandler legacyHandler,
            SessionStateHandler stateHandler) {
        init(
                offerer,
                iceServers,
                null,
                (label, data, isBinary) -> legacyHandler.onMessage(data, isBinary),
                stateHandler);
    }

    public synchronized void init(
            boolean offerer,
            List<IceServer> iceServers,
            DataChannelMessageHandler messageHandler,
            SessionStateHandler stateHandler) {
        init(offerer, iceServers, null, messageHandler, stateHandler);
    }

    /**
     * Initialize the session with configuration, options, and callbacks.
     */
    public synchronized void init(
            boolean offerer,
            List<IceServer> iceServers,
            IceOptions options,
            LegacyDataChannelMessageHandler legacyHandler,
            SessionStateHandler stateHandler) {
        init(
                offerer,
                iceServers,
                options,
                AllowCombination.ALL,
                (label, data, isBinary) -> legacyHandler.onMessage(data, isBinary),
                stateHandler);
    }

    public synchronized void init(
            boolean offerer,
            List<IceServer> iceServers,
            IceOptions options,
            DataChannelMessageHandler messageHandler,
            SessionStateHandler stateHandler) {
        init(offerer, iceServers, options, AllowCombination.ALL, messageHandler, stateHandler);
    }

    /**
     * Initialize the session with configuration, options, combination, and callbacks.
     */
    public synchronized void init(
            boolean offerer,
            List<IceServer> iceServers,
            IceOptions options,
            AllowCombination combination,
            LegacyDataChannelMessageHandler legacyHandler,
            SessionStateHandler stateHandler) {
        init(
                offerer,
                CHANNEL_GAME_DATA,
                iceServers,
                options,
                combination,
                (label, data, isBinary) -> legacyHandler.onMessage(data, isBinary),
                stateHandler);
    }

    public synchronized void init(
            boolean offerer,
            List<IceServer> iceServers,
            IceOptions options,
            AllowCombination combination,
            DataChannelMessageHandler messageHandler,
            SessionStateHandler stateHandler) {
        init(offerer, CHANNEL_GAME_DATA, iceServers, options, combination, messageHandler, stateHandler);
    }

    public synchronized void init(
            boolean offerer,
            String channelLabel,
            List<IceServer> iceServers,
            IceOptions options,
            AllowCombination combination,
            LegacyDataChannelMessageHandler legacyHandler,
            SessionStateHandler stateHandler) {
        init(
                offerer,
                channelLabel,
                iceServers,
                options,
                combination,
                (label, data, isBinary) -> legacyHandler.onMessage(data, isBinary),
                stateHandler);
    }

    /**
     * Initialize the session with configuration, options, combination, data channel label, and callbacks.
     */
    public synchronized void init(
            boolean offerer,
            String channelLabel,
            List<IceServer> iceServers,
            IceOptions options,
            AllowCombination combination,
            DataChannelMessageHandler messageHandler,
            SessionStateHandler stateHandler) {
        this.isOfferer = offerer;
        this.messageHandler = messageHandler;
        this.stateHandler = stateHandler;
        this.allowCombination = combination != null ? combination : AllowCombination.ALL;

        config = new RTCConfiguration();
        if (this.allowCombination == AllowCombination.RELAY || (options != null && options.isForceRelay())) {
            config.iceTransportPolicy = RTCIceTransportPolicy.RELAY;
        } else {
            config.iceTransportPolicy = RTCIceTransportPolicy.ALL;
        }

        if (config.portAllocatorConfig == null) {
            config.portAllocatorConfig = new PortAllocatorConfig();
        }

        if (!this.allowCombination.isAllowReflexive()) {
            config.portAllocatorConfig.setDisableStun(true);
        }
        if (!this.allowCombination.isAllowRelay()) {
            config.portAllocatorConfig.setDisableRelay(true);
        }
        if (!this.allowCombination.isAllowHost()) {
            config.portAllocatorConfig.setDisableAdapterEnumeration(true);
            config.portAllocatorConfig.setDisableDefaultLocalCandidate(true);
        }

        if (options != null && (options.getMinPort() > 0 || options.getMaxPort() > 0)) {
            if (options.getMinPort() > 0) {
                config.portAllocatorConfig.minPort = options.getMinPort();
            }
            if (options.getMaxPort() > 0) {
                config.portAllocatorConfig.maxPort = options.getMaxPort();
            }
        }

        // Add STUN/TURN servers
        if (iceServers != null) {
            for (IceServer server : iceServers) {
                if (!server.isEnabled()) {
                    continue;
                }
                config.iceServers.add(server.toWebRtcServer());
            }
        }

        peerConnection = factory.getFactory().createPeerConnection(config, this);
        if (isOfferer) {
            String label = channelLabel != null && !channelLabel.isBlank() ? channelLabel : CHANNEL_GAME_DATA;
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true;
            this.gameDataChannel = peerConnection.createDataChannel(label, init);
            this.dataChannel = this.gameDataChannel;
            this.gameDataChannel.registerObserver(this);
            stats.setDataChannelLabel(label);
            log.info("Created local data channel '{}' for offerer", label);
        }
        initialized = true;

        try {
            statsFuture = ExecutorHolder.getScheduledExecutor()
                    .scheduleWithFixedDelay(this::updateStats, 1, 1, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Could not schedule stats task", e);
        }

        log.info(
                "WebRtcSession initialized (offerer={}, iceServers={}, combination={}, forceRelay={}, minPort={}, maxPort={})",
                offerer,
                config.iceServers.size(),
                this.allowCombination,
                options != null && options.isForceRelay(),
                options != null ? options.getMinPort() : 0,
                options != null ? options.getMaxPort() : 0);
    }

    /**
     * Create an SDP offer.
     * Returns the offer via the stateHandler.onOfferCreated() callback.
     */
    public void createOffer() {
        if (!initialized || closed || peerConnection == null) {
            log.warn("Cannot create offer: session not initialized or closed");
            return;
        }

        RTCPeerConnection pc = peerConnection;
        if (pc == null || closed) {
            return;
        }

        AtomicReference<RTCSessionDescription> offerRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    offerRef.set(description);
                    latch.countDown();
                }

                @Override
                public void onFailure(String error) {
                    log.error("Failed to create offer: {}", error);
                    if (stateHandler != null) {
                        stateHandler.onError("Failed to create offer: " + error);
                    }
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            log.error("Failed to call createOffer on peer connection", e);
            return;
        }

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Offer creation timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Offer creation interrupted", e);
        }

        if (closed) {
            log.warn("Session closed during offer creation");
            return;
        }

        RTCSessionDescription offer = offerRef.get();
        if (offer == null) {
            throw new RuntimeException("Offer is null");
        }

        // Clear previously gathered candidates
        gatheredCandidatePackets.clear();
        CountDownLatch gatherLatch = new CountDownLatch(1);
        gatheringLatch = gatherLatch;

        // Set local description
        setLocalDescription(offer, () -> {
            log.info("Offer created and set as local description, gathering ICE candidates...");
        });

        // Wait for ICE candidate gathering to complete (Vanilla ICE)
        try {
            boolean completed = gatherLatch.await(GATHER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            log.info(
                    "ICE candidate gathering for offer finished (completed={}, gatheredCandidates={})",
                    completed,
                    gatheredCandidatePackets.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for ICE candidate gathering");
        }

        if (closed) {
            log.warn("Session closed during candidate gathering for offer");
            return;
        }

        RTCPeerConnection currentPc = peerConnection;
        RTCSessionDescription localDesc = currentPc != null ? currentPc.getLocalDescription() : null;
        String finalSdp = (localDesc != null && localDesc.sdp != null) ? localDesc.sdp : offer.sdp;
        List<CandidatePacket> candidatesCopy = List.copyOf(gatheredCandidatePackets);

        SessionStateHandler handler = stateHandler;
        if (handler != null && !closed) {
            handler.onOfferCreated(finalSdp, candidatesCopy);
        }
    }

    /**
     * Process a remote SDP offer received via signaling with embedded candidates.
     */
    public void processRemoteOffer(String sdp, List<CandidatePacket> candidates) {
        if (!initialized) {
            throw new IllegalStateException("Session not initialized");
        }

        RTCSessionDescription offer = new RTCSessionDescription(RTCSdpType.OFFER, sdp);
        setRemoteDescription(offer, () -> {
            log.info("Remote offer set, adding {} remote candidates", candidates != null ? candidates.size() : 0);
            if (candidates != null) {
                for (CandidatePacket cp : candidates) {
                    if (CandidatePacket.ADAPTER_FAF_ICE_ADAPTER.equals(cp.adapter())) {
                        remoteIsJavaAdapter = true;
                    }
                    String candStr = CandidateUtil.candidatePacketToWebRtcString(cp);
                    if (candStr != null) {
                        addRemoteCandidate("0", 0, candStr);
                    }
                }
            }
            // Now create answer
            createAnswer();
        });
    }

    /**
     * Process a remote SDP offer received via signaling.
     */
    public void processRemoteOffer(String sdp) {
        processRemoteOffer(sdp, List.of());
    }

    /**
     * Process a remote SDP answer received via signaling with embedded candidates.
     */
    public void processRemoteAnswer(String sdp, List<CandidatePacket> candidates) {
        if (!initialized) {
            throw new IllegalStateException("Session not initialized");
        }

        RTCSessionDescription answer = new RTCSessionDescription(RTCSdpType.ANSWER, sdp);
        setRemoteDescription(answer, () -> {
            log.info("Remote answer set, adding {} remote candidates", candidates != null ? candidates.size() : 0);
            if (candidates != null) {
                for (CandidatePacket cp : candidates) {
                    if (CandidatePacket.ADAPTER_FAF_ICE_ADAPTER.equals(cp.adapter())) {
                        remoteIsJavaAdapter = true;
                    }
                    String candStr = CandidateUtil.candidatePacketToWebRtcString(cp);
                    if (candStr != null) {
                        addRemoteCandidate("0", 0, candStr);
                    }
                }
            }
            checkAndCreateControlChannel();
            if (stateHandler != null) {
                stateHandler.onRemoteDescriptionSet();
            }
        });
    }

    /**
     * Process a remote SDP answer received via signaling.
     */
    public void processRemoteAnswer(String sdp) {
        processRemoteAnswer(sdp, List.of());
    }

    /**
     * Dynamically create controlData channel if remote peer is Java adapter and we are the offerer.
     */
    public void checkAndCreateControlChannel() {
        if (closed || peerConnection == null || !initialized) {
            return;
        }
        if (isOfferer && remoteIsJavaAdapter && controlDataChannel == null) {
            ExecutorHolder.getExecutor().submit(() -> {
                synchronized (WebRtcSession.this) {
                    RTCPeerConnection pc = peerConnection;
                    if (closed || pc == null || !initialized || controlDataChannel != null) {
                        return;
                    }
                    try {
                        RTCDataChannelInit init = new RTCDataChannelInit();
                        init.ordered = true;
                        this.controlDataChannel = pc.createDataChannel(CHANNEL_CONTROL_DATA, init);
                        this.controlDataChannel.registerObserver(new ControlChannelObserver());
                        log.info("Dynamically created controlData channel for Java peer");
                    } catch (Exception e) {
                        log.warn("Failed to create controlData channel", e);
                    }
                }
            });
        }
    }

    /**
     * Add a remote ICE candidate.
     * If remote description is not set yet, buffer candidate until remote description is set.
     */
    public synchronized void addRemoteCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        RTCIceCandidate iceCandidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);
        RTCPeerConnection pc = peerConnection;
        if (pc != null && !closed && pc.getRemoteDescription() != null) {
            try {
                pc.addIceCandidate(iceCandidate);
            } catch (Throwable t) {
                log.warn("Failed to add remote ICE candidate: {}", candidate, t);
            }
        } else {
            pendingCandidates.add(iceCandidate);
        }
    }

    /**
     * Get the game data channel (null if not yet created/received).
     */
    public Optional<RTCDataChannel> getDataChannel() {
        return Optional.ofNullable(gameDataChannel != null ? gameDataChannel : dataChannel);
    }

    /**
     * Get the control data channel (null if not yet created/received).
     */
    public Optional<RTCDataChannel> getControlDataChannel() {
        return Optional.ofNullable(controlDataChannel);
    }

    /**
     * Wait for connection to be established.
     */
    public boolean waitForConnected(long timeoutMs) throws InterruptedException {
        return connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Send game data asynchronously over the gameData channel.
     */
    public boolean sendGameDataAsync(byte[] data) {
        RTCDataChannel dc = gameDataChannel != null ? gameDataChannel : dataChannel;
        return sendDataAsync(dc, data, true, null);
    }

    /**
     * Send control data asynchronously over the controlData channel.
     * If controlData channel is still activating (CONNECTING) and remote is Java adapter,
     * buffer the packet to be sent as soon as the channel becomes OPEN.
     */
    public boolean sendControlDataAsync(byte[] data) {
        if (closed) {
            return false;
        }
        RTCDataChannel dc = controlDataChannel;
        if (dc != null && dc.getState() == RTCDataChannelState.OPEN) {
            return sendDataAsync(dc, data, true, null);
        }
        if (remoteIsJavaAdapter) {
            pendingControlMessages.offer(data);
            log.trace("Queued {} bytes of control data while controlData channel is activating", data.length);
            return true;
        }
        return false;
    }

    private void flushPendingControlMessages() {
        RTCDataChannel dc = controlDataChannel;
        if (dc == null || dc.getState() != RTCDataChannelState.OPEN) {
            return;
        }
        byte[] msg;
        int count = 0;
        while ((msg = pendingControlMessages.poll()) != null) {
            sendDataAsync(dc, msg, true, null);
            count++;
        }
        if (count > 0) {
            log.debug("Flushed {} queued control messages over controlData channel", count);
        }
    }

    /**
     * Send data over the data channel.
     */
    public boolean sendData(byte[] data, boolean isBinary) {
        return sendData(gameDataChannel != null ? gameDataChannel : dataChannel, data, isBinary);
    }

    private boolean sendData(RTCDataChannel dc, byte[] data, boolean isBinary) {
        if (closed || dc == null || dc.getState() != RTCDataChannelState.OPEN) {
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            RTCDataChannelBuffer bufferData = new RTCDataChannelBuffer(buffer, isBinary);
            dc.send(bufferData);
            return true;
        } catch (Exception e) {
            log.error("Failed to send data over data channel", e);
            return false;
        }
    }

    /**
     * Send data asynchronously without blocking calling thread on native WebRTC network thread.
     */
    public boolean sendDataAsync(byte[] data, boolean isBinary) {
        return sendDataAsync(gameDataChannel != null ? gameDataChannel : dataChannel, data, isBinary, null);
    }

    /**
     * Send data asynchronously and report the result via observer.
     */
    public boolean sendDataAsync(byte[] data, boolean isBinary, RTCDataChannelSendObserver observer) {
        return sendDataAsync(gameDataChannel != null ? gameDataChannel : dataChannel, data, isBinary, observer);
    }

    private boolean sendDataAsync(
            RTCDataChannel dc, byte[] data, boolean isBinary, RTCDataChannelSendObserver observer) {
        if (closed) {
            return false;
        }
        if (dc == null || dc.getState() != RTCDataChannelState.OPEN) {
            log.warn("Data channel not open, cannot send data async");
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            RTCDataChannelBuffer bufferData = new RTCDataChannelBuffer(buffer, isBinary);
            if (observer != null) {
                dc.sendAsync(bufferData, observer);
            } else {
                dc.sendAsync(bufferData);
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to send data async over data channel", e);
            return false;
        }
    }

    /**
     * Send text data over the data channel.
     */
    public boolean sendTextData(String text) {
        return sendData(text.getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * Close the session and dispose native resources.
     * Idempotent: safe to call multiple times.
     */
    public void close() {
        RTCDataChannel dc;
        RTCDataChannel ctrlDc;
        RTCPeerConnection pc;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stateHandler = null;
            messageHandler = null;
            dc = this.gameDataChannel != null ? this.gameDataChannel : this.dataChannel;
            this.gameDataChannel = null;
            this.dataChannel = null;
            ctrlDc = this.controlDataChannel;
            this.controlDataChannel = null;
            pc = this.peerConnection;
            this.peerConnection = null;
        }
        log.info("Closing WebRtcSession");
        if (dc != null) {
            try {
                dc.unregisterObserver();
            } catch (Throwable e) {
                log.warn("Error unregistering data channel observer", e);
            }
            try {
                dc.close();
            } catch (Throwable e) {
                log.warn("Error closing data channel", e);
            }
            try {
                dc.dispose();
            } catch (Throwable e) {
                log.warn("Error disposing data channel", e);
            }
        }
        if (ctrlDc != null) {
            try {
                ctrlDc.unregisterObserver();
            } catch (Throwable e) {
                log.warn("Error unregistering control data channel observer", e);
            }
            try {
                ctrlDc.close();
            } catch (Throwable e) {
                log.warn("Error closing control data channel", e);
            }
            try {
                ctrlDc.dispose();
            } catch (Throwable e) {
                log.warn("Error disposing control data channel", e);
            }
        }
        if (pc != null) {
            try {
                pc.close();
            } catch (Throwable e) {
                log.warn("Error closing peer connection", e);
            }
        }
        if (statsFuture != null) {
            statsFuture.cancel(false);
            statsFuture = null;
        }
        CountDownLatch gLatch = this.gatheringLatch;
        if (gLatch != null) {
            gLatch.countDown();
        }
        connectedLatch.countDown();
        pendingCandidates.clear();
        pendingControlMessages.clear();
        connected = false;
        initialized = false;
        log.info("WebRtcSession closed");
    }

    // ==================== PeerConnectionObserver ====================

    @Override
    public void onIceCandidate(RTCIceCandidate candidate) {
        if (closed) {
            return;
        }
        if (candidate != null && candidate.sdp != null) {
            log.debug("ICE candidate gathered: {}:{}:{}", candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
            CandidatePacket packet = CandidateUtil.webRtcCandidateToPacket(candidate.sdp);
            if (packet != null) {
                gatheredCandidatePackets.add(packet);
            }
            SessionStateHandler handler = stateHandler;
            if (handler != null && !closed) {
                handler.onIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
            }
        } else {
            log.info("ICE candidate gathering completed (null candidate received)");
            CountDownLatch latch = gatheringLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    @Override
    public void onIceGatheringChange(RTCIceGatheringState state) {
        if (closed) {
            return;
        }
        log.info("ICE gathering state changed: {}", state);
        if (state == RTCIceGatheringState.COMPLETE) {
            CountDownLatch latch = gatheringLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    @Override
    public void onDataChannel(RTCDataChannel dataChannel) {
        log.info("Remote data channel received: label={}", dataChannel.getLabel());
        if (CHANNEL_CONTROL_DATA.equals(dataChannel.getLabel())) {
            this.controlDataChannel = dataChannel;
            dataChannel.registerObserver(new ControlChannelObserver());
            if (dataChannel.getState() == RTCDataChannelState.OPEN) {
                flushPendingControlMessages();
            }
            return;
        }

        this.gameDataChannel = dataChannel;
        this.dataChannel = dataChannel;
        dataChannel.registerObserver(this);
        if (dataChannel.getState() == RTCDataChannelState.OPEN) {
            connected = true;
            connectedLatch.countDown();
            checkAndCreateControlChannel();
            SessionStateHandler handler = stateHandler;
            if (handler != null) {
                handler.onConnected();
            }
        }
    }

    @Override
    public void onConnectionChange(RTCPeerConnectionState state) {
        log.info("Peer connection state: {}", state);
        if (closed) {
            return;
        }
        if (state == RTCPeerConnectionState.FAILED
                || state == RTCPeerConnectionState.DISCONNECTED
                || state == RTCPeerConnectionState.CLOSED) {
            connected = false;
            SessionStateHandler handler = stateHandler;
            if (handler != null && !closed) {
                handler.onDisconnected();
            }
        }
    }

    // ==================== RTCDataChannelObserver (gameDataChannel) ====================

    @Override
    public void onBufferedAmountChange(long sentDataSize) {
        log.trace("Data channel buffered amount decreased by {} bytes", sentDataSize);
    }

    @Override
    public void onStateChange() {
        if (closed) {
            return;
        }
        RTCDataChannel dc = gameDataChannel != null ? gameDataChannel : dataChannel;
        if (dc == null) {
            return;
        }
        RTCDataChannelState state = dc.getState();
        log.info("Data channel state: {}", state);
        if (state == RTCDataChannelState.OPEN) {
            connected = true;
            connectedLatch.countDown();
            checkAndCreateControlChannel();
            SessionStateHandler handler = stateHandler;
            if (handler != null && !closed) {
                handler.onConnected();
            }
        } else if (state == RTCDataChannelState.CLOSED || state == RTCDataChannelState.CLOSING) {
            connected = false;
            SessionStateHandler handler = stateHandler;
            if (handler != null && !closed) {
                handler.onDisconnected();
            }
        }
    }

    @Override
    public void onMessage(RTCDataChannelBuffer buffer) {
        ByteBuffer data = buffer.data;
        byte[] payload = new byte[data.remaining()];
        data.get(payload);
        log.trace("Game data channel message received: {} bytes, binary={}", payload.length, buffer.binary);
        if (messageHandler != null) {
            String label = gameDataChannel != null
                    ? gameDataChannel.getLabel()
                    : (dataChannel != null ? dataChannel.getLabel() : CHANNEL_GAME_DATA);
            messageHandler.onMessage(label, payload, buffer.binary);
        }
    }

    private class ControlChannelObserver implements RTCDataChannelObserver {
        @Override
        public void onBufferedAmountChange(long sentDataSize) {
            log.trace("Control channel buffered amount decreased by {} bytes", sentDataSize);
        }

        @Override
        public void onStateChange() {
            if (closed || controlDataChannel == null) {
                return;
            }
            RTCDataChannelState state = controlDataChannel.getState();
            log.info("Control data channel state: {}", state);
            if (state == RTCDataChannelState.OPEN) {
                flushPendingControlMessages();
            }
        }

        @Override
        public void onMessage(RTCDataChannelBuffer buffer) {
            ByteBuffer data = buffer.data;
            byte[] payload = new byte[data.remaining()];
            data.get(payload);
            log.trace("Control data channel message received: {} bytes, binary={}", payload.length, buffer.binary);
            if (messageHandler != null) {
                messageHandler.onMessage(CHANNEL_CONTROL_DATA, payload, buffer.binary);
            }
        }
    }

    // ==================== Internal Helpers ====================

    private void createAnswer() {
        if (!initialized || closed || peerConnection == null) {
            log.warn("Cannot create answer: session not initialized or closed");
            return;
        }

        RTCPeerConnection pc = peerConnection;
        if (pc == null || closed) {
            return;
        }

        AtomicReference<RTCSessionDescription> answerRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    answerRef.set(description);
                    latch.countDown();
                }

                @Override
                public void onFailure(String error) {
                    log.error("Failed to create answer: {}", error);
                    if (stateHandler != null) {
                        stateHandler.onError("Failed to create answer: " + error);
                    }
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            log.error("Failed to call createAnswer on peer connection", e);
            return;
        }

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Answer creation timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Answer creation interrupted", e);
        }

        if (closed) {
            log.warn("Session closed during answer creation");
            return;
        }

        RTCSessionDescription answer = answerRef.get();
        if (answer == null) {
            throw new RuntimeException("Answer is null");
        }

        // Clear previously gathered candidates
        gatheredCandidatePackets.clear();
        CountDownLatch gatherLatch = new CountDownLatch(1);
        gatheringLatch = gatherLatch;

        setLocalDescription(answer, () -> {
            log.info("Answer created and set as local description, gathering ICE candidates...");
        });

        // Wait for ICE candidate gathering to complete (Vanilla ICE)
        try {
            boolean completed = gatherLatch.await(GATHER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            log.info(
                    "ICE candidate gathering for answer finished (completed={}, gatheredCandidates={})",
                    completed,
                    gatheredCandidatePackets.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for ICE candidate gathering");
        }

        if (closed) {
            log.warn("Session closed during candidate gathering for answer");
            return;
        }

        RTCPeerConnection currentPc = peerConnection;
        RTCSessionDescription localDesc = currentPc != null ? currentPc.getLocalDescription() : null;
        String finalSdp = (localDesc != null && localDesc.sdp != null) ? localDesc.sdp : answer.sdp;
        List<CandidatePacket> candidatesCopy = List.copyOf(gatheredCandidatePackets);

        SessionStateHandler handler = stateHandler;
        if (handler != null && !closed) {
            handler.onAnswerCreated(finalSdp, candidatesCopy);
        }
    }

    private void setLocalDescription(RTCSessionDescription description, Runnable onSuccess) {
        if (closed || peerConnection == null) {
            log.warn("Cannot setLocalDescription: session closed or peerConnection null");
            return;
        }

        RTCPeerConnection pc = peerConnection;
        if (pc == null || closed) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> errorRef = new AtomicReference<>();

        try {
            pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    latch.countDown();
                }

                @Override
                public void onFailure(String error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            log.error("Failed to call setLocalDescription on peer connection", e);
            return;
        }

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("SetLocalDescription timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SetLocalDescription interrupted", e);
        }

        if (closed) {
            return;
        }

        if (errorRef.get() != null) {
            throw new RuntimeException("SetLocalDescription failed: " + errorRef.get());
        }

        if (onSuccess != null) {
            onSuccess.run();
        }
    }

    private void setRemoteDescription(RTCSessionDescription description, Runnable onSuccess) {
        if (closed || peerConnection == null) {
            log.warn("Cannot setRemoteDescription: session closed or peerConnection null");
            return;
        }

        RTCPeerConnection pc = peerConnection;
        if (pc == null || closed) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> errorRef = new AtomicReference<>();

        try {
            pc.setRemoteDescription(description, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    latch.countDown();
                }

                @Override
                public void onFailure(String error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            log.error("Failed to call setRemoteDescription on peer connection", e);
            return;
        }

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("SetRemoteDescription timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SetRemoteDescription interrupted", e);
        }

        if (closed) {
            return;
        }

        if (errorRef.get() != null) {
            throw new RuntimeException("SetRemoteDescription failed: " + errorRef.get());
        }

        if (onSuccess != null) {
            onSuccess.run();
        }

        drainPendingCandidates();
    }

    private synchronized void drainPendingCandidates() {
        RTCPeerConnection pc = peerConnection;
        if (pc != null && !closed && pc.getRemoteDescription() != null) {
            for (RTCIceCandidate candidate : pendingCandidates) {
                log.debug("Adding queued ICE candidate: {}:{}", candidate.sdpMid, candidate.sdpMLineIndex);
                try {
                    pc.addIceCandidate(candidate);
                } catch (Throwable t) {
                    log.warn("Failed to add queued remote ICE candidate: {}", candidate.sdp, t);
                }
            }
            pendingCandidates.clear();
        }
    }
}
