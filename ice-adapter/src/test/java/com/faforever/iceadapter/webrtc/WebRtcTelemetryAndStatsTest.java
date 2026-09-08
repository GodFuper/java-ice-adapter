package com.faforever.iceadapter.webrtc;

import com.faforever.iceadapter.ice.peer.MainPeer;
import com.faforever.iceadapter.ice.peer.Peer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebRtcTelemetryAndStatsTest {

    @Test
    void testWebRtcStatsCollectionAndPeerIntegration() throws Exception {
        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        WebRtcSession sessionA = new WebRtcSession(factory);
        WebRtcSession sessionB = new WebRtcSession(factory);

        sessionA.init(true, List.of(), (d, b) -> {
        }, new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onError(String error) {
            }

            @Override
            public void onOfferCreated(String sdp) {
                sessionB.processRemoteOffer(sdp);
            }

            @Override
            public void onAnswerCreated(String sdp) {
            }

            @Override
            public void onRemoteDescriptionSet() {
            }

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                sessionB.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        });

        sessionB.init(false, List.of(), (d, b) -> {
        }, new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onError(String error) {
            }

            @Override
            public void onOfferCreated(String sdp) {
            }

            @Override
            public void onAnswerCreated(String sdp) {
                sessionA.processRemoteAnswer(sdp);
            }

            @Override
            public void onRemoteDescriptionSet() {
            }

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                sessionA.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        });

        sessionA.createOffer();

        assertTrue(sessionA.waitForConnected(10000));
        assertTrue(sessionB.waitForConnected(10000));

        // Send test data
        byte[] payload = new byte[]{10, 20, 30, 40, 50};
        assertTrue(sessionA.sendData(payload, true));

        // Allow stats to collect
        Thread.sleep(300);
        sessionA.updateStats();
        Thread.sleep(100);

        WebRtcSession.SessionStats stats = sessionA.getStats();
        assertNotNull(stats);
        assertEquals("open", stats.getDataChannelState());
        assertTrue(stats.getBytesSent() >= payload.length);
        assertTrue(stats.getMessagesSent() >= 1);
        assertNotNull(stats.getLocalCandidateType());
        assertNotNull(stats.getRemoteCandidateType());

        // Create Peer and attach session
        Peer peer = new MainPeer(1, 2, "PlayerB", true, 0, 0, false, Set.of());
        peer.setWebRtcSession(sessionA);

        assertEquals(stats.getRttMs(), peer.getRtt());
        assertEquals(1, peer.getCandidateTypes().size());
        assertEquals(stats.getLocalCandidateType(), peer.getCandidateTypes().get(0).getFirst());
        assertEquals(stats.getRemoteCandidateType(), peer.getCandidateTypes().get(0).getSecond());

        String fullInfo = peer.getFullInfoSelectedPair();
        assertNotNull(fullInfo);
        assertTrue(fullInfo.contains("WebRTC DataChannel: open"));
        assertTrue(fullInfo.contains("RTT:"));

        peer.close();
        sessionB.close();
    }
}
