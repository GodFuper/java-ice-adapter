package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.PeerSendMode;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for packet delivery over KCP transport under packet loss conditions.
 * <p>
 * This test does NOT use the full ICE infrastructure. Instead:
 * <ul>
 *   <li>Two Peers are created via TestGameSession</li>
 *   <li>ICE connection is established through InMemoryDatagramSocket</li>
 *   <li>DroppingPeerToPeerListenerModule replaces PEER_LISTENER_MODULE on peerB</li>
 *   <li>Data is sent through socketA → socketB</li>
 * </ul>
 * <p>
 * Tests verify that with 5%, 10%, 20% packet loss:
 * - All packets are delivered thanks to KCP reliability
 */
@DisplayName("KCP Transport Packet Loss Tests")
class ReliableTransportPacketLossTest extends PeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 100;
    private static final long DATA_WAIT_MS = 3_000;

    @Override
    protected Set<PeerModule> getDisabledModules() {
        return Set.of(
                PeerModule.PEER_LISTENER_MODULE,
                PeerModule.CONNECTION_CHECKER_MODULE,
                PeerModule.PEER_TURN_REFRESHER_MODULE,
                PeerModule.RELAY_CLIENT_MODULE,
                PeerModule.RELAY_SERVER_MODULE,
                PeerModule.AUTO_RELAY_CALCULATE_RTT);
    }

    // =========================================================================
    // Test 1: 5% packet loss — 100/100 should be delivered
    // =========================================================================
    @Test
    @Timeout(value = 120)
    @DisplayName("5% packet loss: all packets should be delivered via KCP")
    void test5PercentPacketLoss_allPacketsDelivered() throws IOException, InterruptedException {
        // Drop every 20th packet = 5% loss
        replaceModuleListener(peerB, 20);

        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        socketA.clear();
        socketB.clear();

        sleep(300); // Wait for KCP transport to fully initialize

        // Send 100 packets A → B
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("loss5-A-" + i);
        }

        sleep(DATA_WAIT_MS); // 5% loss needs time for KCP retransmissions

        // Verify: ALL 100 packets should be delivered
        int receivedCount = socketB.getReceivedBytes().size();
        System.out.println("=== 5% LOSS TEST ===");
        System.out.println("Sent: " + NUM_PACKETS);
        System.out.println("Received: " + receivedCount);

        // Check which specific packets are missing
        java.util.Set<String> received = new java.util.HashSet<>();
        for (byte[] bytes : socketB.getReceivedBytes()) {
            received.add(new String(bytes, StandardCharsets.UTF_8));
        }

        java.util.List<String> missing = new java.util.ArrayList<>();
        for (int i = 0; i < NUM_PACKETS; i++) {
            String expected = "loss5-A-" + i;
            if (!received.contains(expected)) {
                missing.add(expected);
            }
        }

        if (!missing.isEmpty()) {
            System.out.println("MISSING packets: " + missing);
        } else {
            System.out.println("ALL packets delivered successfully!");
        }

        assertEquals(NUM_PACKETS, receivedCount,
                "socketB should receive " + NUM_PACKETS + " packets from A with 5% loss, got: " + receivedCount);

        for (int i = 0; i < NUM_PACKETS; i++) {
            String expected = "loss5-A-" + i;
            assertTrue(received.contains(expected),
                    "Missing packet: " + expected);
        }
    }

    // =========================================================================
    // Test 2: 10% packet loss — 100/100 should be delivered
    // =========================================================================
    @Test
    @Timeout(value = 120)
    @DisplayName("10% packet loss: all packets should be delivered via KCP")
    void test10PercentPacketLoss_allPacketsDelivered() throws IOException, InterruptedException {
        // Drop every 10th packet = 10% loss
        replaceModuleListener(peerB, 10);

        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        socketA.clear();
        socketB.clear();

        sleep(300);

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("loss10-A-" + i);
        }

        sleep(DATA_WAIT_MS);

        int receivedCount = socketB.getReceivedBytes().size();
        System.out.println("=== 10% LOSS TEST ===");
        System.out.println("Sent: " + NUM_PACKETS);
        System.out.println("Received: " + receivedCount);

        java.util.Set<String> received = new java.util.HashSet<>();
        for (byte[] bytes : socketB.getReceivedBytes()) {
            received.add(new String(bytes, StandardCharsets.UTF_8));
        }

        java.util.List<String> missing = new java.util.ArrayList<>();
        for (int i = 0; i < NUM_PACKETS; i++) {
            String expected = "loss10-A-" + i;
            if (!received.contains(expected)) {
                missing.add(expected);
            }
        }

        assertEquals(NUM_PACKETS, receivedCount,
                "socketB should receive " + NUM_PACKETS + " packets from A with 10% loss, got: " + receivedCount);
    }

    // =========================================================================
    // Test 3: 20% packet loss — 100/100 should be delivered
    // =========================================================================
    @Test
    @Timeout(value = 120)
    @DisplayName("20% packet loss: all packets should be delivered via KCP")
    void test20PercentPacketLoss_allPacketsDelivered() throws IOException, InterruptedException {
        // Drop every 5th packet = 20% loss
        replaceModuleListener(peerB, 5);

        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        socketA.clear();
        socketB.clear();

        sleep(300);

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("loss20-A-" + i);
        }

        sleep(DATA_WAIT_MS);

        int receivedCount = socketB.getReceivedBytes().size();
        System.out.println("=== 20% LOSS TEST ===");
        System.out.println("Sent: " + NUM_PACKETS);
        System.out.println("Received: " + receivedCount);

        assertEquals(NUM_PACKETS, receivedCount,
                "socketB should receive " + NUM_PACKETS + " packets from A with 20% loss, got: " + receivedCount);
    }

    // =========================================================================
    // Test 4: Bidirectional 5% packet loss
    // =========================================================================
    @Test
    @Timeout(value = 120)
    @DisplayName("Bidirectional 5% packet loss: all packets in both directions")
    void testBidirectional5PercentPacketLoss() throws IOException, InterruptedException {
        // Drop packets in BOTH directions
        replaceModuleListener(peerA, 20);
        replaceModuleListener(peerB, 20);

        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        socketA.clear();
        socketB.clear();

        sleep(300);

        // Send A → B
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("bidir-A-" + i);
        }
        // Send B → A
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketB.sendString("bidir-B-" + i);
        }

        sleep(DATA_WAIT_MS);

        int receivedA = socketA.getReceivedBytes().size();
        int receivedB = socketB.getReceivedBytes().size();

        System.out.println("=== BIDIRECTIONAL 5% LOSS TEST ===");
        System.out.println("A received: " + receivedA + " (expected " + NUM_PACKETS + ")");
        System.out.println("B received: " + receivedB + " (expected " + NUM_PACKETS + ")");

        assertEquals(NUM_PACKETS, receivedA,
                "socketA should receive " + NUM_PACKETS + " packets from B with 5% loss");
        assertEquals(NUM_PACKETS, receivedB,
                "socketB should receive " + NUM_PACKETS + " packets from A with 5% loss");
    }

    // =========================================================================
    // Test 5: Verify retransmissions actually happen (debug test)
    // =========================================================================
    @Test
    @Timeout(value = 60)
    @DisplayName("Debug: verify packets are recovered at 5% loss")
    void testDebugRetransmissionsHappen() throws IOException, InterruptedException {
        replaceModuleListener(peerB, 20);

        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        socketA.clear();
        socketB.clear();

        sleep(300);

        // Send only 20 packets — this will drop exactly 1 packet (seq=19)
        for (int i = 0; i < 20; i++) {
            socketA.sendString("debug-" + i);
        }

        sleep(DATA_WAIT_MS);

        int receivedCount = socketB.getReceivedBytes().size();
        System.out.println("=== DEBUG 20 PACKETS ===");
        System.out.println("Sent: 20");
        System.out.println("Received: " + receivedCount);

        for (int i = 0; i < receivedCount; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            System.out.println("  [" + i + "] " + received);
        }

        // With 5% loss (every 20th), one packet should be dropped and recovered
        // Result: 20/20 should arrive
        assertEquals(20, receivedCount,
                "All 20 packets should be delivered via retransmission");
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Replace PEER_LISTENER_MODULE with DroppingPeerToPeerListenerModule.
     */
    private void replaceModuleListener(Peer peer, int dropEveryNPackets) {
        PeerToPeerListenerModule droppingListener = dropEveryNPackets > 0
                ? new DroppingPeerToPeerListenerModule(peer, dropEveryNPackets)
                : new PeerToPeerListenerModule(peer);
        droppingListener.init();

        peer.getModules().put(PeerModule.PEER_LISTENER_MODULE, droppingListener);

        if (peer.getComponent() != null) {
            droppingListener.onIceComponentChange(peer, peer.getComponent());
        }
    }
}
