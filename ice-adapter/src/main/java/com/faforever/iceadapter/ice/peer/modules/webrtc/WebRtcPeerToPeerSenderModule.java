package com.faforever.iceadapter.ice.peer.modules.webrtc;

import com.faforever.iceadapter.dto.command.CommandBase;
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

    protected final Peer peer;
    private boolean enabled = true;

    public WebRtcPeerToPeerSenderModule(Peer peer) {
        this.peer = peer;
    }

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void onSendGameData(Peer peer, byte[] data) {
        sendGameDataViaWebRtc(data);
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {
        sendControlDataViaWebRtc(data);
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
        sendControlDataViaWebRtc(command.bytes());
    }

    protected void sendGameDataViaWebRtc(byte[] data) {
        if (!enabled || peer.isClosing()) {
            return;
        }

        WebRtcSession session = peer.getWebRtcSession();
        if (session == null || !session.isConnected()) {
            return;
        }

        boolean sent = session.sendGameDataAsync(data);
        if (!sent) {
            log.warn(
                    "Failed to send {} bytes via WebRTC gameData channel to peer {}",
                    data.length,
                    peer.getPeerIdentifier());
            peer.lostConnect();
        } else {
            log.trace("Sent {} bytes via WebRTC gameData to peer {}", data.length, peer.getPeerIdentifier());
        }
    }

    protected void sendControlDataViaWebRtc(byte[] data) {
        if (!enabled || peer.isClosing()) {
            return;
        }

        WebRtcSession session = peer.getWebRtcSession();
        if (session == null || !session.isConnected()) {
            return;
        }

        boolean sent = session.sendControlDataAsync(data);
        if (!sent) {
            log.trace(
                    "Could not send {} bytes via WebRTC controlData channel to peer {}",
                    data.length,
                    peer.getPeerIdentifier());
        } else {
            log.trace("Sent {} bytes via WebRTC controlData to peer {}", data.length, peer.getPeerIdentifier());
        }
    }

    protected void sendViaWebRtc(byte[] data) {
        sendControlDataViaWebRtc(data);
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
