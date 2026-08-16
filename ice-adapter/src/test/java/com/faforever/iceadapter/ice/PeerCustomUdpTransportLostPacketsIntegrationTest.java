package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.PeerModule;
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

@Slf4j
@DisplayName("Custom UDP Transport Integration")
class PeerCustomUdpTransportLostPacketsIntegrationTest extends PeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 100;
    private static final long DATA_WAIT_MS = 3_000;

    /**
     * If set, replaces PEER_LISTENER_MODULE for peerB with a dropping listener.
     * The value N means every Nth packet is dropped (e.g., 5 = 20% loss).
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
    @DisplayName("Custom mode with 20% packet loss (every 5th packet dropped)")
    void testCustomModeWith20PercentPacketLoss() throws IOException {
        dropEveryNPacketsForPeerB = 5;
        addPeerModuleListener(peerA, new PeerToPeerListenerModule(peerA));
        DroppingPeerToPeerListenerModule dropModule = addPeerModuleListener(peerB, new DroppingPeerToPeerListenerModule(peerB, dropEveryNPacketsForPeerB));

        peerA.setCustomUdpTransport(true);
        peerB.setCustomUdpTransport(true);

        socketA.clear();
        socketB.clear();

        sleep(300); // Wait for ReliableUdpTransport to fully initialize

        // Send packets A → B through custom transport
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("loss20-A-" + i);
        }

        sleep(DATA_WAIT_MS * 2);  // 20% loss needs more time for retransmissions

        int dropsPackets = NUM_PACKETS / dropEveryNPacketsForPeerB;
        int successPackets = NUM_PACKETS - dropsPackets;
        int retransmissionsPackets = dropsPackets;

        int actualLostPackets = dropModule.getLostSequence().size();
        int actualSuccessPackets = dropModule.getSuccessSequence().size();
        int actualRetransmittedPackets = dropModule.getRetransmissionSequence().size();

        log.info("Drops packets {}: {}", actualLostPackets, dropModule.getLostSequence());
        log.info("Success packets {}: {}", actualSuccessPackets, dropModule.getSuccessSequence());
        log.info("Retransmitted packets {}: {}", actualRetransmittedPackets, dropModule.getRetransmissionSequence());

        assertEquals(dropsPackets, actualLostPackets, "The number of lost packets does not match, %d != %d".formatted(dropsPackets, actualLostPackets));
        assertEquals(successPackets, actualSuccessPackets, "The number of success packets does not match, %d != %d".formatted(successPackets, actualSuccessPackets));
        assertEquals(retransmissionsPackets, actualRetransmittedPackets, "The number of retransmitted packets does not match, %d != %d".formatted(retransmissionsPackets, actualRetransmittedPackets));

        // Verify A → B (most packets should be delivered via retransmissions;
        // with 20% drop rate, expect at least 80% delivery)
        int expectedMin20 = (int) (NUM_PACKETS * 0.8);
        int receivedCount20 = socketB.getReceivedBytes().size();
        assertTrue(
                receivedCount20 >= expectedMin20,
                "socketB should receive at least " + expectedMin20 + " packets from A with 20% loss, got: "
                        + receivedCount20);
        for (int i = 0; i < receivedCount20; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertTrue(
                    received.startsWith("loss20-A-"),
                    "Packet " + i + " A→B with 20% loss should start with loss20-A-, actual=" + received);
        }
    }


    <T extends ModuleBase> T addPeerModuleListener(Peer peer, T module) {
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
