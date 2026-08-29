package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.DroppingPeerToPeerListenerModule;
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
 * Integration tests for KCP transport under packet loss conditions.
 *
 * <p>These tests verify that KCP's built-in reliable transport mechanism correctly
 * recovers from simulated packet loss on the wire. A custom {@link DroppingPeerToPeerListenerModule}
 * is injected to drop packets at peerB's receive path, simulating network degradation.
 *
 * <p>KCP should retransmit dropped packets, ensuring eventual delivery of all data from peerA to peerB.
 */
@Slf4j
@DisplayName("KCP Transport — Packet Loss Scenarios")
public class PeerKcpTransportLostPacketsIntegrationTest extends PeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 100;

    /**
     * If set, replaces PEER_LISTENER_MODULE for peerB with a dropping listener.
     * The value N means every Nth packet is dropped (e.g., 5 = 20% loss, 2 = 50% loss).
     */
    protected Integer dropEveryNPacketsForPeerB = null;

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
    @Timeout(value = 30)
    @DisplayName("KCP mode with 20% packet loss (every 5th packet dropped)")
    void testCustomModeWith20PercentPacketLoss() throws IOException {
        runPacketLossTest(5, "loss20-A-");
    }

    @Test
    @Timeout(value = 30)
    @DisplayName("KCP mode with 30% packet loss (every 3rd packet dropped)")
    void testCustomModeWith30PercentPacketLoss() throws IOException {
        runPacketLossTest(3, "loss30-A-");
    }

    private void runPacketLossTest(int dropEveryN, String packetPrefix) throws IOException {
        dropEveryNPacketsForPeerB = dropEveryN;
        addPeerModuleListener(peerA, new PeerToPeerListenerModule(peerA));
        addPeerModuleListener(peerB, new DroppingPeerToPeerListenerModule(peerB, dropEveryNPacketsForPeerB));

        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        socketA.clear();
        socketB.clear();

        sleep(300); // Wait for KCP transport to fully initialize

        long startTime = System.nanoTime();

        // Send packets A → B through KCP transport
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString(packetPrefix + i);
        }

        // Wait until all packets are delivered
        waitForDelivery(NUM_PACKETS);

        long deliveryTimeMs = (System.nanoTime() - startTime) / 1_000_000;

        log.info("Delivered {} packets in {} ms", NUM_PACKETS, deliveryTimeMs);

        // Verify A → B — KCP must deliver all packets even with packet loss via retransmissions
        int receivedCount = socketB.getReceivedBytes().size();
        assertEquals(NUM_PACKETS, receivedCount,
                "socketB should receive all " + NUM_PACKETS + " packets from A, got: "
                        + receivedCount);
        for (int i = 0; i < receivedCount; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertTrue(
                    received.startsWith(packetPrefix),
                    "Packet " + i + " A→B should start with " + packetPrefix + ", actual=" + received);
        }
    }

    private void waitForDelivery(int expectedCount) {
        long waitStart = System.nanoTime();
        long timeoutNanos = 25_000_000_000L; // 25s (below @Timeout)

        while ((System.nanoTime() - waitStart) < timeoutNanos) {
            int received = socketB.getReceivedBytes().size();
            if (received >= expectedCount) {
                return;
            }
            sleep(100);
        }

        throw new AssertionError("Timeout waiting for " + expectedCount + " packets (received " +
                socketB.getReceivedBytes().size() + ")");
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
