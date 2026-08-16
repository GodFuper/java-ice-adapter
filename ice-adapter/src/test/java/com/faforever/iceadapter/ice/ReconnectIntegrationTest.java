package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for reconnect behavior.
 * Tests verify that reconnect triggers state changes and data can be re-exchanged after reconnect.
 */
@Slf4j
@DisplayName("Reconnect Integration")
class ReconnectIntegrationTest extends PeerConnectionIntegrationBase {

    private static final int NUM_PACKETS_PER_ROUND = 10;
    private static final long DATA_WAIT_MS = 4_000;

    protected Set<PeerModule> getDisabledModules() {
        return Set.of(
                PeerModule.PEER_TURN_REFRESHER_MODULE,
                PeerModule.RELAY_CLIENT_MODULE,
                PeerModule.RELAY_SERVER_MODULE,
                PeerModule.AUTO_RELAY_CALCULATE_RTT);
    }

    // =========================================================================
    // Test 1: Reconnect from the controlling side (Peer A, localOffer=true)
    // =========================================================================
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Controlling peer reconnect should trigger multiple state transitions and allow data exchange")
    void testReconnectFromControllingSide() throws IOException, InterruptedException {
        sleep(200); // Give modules time to settle

        // Initial connection should be established in @BeforeEach
        assertTrue(peerA.isConnected(), "Peer A (controlling) should be initially connected");
        assertTrue(peerB.isConnected(), "Peer B (controlled) should be initially connected");

        // Round 1: Send data before first reconnect
        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "round1-A", NUM_PACKETS_PER_ROUND);
        sendPackets(socketB, "round1-B", NUM_PACKETS_PER_ROUND);
        sleep(DATA_WAIT_MS);

        List<byte[]> receivedAtB_round1 = socketB.getReceivedBytes();
        List<byte[]> receivedAtA_round1 = socketA.getReceivedBytes();
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtB_round1.size(),
                "socketB should receive " + NUM_PACKETS_PER_ROUND + " packets from A in round 1");
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtA_round1.size(),
                "socketA should receive " + NUM_PACKETS_PER_ROUND + " packets from B in round 1");

        // Round 2: Reconnect from controlling side (Peer A) and send data
        reconnectPeer(peerA, peerB, "reconnect-A");
        awaitReconnection(peerA, peerB);

        assertTrue(peerA.isConnected(), "Peer A (controlling) should be connected after reconnect");
        assertTrue(peerB.isConnected(), "Peer B (controlled) should still be connected after A reconnects");

        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "round2-A", NUM_PACKETS_PER_ROUND);
        sendPackets(socketB, "round2-B", NUM_PACKETS_PER_ROUND);
        sleep(DATA_WAIT_MS);

        List<byte[]> receivedAtB_round2 = socketB.getReceivedBytes();
        List<byte[]> receivedAtA_round2 = socketA.getReceivedBytes();
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtB_round2.size(),
                "socketB should receive " + NUM_PACKETS_PER_ROUND + " packets from A in round 2");
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtA_round2.size(),
                "socketA should receive " + NUM_PACKETS_PER_ROUND + " packets from B in round 2");

        // Round 3: Another reconnect from controlling side and verify data exchange
        reconnectPeer(peerA, peerB, "reconnect-A-2");
        awaitReconnection(peerA, peerB);

        assertTrue(peerA.isConnected(), "Peer A (controlling) should be connected after second reconnect");
        assertTrue(peerB.isConnected(), "Peer B (controlled) should still be connected after second A reconnect");

        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "round3-A", NUM_PACKETS_PER_ROUND);
        sendPackets(socketB, "round3-B", NUM_PACKETS_PER_ROUND);
        sleep(DATA_WAIT_MS);

        List<byte[]> receivedAtB_round3 = socketB.getReceivedBytes();
        List<byte[]> receivedAtA_round3 = socketA.getReceivedBytes();
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtB_round3.size(),
                "socketB should receive " + NUM_PACKETS_PER_ROUND + " packets from A in round 3");
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtA_round3.size(),
                "socketA should receive " + NUM_PACKETS_PER_ROUND + " packets from B in round 3");

        // Verify packet content
        for (int i = 0; i < NUM_PACKETS_PER_ROUND; i++) {
            String fromB = new String(receivedAtB_round1.get(i), StandardCharsets.UTF_8);
            assertTrue(
                    fromB.startsWith("round1-A"),
                    "Round 1 A->B packet " + i + " should start with 'round1-A', got: " + fromB);
            fromB = new String(receivedAtB_round2.get(i), StandardCharsets.UTF_8);
            assertTrue(
                    fromB.startsWith("round2-A"),
                    "Round 2 A->B packet " + i + " should start with 'round2-A', got: " + fromB);
            fromB = new String(receivedAtB_round3.get(i), StandardCharsets.UTF_8);
            assertTrue(
                    fromB.startsWith("round3-A"),
                    "Round 3 A->B packet " + i + " should start with 'round3-A', got: " + fromB);
        }
    }

    // =========================================================================
    // Test 2: Reconnect from the controlled side (Peer B, localOffer=false)
    // =========================================================================
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Controlled peer reconnect should trigger multiple state transitions and allow data exchange")
    void testReconnectFromControlledSide() throws IOException, InterruptedException {
        sleep(200); // Give modules time to settle

        // Initial connection should be established in @BeforeEach
        assertTrue(peerA.isConnected(), "Peer A (controlling) should be initially connected");
        assertTrue(peerB.isConnected(), "Peer B (controlled) should be initially connected");

        // Round 1: Send data before first reconnect
        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "round1-A", NUM_PACKETS_PER_ROUND);
        sendPackets(socketB, "round1-B", NUM_PACKETS_PER_ROUND);
        sleep(DATA_WAIT_MS);

        List<byte[]> receivedAtB_round1 = socketB.getReceivedBytes();
        List<byte[]> receivedAtA_round1 = socketA.getReceivedBytes();
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtB_round1.size(),
                "socketB should receive " + NUM_PACKETS_PER_ROUND + " packets from A in round 1");
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtA_round1.size(),
                "socketA should receive " + NUM_PACKETS_PER_ROUND + " packets from B in round 1");

        // Round 2: Reconnect from controlled side (Peer B) and send data
        reconnectPeer(peerB, peerA, "reconnect-B");
        awaitReconnection(peerA, peerB);

        assertTrue(peerA.isConnected(), "Peer A (controlling) should still be connected after B reconnects");
        assertTrue(peerB.isConnected(), "Peer B (controlled) should be connected after reconnect");

        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "round2-A", NUM_PACKETS_PER_ROUND);
        sendPackets(socketB, "round2-B", NUM_PACKETS_PER_ROUND);
        sleep(DATA_WAIT_MS);

        List<byte[]> receivedAtB_round2 = socketB.getReceivedBytes();
        List<byte[]> receivedAtA_round2 = socketA.getReceivedBytes();
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtB_round2.size(),
                "socketB should receive " + NUM_PACKETS_PER_ROUND + " packets from A in round 2");
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtA_round2.size(),
                "socketA should receive " + NUM_PACKETS_PER_ROUND + " packets from B in round 2");

        // Round 3: Another reconnect from controlled side and verify data exchange
        reconnectPeer(peerB, peerA, "reconnect-B-2");
        awaitReconnection(peerA, peerB);

        assertTrue(peerA.isConnected(), "Peer A (controlling) should still be connected after second B reconnect");
        assertTrue(peerB.isConnected(), "Peer B (controlled) should be connected after second reconnect");

        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "round3-A", NUM_PACKETS_PER_ROUND);
        sendPackets(socketB, "round3-B", NUM_PACKETS_PER_ROUND);
        sleep(DATA_WAIT_MS);

        List<byte[]> receivedAtB_round3 = socketB.getReceivedBytes();
        List<byte[]> receivedAtA_round3 = socketA.getReceivedBytes();
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtB_round3.size(),
                "socketB should receive " + NUM_PACKETS_PER_ROUND + " packets from A in round 3");
        assertEquals(
                NUM_PACKETS_PER_ROUND,
                receivedAtA_round3.size(),
                "socketA should receive " + NUM_PACKETS_PER_ROUND + " packets from B in round 3");

        // Verify packet content
        for (int i = 0; i < NUM_PACKETS_PER_ROUND; i++) {
            String fromA = new String(receivedAtA_round1.get(i), StandardCharsets.UTF_8);
            assertTrue(
                    fromA.startsWith("round1-B"),
                    "Round 1 B->A packet " + i + " should start with 'round1-B', got: " + fromA);
            fromA = new String(receivedAtA_round2.get(i), StandardCharsets.UTF_8);
            assertTrue(
                    fromA.startsWith("round2-B"),
                    "Round 2 B->A packet " + i + " should start with 'round2-B', got: " + fromA);
            fromA = new String(receivedAtA_round3.get(i), StandardCharsets.UTF_8);
            assertTrue(
                    fromA.startsWith("round3-B"),
                    "Round 3 B->A packet " + i + " should start with 'round3-B', got: " + fromA);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Sends count UDP packets from the socket with the given prefix.
     */
    private void sendPackets(InMemoryDatagramSocket socket, String prefix, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            socket.sendString(prefix + "-" + i);
        }
    }

    /**
     * Triggers reconnect on the peer by calling lostConnect() through the GameSession.
     * For the controlling peer, this simulates triggering a reconnect via
     * the connectivity checker timeout or explicit call.
     * For the controlled peer, reconnect triggers automatic re-ICE.
     */
    private void reconnectPeer(Peer peer1, Peer peer2, String logPrefix) throws InterruptedException {
        log.info("{}: Triggering reconnect for peer {}", logPrefix, peer1.getPeerIdentifier());

        // Get the disconnect state via lostConnect (which is what reconnect() delegates to)
        peer1.reconnect();
        sleep(DATA_WAIT_MS);
        // Wait for connection to be re-established
        awaitIceReady(peer1, peer2);

        awaitIceState(peer1, IceState.CONNECTED, DATA_WAIT_MS);
        awaitIceState(peer2, IceState.CONNECTED, DATA_WAIT_MS);
    }

    /**
     * Waits for a peer to reach the specified ICE state within the timeout.
     */
    private void awaitIceState(Peer peer, IceState expectedState, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (peer.getIceState() == expectedState) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Expected state " + expectedState + " for peer " + peer.getPeerIdentifier() + " but got "
                + peer.getIceState() + " after " + timeoutMs + "ms");
    }

    /**
     * Alternative helper: wait for both peers to be connected (handles the case where one is null).
     */
    private void awaitReconnection(Peer... peers) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allConnected = true;
            for (Peer p : peers) {
                if (p != null && !p.isConnected()) {
                    allConnected = false;
                    break;
                }
            }
            if (allConnected) {
                return;
            }
            Thread.sleep(50);
        }

        StringBuilder sb = new StringBuilder("Peers not all connected after reconnect timeout: ");
        for (Peer p : peers) {
            if (p != null) {
                sb.append(String.format(
                        "%s(state=%s, connected=%s) ", p.getPeerIdentifier(), p.getIceState(), p.isConnected()));
            }
        }
        fail(sb.toString());
    }
}
