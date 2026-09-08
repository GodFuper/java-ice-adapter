package com.faforever.iceadapter.ice.peer.modules.webrtc;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import lombok.extern.slf4j.Slf4j;

/**
 * WebRTC-based peer-to-peer sender module.
 * Replaces PeerToPeerSenderModule when --transport=webrtc is used.
 * Sends data via WebRTC data channel instead of ice4j Component.
 */
@Slf4j
public class WebRtcPeerToPeerSenderModule implements ModuleBase, PeerEventListener {

    private final Peer peer;
    private volatile WebRtcSession webRtcSession;
    private boolean enabled = true;

    public WebRtcPeerToPeerSenderModule(Peer peer) {
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
        log.info("WebRtcPeerToPeerSenderModule: WebRTC session set for peer {}", peer.getPeerIdentifier());
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {
        sendViaWebRtc(data);
    }

    @Override
    public void onSendCommand(Peer peer, com.faforever.iceadapter.dto.command.CommandBase command, boolean force) {
        if (!peer.isSupportCommand() && !force) {
            return;
        }
        sendViaWebRtc(command.bytes());
    }

    private void sendViaWebRtc(byte[] data) {
        if (!enabled || peer.isClosing()) {
            return;
        }

        WebRtcSession session = webRtcSession != null ? webRtcSession : peer.getWebRtcSession();
        if (session == null || !session.isConnected()) {
            return;
        }

        boolean sent = session.sendDataAsync(data, true);
        if (!sent) {
            log.warn("Failed to send {} bytes via WebRTC data channel to peer {}", data.length, peer.getPeerIdentifier());
            peer.lostConnect();
        } else {
            log.trace("Sent {} bytes via WebRTC to peer {}", data.length, peer.getPeerIdentifier());
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
