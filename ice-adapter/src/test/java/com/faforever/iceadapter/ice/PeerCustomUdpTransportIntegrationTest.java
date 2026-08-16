package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies packet delivery through both normal UDP and custom reliable UDP transport.
 *
 * <p>Tests two scenarios:
 * <ol>
 *   <li>Normal mode (customReliableUdp = false) — standard ice4j UDP path</li>
 *   <li>Custom mode (customReliableUdp = true) — ReliableUdpTransport overlay with header, ACKs, retransmissions</li>
 * </ol>
 *
 * <p>In both cases data flows: socket → ice4j Component → Peer listener → ... → Peer sender → ice4j Component → socket.
 * In custom mode, PeerToPeerSenderModule and RelayPeerToPeerSenderModule return early
 * (they check {@code peer.isCustomUdpTransport()}), and CustomUdpTransportSenderModule takes over
 * by wrapping payloads with a {@link PacketHeader}
 * and managing a reliable send window.
 */
@DisplayName("Custom UDP Transport Integration")
class PeerCustomUdpTransportIntegrationTest extends PeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 10;
    private static final long DATA_WAIT_MS = 3_000;

    @Override
    protected Set<PeerModule> getDisabledModules() {
        return Set.of(
                PeerModule.CONNECTION_CHECKER_MODULE,
                PeerModule.PEER_TURN_REFRESHER_MODULE,
                PeerModule.RELAY_CLIENT_MODULE,
                PeerModule.RELAY_SERVER_MODULE,
                PeerModule.AUTO_RELAY_CALCULATE_RTT);
    }

    @Test
    @Timeout(value = 30)
    @DisplayName("Packets should be delivered in normal mode (customReliableUdp = false)")
    void testNormalMode() throws IOException {
        // Verify default mode
        assertFalse(peerA.isCustomUdpTransport(), "Peer A should start in normal mode");
        assertFalse(peerB.isCustomUdpTransport(), "Peer B should start in normal mode");

        socketA.clear();
        socketB.clear();

        // Send packets A → B
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("normal-A-" + i);
        }
        // Send packets B → A
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketB.sendString("normal-B-" + i);
        }

        sleep(DATA_WAIT_MS);

        // Verify A → B
        assertEquals(
                NUM_PACKETS,
                socketB.getReceivedBytes().size(),
                "socketB should receive " + NUM_PACKETS + " packets from A in normal mode, got: "
                        + socketB.getReceivedBytes().size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            String msg = "Packet " + i + " A→B in normal mode: expected=normal-A-" + i + " actual=" + received;
            assertEquals("normal-A-" + i, received, msg);
        }

        // Verify B → A
        assertEquals(
                NUM_PACKETS,
                socketA.getReceivedBytes().size(),
                "socketA should receive " + NUM_PACKETS + " packets from B in normal mode, got: "
                        + socketA.getReceivedBytes().size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketA.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            String msg = "Packet " + i + " B→A in normal mode: expected=normal-B-" + i + " actual=" + received;
            assertEquals("normal-B-" + i, received, msg);
        }
    }

    @Test
    @Timeout(value = 30)
    void testCustomMode() throws IOException {
        peerA.setCustomUdpTransport(true);
        peerB.setCustomUdpTransport(true);

        // Verify custom mode is enabled
        assertTrue(peerA.isCustomUdpTransport(), "Peer A should be in custom UDP mode");
        assertTrue(peerB.isCustomUdpTransport(), "Peer B should be in custom UDP mode");

        socketA.clear();
        socketB.clear();

        sleep(300); // Wait for ReliableUdpTransport to fully initialize

        // Send packets A → B through custom transport
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("custom-A-" + i);
        }
        // Send packets B → A through custom transport
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketB.sendString("custom-B-" + i);
        }

        sleep(DATA_WAIT_MS);

        // Verify A → B
        assertEquals(
                NUM_PACKETS,
                socketB.getReceivedBytes().size(),
                "socketB should receive " + NUM_PACKETS + " packets from A in custom UDP mode, got: "
                        + socketB.getReceivedBytes().size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            String msg = "Packet " + i + " A→B in custom mode: expected=custom-A-" + i + " actual=" + received;
            assertEquals("custom-A-" + i, received, msg);
        }

        // Verify B → A
        assertEquals(
                NUM_PACKETS,
                socketA.getReceivedBytes().size(),
                "socketA should receive " + NUM_PACKETS + " packets from B in custom UDP mode, got: "
                        + socketA.getReceivedBytes().size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketA.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            String msg = "Packet " + i + " B→A in custom mode: expected=custom-B-" + i + " actual=" + received;
            assertEquals("custom-B-" + i, received, msg);
        }
    }
}
