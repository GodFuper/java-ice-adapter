package com.faforever.iceadapter.ice.peer.modules.webrtc;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * WebRTC signaling module.
 * Intercepts CandidatesMessage and routes it to WebRTC session for SDP/ICE exchange.
 * Replaces the ice4j-based ICE candidate handling when --transport=webrtc is used.
 */
@Slf4j
public class WebRtcSignalingModule implements ModuleBase, PeerEventListener {

    private final Peer peer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile WebRtcSession webRtcSession;
    private boolean enabled = true;

    public WebRtcSignalingModule(Peer peer) {
        this.peer = peer;
    }

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    /**
     * Set the WebRTC session for this module.
     * Called by GameSession when WebRTC session is created.
     */
    public void setWebRtcSession(WebRtcSession session) {
        this.webRtcSession = session;
        log.info("WebRtcSignalingModule: WebRTC session set for peer {}", peer.getPeerIdentifier());
    }

    @Override
    public void onIceMessageFromRPC(Peer peer, CandidatesMessage message) {
        if (!enabled || webRtcSession == null) {
            return;
        }

        log.debug("WebRtcSignalingModule: Received CandidatesMessage for peer {}, forwarding to WebRTC", peer.getPeerIdentifier());

        // Serialize and send via WebRTC data channel
        try {
            String json = objectMapper.writeValueAsString(message);
            boolean sent = webRtcSession.sendTextData(json);
            if (!sent) {
                log.warn("Failed to send CandidatesMessage via WebRTC data channel to peer {}", peer.getPeerIdentifier());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize CandidatesMessage for peer {}", peer.getPeerIdentifier(), e);
        }
    }

    @Override
    public void onSendToRpc(Peer peer, CandidatesMessage message) {
        if (!enabled || webRtcSession == null) {
            return;
        }

        log.debug("WebRtcSignalingModule: Sending CandidatesMessage for peer {} via WebRTC", peer.getPeerIdentifier());

        try {
            String json = objectMapper.writeValueAsString(message);
            boolean sent = webRtcSession.sendTextData(json);
            if (!sent) {
                log.warn("Failed to send CandidatesMessage via WebRTC data channel to peer {}", peer.getPeerIdentifier());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize CandidatesMessage for peer {}", peer.getPeerIdentifier(), e);
        }
    }

    @Override
    public Boolean isEnabled() {
        return enabled;
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
    }
}
