package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.CandidatePacket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("WebRTC Unknown Adapter Connection Tests")
class WebRtcUnknownAdapterTest {

    private WebRtcSession localSession;
    private WebRtcSession remoteSession;

    @AfterEach
    void tearDown() {
        if (localSession != null) {
            localSession.close();
        }
        if (remoteSession != null) {
            remoteSession.close();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Local offerer connecting to unknown adapter (e.g. faf-pioneer) should not create controlData channel")
    void testOffererConnectsToUnknownAdapterPeer() throws Exception {
        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory.getFactory());

        localSession = new WebRtcSession(factory);
        remoteSession = new WebRtcSession(factory);

        List<byte[]> localReceivedGameData = new CopyOnWriteArrayList<>();
        List<String> localReceivedChannelLabels = new CopyOnWriteArrayList<>();
        List<byte[]> remoteReceivedData = new CopyOnWriteArrayList<>();

        CountDownLatch localConnectedLatch = new CountDownLatch(1);
        CountDownLatch remoteConnectedLatch = new CountDownLatch(1);

        WebRtcSession.SessionStateHandler localHandler = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                localConnectedLatch.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Local error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
                // Forward offer to remote peer (simulating Pioneer receiving SDP)
                remoteSession.processRemoteOffer(sdp);
            }

            @Override
            public void onAnswerCreated(String sdp) {}

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                remoteSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        WebRtcSession.SessionStateHandler remoteHandler = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                remoteConnectedLatch.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Remote error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp) {}

            @Override
            public void onAnswerCreated(String sdp, List<CandidatePacket> candidates) {
                // Simulate remote peer (faf-pioneer) responding with candidates WITHOUT "adapter" marker
                List<CandidatePacket> pioneerCandidates = new ArrayList<>();
                if (candidates != null) {
                    for (CandidatePacket cp : candidates) {
                        // Strip the adapter identifier to simulate faf-pioneer / unknown adapter
                        pioneerCandidates.add(new CandidatePacket(
                                cp.foundation(),
                                cp.protocol(),
                                cp.priority(),
                                cp.ip(),
                                cp.port(),
                                cp.type(),
                                cp.generation(),
                                cp.id(),
                                cp.relAddr(),
                                cp.relPort(),
                                null)); // adapter is null from faf-pioneer
                    }
                }
                localSession.processRemoteAnswer(sdp, pioneerCandidates);
            }

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                localSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        // Initialize local session with 3-arg messageHandler (channelLabel, data, isBinary)
        localSession.init(
                true,
                List.of(),
                (channelLabel, data, isBinary) -> {
                    localReceivedChannelLabels.add(channelLabel);
                    localReceivedGameData.add(data);
                },
                localHandler);

        // Remote session represents faf-pioneer (single gameData channel)
        remoteSession.init(
                false, List.of(), (channelLabel, data, isBinary) -> remoteReceivedData.add(data), remoteHandler);

        localSession.createOffer();

        assertTrue(localConnectedLatch.await(15, TimeUnit.SECONDS), "Local peer should connect");
        assertTrue(remoteConnectedLatch.await(15, TimeUnit.SECONDS), "Remote peer should connect");

        // Wait a moment for any potential dynamic channel creation
        Thread.sleep(200);

        // Verify that remote peer is recognized as NON-Java adapter
        assertFalse(localSession.isRemoteIsJavaAdapter(), "Remote peer must NOT be recognized as Java adapter");

        // Verify controlData channel was NOT created
        assertTrue(
                localSession.getControlDataChannel().isEmpty(),
                "Offerer must NOT create controlData channel for unknown adapter");

        // Verify gameData channel exists and is open
        assertTrue(localSession.getDataChannel().isPresent(), "gameData channel must exist");
        assertEquals(
                WebRtcSession.CHANNEL_GAME_DATA,
                localSession.getDataChannel().get().getLabel());

        // Verify sendControlDataAsync returns false since control channel doesn't exist
        byte[] controlPayload = new byte[] {0x01, 0x02, 0x03};
        assertFalse(
                localSession.sendControlDataAsync(controlPayload),
                "sendControlDataAsync must fail when controlData channel is absent");

        // Verify bidirectional transmission of raw game packets over gameData channel
        byte[] localGamePacket = new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55};
        byte[] remoteGamePacket = new byte[] {0x55, 0x44, 0x33, 0x22, 0x11, 0x00};

        assertTrue(localSession.sendGameDataAsync(localGamePacket), "local sendGameDataAsync should succeed");
        assertTrue(remoteSession.sendGameDataAsync(remoteGamePacket), "remote sendGameDataAsync should succeed");

        waitForCondition(() -> !remoteReceivedData.isEmpty(), 5000);
        waitForCondition(() -> !localReceivedGameData.isEmpty(), 5000);

        assertEquals(1, remoteReceivedData.size(), "Remote should receive exactly 1 game packet");
        assertArrayEquals(localGamePacket, remoteReceivedData.get(0), "Remote received payload must match exactly");

        assertEquals(1, localReceivedGameData.size(), "Local should receive exactly 1 game packet");
        assertArrayEquals(remoteGamePacket, localReceivedGameData.get(0), "Local received payload must match exactly");
        assertEquals(
                WebRtcSession.CHANNEL_GAME_DATA,
                localReceivedChannelLabels.get(0),
                "Local message should be tagged with gameData channel label");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Local answerer connecting to unknown offerer with custom adapter name should not create controlData")
    void testAnswererConnectsToUnknownAdapterOfferer() throws Exception {
        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory.getFactory());

        localSession = new WebRtcSession(factory);
        remoteSession = new WebRtcSession(factory);

        List<byte[]> localReceivedGameData = new CopyOnWriteArrayList<>();
        List<byte[]> remoteReceivedData = new CopyOnWriteArrayList<>();

        CountDownLatch localConnectedLatch = new CountDownLatch(1);
        CountDownLatch remoteConnectedLatch = new CountDownLatch(1);

        WebRtcSession.SessionStateHandler remoteOffererHandler = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                remoteConnectedLatch.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Remote error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
                // Simulate unknown adapter (e.g. "some-custom-proxy")
                List<CandidatePacket> customCandidates = new ArrayList<>();
                if (candidates != null) {
                    for (CandidatePacket cp : candidates) {
                        customCandidates.add(new CandidatePacket(
                                cp.foundation(),
                                cp.protocol(),
                                cp.priority(),
                                cp.ip(),
                                cp.port(),
                                cp.type(),
                                cp.generation(),
                                cp.id(),
                                cp.relAddr(),
                                cp.relPort(),
                                "some-custom-proxy"));
                    }
                }
                localSession.processRemoteOffer(sdp, customCandidates);
            }

            @Override
            public void onAnswerCreated(String sdp) {}

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                localSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        WebRtcSession.SessionStateHandler localAnswererHandler = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                localConnectedLatch.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Local error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp) {}

            @Override
            public void onAnswerCreated(String sdp, List<CandidatePacket> candidates) {
                remoteSession.processRemoteAnswer(sdp);
            }

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                remoteSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        // Remote is offerer (e.g. Pioneer or custom adapter)
        remoteSession.init(
                true, List.of(), (channelLabel, data, isBinary) -> remoteReceivedData.add(data), remoteOffererHandler);

        // Local is answerer (Java faf-ice-adapter)
        localSession.init(
                false,
                List.of(),
                (channelLabel, data, isBinary) -> localReceivedGameData.add(data),
                localAnswererHandler);

        remoteSession.createOffer();

        assertTrue(localConnectedLatch.await(15, TimeUnit.SECONDS), "Local peer should connect");
        assertTrue(remoteConnectedLatch.await(15, TimeUnit.SECONDS), "Remote peer should connect");

        Thread.sleep(200);

        // Verify that custom unknown adapter is NOT recognized as Java adapter
        assertFalse(
                localSession.isRemoteIsJavaAdapter(), "Remote custom adapter must NOT be recognized as Java adapter");
        assertTrue(
                localSession.getControlDataChannel().isEmpty(),
                "Answerer must NOT have controlData channel when remote is not Java adapter");

        // Verify data transmission works cleanly
        byte[] testData = new byte[] {0x12, 0x34, 0x56, 0x78};
        assertTrue(remoteSession.sendGameDataAsync(testData), "Remote sendGameDataAsync should succeed");

        waitForCondition(() -> !localReceivedGameData.isEmpty(), 5000);
        assertEquals(1, localReceivedGameData.size());
        assertArrayEquals(testData, localReceivedGameData.get(0));
    }

    private void waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
