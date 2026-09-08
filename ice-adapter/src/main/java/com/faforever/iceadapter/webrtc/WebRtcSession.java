package com.faforever.iceadapter.webrtc;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.IceServer;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.ExecutorHolder;
import dev.onvoid.webrtc.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the full WebRTC lifecycle for ONE peer connection.
 * Handles SDP offer/answer exchange, ICE candidate exchange, and data channel lifecycle.
 */
@Slf4j
public class WebRtcSession implements PeerConnectionObserver, RTCDataChannelObserver {

    private final WebRtcConnectionFactory factory;
    private RTCPeerConnection peerConnection;
    private RTCDataChannel dataChannel;
    private RTCConfiguration config;

    public RTCConfiguration getConfig() {
        return config;
    }

    // Callbacks
    private DataChannelMessageHandler messageHandler;
    private SessionStateHandler stateHandler;

    // State
    private boolean isOfferer;
    private volatile boolean connected = false;
    private volatile boolean closed = false;
    private volatile boolean initialized = false;

    // Pending ICE candidates received before remote description is set
    private final List<RTCIceCandidate> pendingCandidates;
    private volatile boolean remoteDescriptionSet = false;

    // Latches for async operations
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch dataChannelOpenLatch = new CountDownLatch(1);

    // Vanilla ICE candidate gathering (gathered before sending offer/answer)
    private final List<CandidatePacket> gatheredCandidatePackets = new CopyOnWriteArrayList<>();
    private final List<RTCIceCandidate> gatheredIceCandidates = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gatheringLatch;

    @Data
    public static class SessionStats {
        private volatile float rttMs = 0.0f;
        private volatile String localCandidateType = "unknown";
        private volatile String remoteCandidateType = "unknown";
        private volatile String localAddress = "";
        private volatile String remoteAddress = "";
        private volatile long bytesSent = 0;
        private volatile long bytesReceived = 0;
        private volatile long messagesSent = 0;
        private volatile long messagesReceived = 0;
        private volatile String dataChannelState = "closed";
    }

    private final SessionStats stats = new SessionStats();
    private ScheduledFuture<?> statsFuture;

    public SessionStats getStats() {
        return stats;
    }

    /**
     * Query WebRTC statistics from libwebrtc peer connection.
     */
    public void updateStats() {
        if (peerConnection == null || closed) {
            return;
        }
        try {
            peerConnection.getStats(report -> {
                Map<String, RTCStats> statsMap = report.getStats();
                String selectedPairId = null;

                for (RTCStats s : statsMap.values()) {
                    if (s.getType() == RTCStatsType.TRANSPORT) {
                        Object pairId = s.getAttributes().get("selectedCandidatePairId");
                        if (pairId != null) {
                            selectedPairId = pairId.toString();
                            break;
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

                    Object localId = attrs.get("localCandidateId");
                    if (localId != null && statsMap.containsKey(localId.toString())) {
                        Map<String, Object> localAttrs = statsMap.get(localId.toString()).getAttributes();
                        stats.setLocalCandidateType(String.valueOf(localAttrs.get("candidateType")));
                        stats.setLocalAddress(localAttrs.get("address") + ":" + localAttrs.get("port"));
                    }

                    Object remoteId = attrs.get("remoteCandidateId");
                    if (remoteId != null && statsMap.containsKey(remoteId.toString())) {
                        Map<String, Object> remoteAttrs = statsMap.get(remoteId.toString()).getAttributes();
                        stats.setRemoteCandidateType(String.valueOf(remoteAttrs.get("candidateType")));
                        stats.setRemoteAddress(remoteAttrs.get("address") + ":" + remoteAttrs.get("port"));
                    }
                }

                for (RTCStats s : statsMap.values()) {
                    if (s.getType() == RTCStatsType.DATA_CHANNEL) {
                        Map<String, Object> attrs = s.getAttributes();
                        stats.setDataChannelState(String.valueOf(attrs.get("state")));
                        if (attrs.get("bytesSent") instanceof Number n) {
                            stats.setBytesSent(n.longValue());
                        }
                        if (attrs.get("bytesReceived") instanceof Number n) {
                            stats.setBytesReceived(n.longValue());
                        }
                        if (attrs.get("messagesSent") instanceof Number n) {
                            stats.setMessagesSent(n.longValue());
                        }
                        if (attrs.get("messagesReceived") instanceof Number n) {
                            stats.setMessagesReceived(n.longValue());
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.trace("Error querying WebRTC stats: {}", e.getMessage());
        }
    }

    /**
     * Callback for incoming data on the data channel.
     */
    public interface DataChannelMessageHandler {
        void onMessage(byte[] data, boolean isBinary);
    }

    /**
     * Callback for session state changes.
     */
    public interface SessionStateHandler {
        void onConnected();

        void onDisconnected();

        void onError(String error);

        default void onOfferCreated(String sdp) {
        }

        default void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
            onOfferCreated(sdp);
        }

        default void onAnswerCreated(String sdp) {
        }

        default void onAnswerCreated(String sdp, List<CandidatePacket> candidates) {
            onAnswerCreated(sdp);
        }

        void onRemoteDescriptionSet();

        default void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        }
    }

    public WebRtcSession(WebRtcConnectionFactory factory) {
        this.factory = factory;
        this.pendingCandidates = new ArrayList<>();
    }

    /**
     * Initialize the session with configuration and callbacks.
     */
    public synchronized void init(boolean offerer, List<IceServer> iceServers,
                                  DataChannelMessageHandler messageHandler, SessionStateHandler stateHandler) {
        init(offerer, iceServers, null, messageHandler, stateHandler);
    }

    /**
     * Initialize the session with configuration, options, and callbacks.
     */
    public synchronized void init(boolean offerer, List<IceServer> iceServers,
                                  IceOptions options,
                                  DataChannelMessageHandler messageHandler, SessionStateHandler stateHandler) {
        this.isOfferer = offerer;
        this.messageHandler = messageHandler;
        this.stateHandler = stateHandler;

        config = new RTCConfiguration();
        if (options != null && options.isForceRelay()) {
            config.iceTransportPolicy = RTCIceTransportPolicy.RELAY;
        } else {
            config.iceTransportPolicy = RTCIceTransportPolicy.ALL;
        }

        if (options != null && (options.getMinPort() > 0 || options.getMaxPort() > 0)) {
            if (config.portAllocatorConfig == null) {
                config.portAllocatorConfig = new PortAllocatorConfig();
            }
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
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true;
            this.dataChannel = peerConnection.createDataChannel("fa-data", init);
            this.dataChannel.registerObserver(this);
            log.info("Created local data channel 'fa-data' for offerer");
        }
        initialized = true;

        try {
            statsFuture = ExecutorHolder.getScheduledExecutor()
                    .scheduleWithFixedDelay(this::updateStats, 1, 1, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Could not schedule stats task", e);
        }

        log.info("WebRtcSession initialized (offerer={}, iceServers={}, forceRelay={}, minPort={}, maxPort={})",
                offerer, config.iceServers.size(),
                options != null && options.isForceRelay(),
                options != null ? options.getMinPort() : 0,
                options != null ? options.getMaxPort() : 0);
    }

    /**
     * Create an SDP offer.
     * Returns the offer via the stateHandler.onOfferCreated() callback.
     */
    public void createOffer() {
        if (!initialized) {
            throw new IllegalStateException("Session not initialized");
        }

        AtomicReference<RTCSessionDescription> offerRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        peerConnection.createOffer(new dev.onvoid.webrtc.RTCOfferOptions(), new CreateSessionDescriptionObserver() {
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

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Offer creation timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Offer creation interrupted", e);
        }

        RTCSessionDescription offer = offerRef.get();
        if (offer == null) {
            throw new RuntimeException("Offer is null");
        }

        // Clear previously gathered candidates
        gatheredCandidatePackets.clear();
        gatheredIceCandidates.clear();
        CountDownLatch gatherLatch = new CountDownLatch(1);
        gatheringLatch = gatherLatch;

        // Set local description
        setLocalDescription(offer, () -> {
            log.info("Offer created and set as local description, gathering ICE candidates...");
        });

        // Wait for ICE candidate gathering to complete (Vanilla ICE)
        try {
            boolean completed = gatherLatch.await(1500, TimeUnit.MILLISECONDS);
            log.info("ICE candidate gathering for offer finished (completed={}, gatheredCandidates={})",
                    completed, gatheredCandidatePackets.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for ICE candidate gathering");
        }

        RTCSessionDescription localDesc = peerConnection.getLocalDescription();
        String finalSdp = (localDesc != null && localDesc.sdp != null) ? localDesc.sdp : offer.sdp;
        List<CandidatePacket> candidatesCopy = List.copyOf(gatheredCandidatePackets);

        if (stateHandler != null) {
            stateHandler.onOfferCreated(finalSdp, candidatesCopy);
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
                    String candStr = CandidateUtil.candidatePacketToWebRtcString(cp);
                    if (candStr != null) {
                        addRemoteCandidate("0", 0, candStr);
                    }
                }
            }
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
     * Add a remote ICE candidate.
     * If remote description is not set yet, buffer candidate until remote description is set.
     */
    public synchronized void addRemoteCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        RTCIceCandidate iceCandidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);
        if (peerConnection != null && peerConnection.getRemoteDescription() != null) {
            peerConnection.addIceCandidate(iceCandidate);
        } else {
            pendingCandidates.add(iceCandidate);
        }
    }

    /**
     * Get the data channel (null if not yet created/received).
     */
    public Optional<RTCDataChannel> getDataChannel() {
        return Optional.ofNullable(dataChannel);
    }

    /**
     * Check if the session is connected.
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Wait for connection to be established.
     */
    public boolean waitForConnected(long timeoutMs) throws InterruptedException {
        return connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Send data over the data channel.
     */
    public boolean sendData(byte[] data, boolean isBinary) {
        if (dataChannel == null || dataChannel.getState() != RTCDataChannelState.OPEN) {
            log.warn("Data channel not open, cannot send data");
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            RTCDataChannelBuffer bufferData = new RTCDataChannelBuffer(buffer, isBinary);
            dataChannel.send(bufferData);
        } catch (Exception e) {
            log.error("Failed to send data over data channel", e);
            return false;
        }
        return true;
    }

    /**
     * Send data asynchronously without blocking calling thread on native WebRTC network thread.
     */
    public boolean sendDataAsync(byte[] data, boolean isBinary) {
        if (dataChannel == null || dataChannel.getState() != RTCDataChannelState.OPEN) {
            log.warn("Data channel not open, cannot send data async");
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            RTCDataChannelBuffer bufferData = new RTCDataChannelBuffer(buffer, isBinary);
            dataChannel.sendAsync(bufferData);
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

    public boolean isClosed() {
        return closed;
    }

    /**
     * Close the session and dispose native resources.
     * Idempotent: safe to call multiple times.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        stateHandler = null;
        messageHandler = null;
        log.info("Closing WebRtcSession");
        if (dataChannel != null) {
            try {
                dataChannel.unregisterObserver();
            } catch (Exception e) {
                log.warn("Error unregistering data channel observer", e);
            }
            try {
                dataChannel.close();
            } catch (Exception e) {
                log.warn("Error closing data channel", e);
            }
            try {
                dataChannel.dispose();
            } catch (Exception e) {
                log.warn("Error disposing data channel", e);
            }
            dataChannel = null;
        }
        if (peerConnection != null) {
            try {
                peerConnection.close();
            } catch (Exception e) {
                log.warn("Error closing peer connection", e);
            }
            peerConnection = null;
        }
        if (statsFuture != null) {
            statsFuture.cancel(false);
            statsFuture = null;
        }
        pendingCandidates.clear();
        connected = false;
        initialized = false;
        log.info("WebRtcSession closed");
    }

    // ==================== PeerConnectionObserver ====================

    @Override
    public void onIceCandidate(RTCIceCandidate candidate) {
        if (candidate != null && candidate.sdp != null) {
            log.debug("ICE candidate gathered: {}:{}:{}", candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
            gatheredIceCandidates.add(candidate);
            CandidatePacket packet = CandidateUtil.webRtcCandidateToPacket(candidate.sdp);
            if (packet != null) {
                gatheredCandidatePackets.add(packet);
            }
            if (stateHandler != null) {
                stateHandler.onIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
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
        this.dataChannel = dataChannel;
        dataChannel.registerObserver(this);
        if (dataChannel.getState() == RTCDataChannelState.OPEN) {
            connected = true;
            connectedLatch.countDown();
            dataChannelOpenLatch.countDown();
            if (stateHandler != null) {
                stateHandler.onConnected();
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
            if (stateHandler != null && !closed) {
                stateHandler.onDisconnected();
            }
        }
    }

    // ==================== RTCDataChannelObserver ====================

    @Override
    public void onBufferedAmountChange(long previousAmount) {
        // ignore
    }

    @Override
    public void onStateChange() {
        if (closed || dataChannel == null) {
            return;
        }
        RTCDataChannelState state = dataChannel.getState();
        log.info("Data channel state: {}", state);
        if (state == RTCDataChannelState.OPEN) {
            connected = true;
            connectedLatch.countDown();
            dataChannelOpenLatch.countDown();
            if (stateHandler != null && !closed) {
                stateHandler.onConnected();
            }
        } else if (state == RTCDataChannelState.CLOSED || state == RTCDataChannelState.CLOSING) {
            connected = false;
            if (stateHandler != null && !closed) {
                stateHandler.onDisconnected();
            }
            dataChannel.close();
        }
    }

    @Override
    public void onMessage(RTCDataChannelBuffer buffer) {
        ByteBuffer data = buffer.data;
        byte[] payload = new byte[data.remaining()];
        data.duplicate().get(payload);
        log.debug("Data channel message received: {} bytes, binary={}", payload.length, buffer.binary);
        if (messageHandler != null) {
            messageHandler.onMessage(payload, buffer.binary);
        }
    }

    // ==================== Internal Helpers ====================

    private void createAnswer() {
        AtomicReference<RTCSessionDescription> answerRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
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

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Answer creation timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Answer creation interrupted", e);
        }

        RTCSessionDescription answer = answerRef.get();
        if (answer == null) {
            throw new RuntimeException("Answer is null");
        }

        // Clear previously gathered candidates
        gatheredCandidatePackets.clear();
        gatheredIceCandidates.clear();
        CountDownLatch gatherLatch = new CountDownLatch(1);
        gatheringLatch = gatherLatch;

        setLocalDescription(answer, () -> {
            log.info("Answer created and set as local description, gathering ICE candidates...");
        });

        // Wait for ICE candidate gathering to complete (Vanilla ICE)
        try {
            boolean completed = gatherLatch.await(1500, TimeUnit.MILLISECONDS);
            log.info("ICE candidate gathering for answer finished (completed={}, gatheredCandidates={})",
                    completed, gatheredCandidatePackets.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for ICE candidate gathering");
        }

        RTCSessionDescription localDesc = peerConnection.getLocalDescription();
        String finalSdp = (localDesc != null && localDesc.sdp != null) ? localDesc.sdp : answer.sdp;
        List<CandidatePacket> candidatesCopy = List.copyOf(gatheredCandidatePackets);

        if (stateHandler != null) {
            stateHandler.onAnswerCreated(finalSdp, candidatesCopy);
        }
    }

    private void setLocalDescription(RTCSessionDescription description, Runnable onSuccess) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> errorRef = new AtomicReference<>();

        peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
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

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("SetLocalDescription timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SetLocalDescription interrupted", e);
        }

        if (errorRef.get() != null) {
            throw new RuntimeException("SetLocalDescription failed: " + errorRef.get());
        }

        if (onSuccess != null) {
            onSuccess.run();
        }
    }

    private void setRemoteDescription(RTCSessionDescription description, Runnable onSuccess) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> errorRef = new AtomicReference<>();

        peerConnection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
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

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("SetRemoteDescription timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SetRemoteDescription interrupted", e);
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
        if (peerConnection != null && peerConnection.getRemoteDescription() != null) {
            for (RTCIceCandidate candidate : pendingCandidates) {
                log.debug("Adding queued ICE candidate: {}:{}", candidate.sdpMid, candidate.sdpMLineIndex);
                peerConnection.addIceCandidate(candidate);
            }
            pendingCandidates.clear();
        }
    }

    // ==================== Getters for Testing/Debugging ====================

    public RTCPeerConnection getPeerConnection() {
        return peerConnection;
    }

    public boolean isOfferer() {
        return isOfferer;
    }
}
