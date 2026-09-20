package com.faforever.iceadapter.ice;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.base.InMemoryRpcBus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test verifying end-to-end communication when connected to an unknown adapter (e.g. faf-pioneer).
 * Simulates remote peer stripping the "adapter" marker from ICE candidates.
 */
@Tag("integration")
@DisplayName("WebRTC Unknown Adapter Integration (e.g. faf-pioneer)")
class WebRtcUnknownAdapterIntegrationTest extends WebRtcPeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 10;
    private static final long DATA_WAIT_MS = 3_000;

    @Override
    protected InMemoryRpcBus createRpcBus() {
        return new InMemoryRpcBus() {
            @Override
            public synchronized void sendToRpc(CandidatesMessage message) {
                // If message comes from Peer B (remote player), strip the adapter field to simulate faf-pioneer
                if (message.srcId() == 2) {
                    List<CandidatePacket> pioneerCandidates = message.candidates().stream()
                            .map(cp -> new CandidatePacket(
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
                                    null)) // adapter is null from faf-pioneer
                            .toList();

                    CandidatesMessage pioneerMsg = new CandidatesMessage(
                            message.srcId(), message.destId(), message.password(), message.ufrag(), pioneerCandidates);
                    super.sendToRpc(pioneerMsg);
                } else {
                    super.sendToRpc(message);
                }
            }
        };
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("faf-ice-adapter should exchange raw FA UDP packets with unknown adapter peer without control channel")
    void testEndToEndWithUnknownAdapter() throws IOException {
        sleep(100);

        // Verify peerA does not recognize peerB as Java adapter and does NOT have a controlData channel
        assertNotNull(peerA.getWebRtcSession(), "peerA webRtcSession must exist");
        assertFalse(peerA.getWebRtcSession().isRemoteIsJavaAdapter(), "peerA must not recognize peerB as Java adapter");
        assertTrue(
                peerA.getWebRtcSession().getControlDataChannel().isEmpty(),
                "peerA must NOT have controlData channel when connected to Pioneer/unknown adapter");

        // Send game packets from FA socket A to FA socket B (and vice versa)
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("game-pkt-A-to-pioneer-%d".formatted(i));
        }

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketB.sendString("game-pkt-pioneer-to-A-%d".formatted(i));
        }

        sleep(DATA_WAIT_MS);

        List<byte[]> receivedAtB = socketB.getReceivedBytes();
        assertEquals(
                NUM_PACKETS,
                receivedAtB.size(),
                "Remote peer B (Pioneer simulation) should receive all packets from A");
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(receivedAtB.get(i), StandardCharsets.UTF_8);
            assertEquals("game-pkt-A-to-pioneer-" + i, received, "Packet content mismatch from A->B");
        }

        List<byte[]> receivedAtA = socketA.getReceivedBytes();
        assertEquals(NUM_PACKETS, receivedAtA.size(), "Local peer A should receive all packets from remote peer B");
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(receivedAtA.get(i), StandardCharsets.UTF_8);
            assertEquals("game-pkt-pioneer-to-A-" + i, received, "Packet content mismatch from B->A");
        }
    }
}
