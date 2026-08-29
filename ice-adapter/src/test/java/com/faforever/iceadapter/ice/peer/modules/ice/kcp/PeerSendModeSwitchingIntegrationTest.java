package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.PeerConnectionIntegrationBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.PeerSendMode;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for Peer.sendMode switching between DIRECT_ONLY and KCP_ONLY.
 *
 * <p>Verifies that both peers can successfully communicate via direct UDP transport,
 * then switch to KCP transport and continue exchanging data without issues.
 */
@Slf4j
@DisplayName("Peer — SendMode switching")
public class PeerSendModeSwitchingIntegrationTest extends PeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 100;

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

    @Test
    @Timeout(value = 60)
    @DisplayName("Send 100 packets via DIRECT_ONLY then switch to KCP_ONLY and send another 100")
    void testSendModeSwitchFromDirectOnlyToKcpOnly() throws IOException {
        addPeerModuleListener(peerA, new PeerToPeerListenerModule(peerA));
        addPeerModuleListener(peerB, new PeerToPeerListenerModule(peerB));

        // Phase 1: DIRECT_ONLY
        peerA.setSendMode(PeerSendMode.DIRECT_ONLY);
        peerB.setSendMode(PeerSendMode.DIRECT_ONLY);

        socketA.clear();
        socketB.clear();

        sleep(300); // Wait for transport to fully initialize

        long startTime = System.nanoTime();

        // Both peers send 100 packets to each other via DIRECT_ONLY
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("direct-A-" + i);
            socketB.sendString("direct-B-" + i);
        }

        // Wait until all packets are delivered (A→B and B→A)
        waitForDelivery(peerA, peerB, NUM_PACKETS, NUM_PACKETS);

        long directDeliveryTimeMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("DIRECT_ONLY: Delivered {} packets (A→B) and {} packets (B→A) in {} ms",
                socketB.getReceivedBytes().size(),
                socketA.getReceivedBytes().size(),
                directDeliveryTimeMs);

        // Verify A → B
        int receivedAB = socketB.getReceivedBytes().size();
        assertEquals(NUM_PACKETS, receivedAB,
                "socketB should receive all " + NUM_PACKETS + " packets from A (DIRECT_ONLY), got: " + receivedAB);
        for (int i = 0; i < receivedAB; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertTrue(received.startsWith("direct-A-"),
                    "Packet " + i + " A→B should start with direct-A-, actual=" + received);
        }

        // Verify B → A
        int receivedBA = socketA.getReceivedBytes().size();
        assertEquals(NUM_PACKETS, receivedBA,
                "socketA should receive all " + NUM_PACKETS + " packets from B (DIRECT_ONLY), got: " + receivedBA);
        for (int i = 0; i < receivedBA; i++) {
            String received = new String(socketA.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertTrue(received.startsWith("direct-B-"),
                    "Packet " + i + " B→A should start with direct-B-, actual=" + received);
        }

        // Phase 2: Switch to KCP_ONLY
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        socketA.clear();
        socketB.clear();

        sleep(500); // Wait for KCP transport to fully initialize

        startTime = System.nanoTime();

        // Both peers send 100 packets to each other via KCP_ONLY
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("kcp-A-" + i);
            socketB.sendString("kcp-B-" + i);
        }

        // Wait until all packets are delivered (A→B and B→A)
        waitForDelivery(peerA, peerB, NUM_PACKETS, NUM_PACKETS);

        long kcpDeliveryTimeMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("KCP_ONLY: Delivered {} packets (A→B) and {} packets (B→A) in {} ms",
                socketB.getReceivedBytes().size(),
                socketA.getReceivedBytes().size(),
                kcpDeliveryTimeMs);

        // Verify A → B via KCP
        receivedAB = socketB.getReceivedBytes().size();
        assertEquals(NUM_PACKETS, receivedAB,
                "socketB should receive all " + NUM_PACKETS + " packets from A (KCP_ONLY), got: " + receivedAB);
        for (int i = 0; i < receivedAB; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertTrue(received.startsWith("kcp-A-"),
                    "Packet " + i + " A→B should start with kcp-A-, actual=" + received);
        }

        // Verify B → A via KCP
        receivedBA = socketA.getReceivedBytes().size();
        assertEquals(NUM_PACKETS, receivedBA,
                "socketA should receive all " + NUM_PACKETS + " packets from B (KCP_ONLY), got: " + receivedBA);
        for (int i = 0; i < receivedBA; i++) {
            String received = new String(socketA.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertTrue(received.startsWith("kcp-B-"),
                    "Packet " + i + " B→A should start with kcp-B-, actual=" + received);
        }
    }

    private void waitForDelivery(Peer peerA, Peer peerB, int expectedA, int expectedB) {
        long waitStart = System.nanoTime();
        long timeoutNanos = 50_000_000_000L; // 50s

        while ((System.nanoTime() - waitStart) < timeoutNanos) {
            int receivedA = socketA.getReceivedBytes().size();
            int receivedB = socketB.getReceivedBytes().size();
            if (receivedA >= expectedA && receivedB >= expectedB) {
                return;
            }
            sleep(100);
        }

        throw new AssertionError("Timeout waiting for " + expectedA + " packets to A and " + expectedB +
                " packets to B (received A=" + socketA.getReceivedBytes().size() +
                ", B=" + socketB.getReceivedBytes().size() + ")");
    }

    private <T extends ModuleBase> T addPeerModuleListener(Peer peer, T module) {
        PeerModule typeModule = PeerModule.PEER_LISTENER_MODULE;
        module.init();

        // Replace in modules map
        peer.getModules().put(typeModule, module);

        // Notify about component change so the listener thread starts
        if (peer.getComponent() != null && module instanceof PeerEventListener listener) {
            listener.onIceComponentChange(peer, peer.getComponent());
        }
        return module;
    }
}
