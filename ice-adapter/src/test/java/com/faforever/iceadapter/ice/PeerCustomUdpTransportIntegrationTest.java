package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.PeerSendMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test that verifies packet delivery through both normal UDP and KCP transport.
 *
 * <p>Tests two scenarios:
 * <ol>
 *   <li>Normal mode (kcpUdp = false) — standard ice4j UDP path</li>
 *   <li>KCP mode (kcpUdp = true) — KCP overlay with reliable delivery, retransmissions</li>
 * </ol>
 *
 * <p>In both cases data flows: socket → ice4j Component → Peer listener → ... → Peer sender → ice4j Component → socket.
 * In KCP mode, PeerToPeerSenderModule and RelayPeerToPeerSenderModule return early
 * (they check {@code peer.isKcpUdpTransport()}), and KcpPeerToPeerSenderModule takes over
 * by sending data through the KCP protocol.
 */
@DisplayName("Custom UDP Transport Integration (KCP)")
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
    @DisplayName("Packets should be delivered in normal mode (kcpUdp = false)")
    void testNormalMode() throws IOException {
        // Verify default mode
        assertEquals(PeerSendMode.DIRECT_ONLY, peerA.getSendMode(), "Peer A should start in DIRECT_ONLY mode");
        assertEquals(PeerSendMode.DIRECT_ONLY, peerB.getSendMode(), "Peer B should start in DIRECT_ONLY mode");

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
    void testKcpMode() throws IOException {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        // Verify KCP mode is enabled
        assertEquals(PeerSendMode.KCP_ONLY, peerA.getSendMode(), "Peer A should be in KCP_ONLY mode");
        assertEquals(PeerSendMode.KCP_ONLY, peerB.getSendMode(), "Peer B should be in KCP_ONLY mode");

        socketA.clear();
        socketB.clear();

        sleep(300); // Wait for KCP transport to fully initialize

        // Send packets A → B through KCP transport
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("kcp-A-" + i);
        }
        // Send packets B → A through KCP transport
        for (int i = 0; i < NUM_PACKETS; i++) {
            socketB.sendString("kcp-B-" + i);
        }

        sleep(DATA_WAIT_MS);

        // Verify A → B
        assertEquals(
                NUM_PACKETS,
                socketB.getReceivedBytes().size(),
                "socketB should receive " + NUM_PACKETS + " packets from A in KCP mode, got: "
                        + socketB.getReceivedBytes().size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            String msg = "Packet " + i + " A→B in KCP mode: expected=kcp-A-" + i + " actual=" + received;
            assertEquals("kcp-A-" + i, received, msg);
        }

        // Verify B → A
        assertEquals(
                NUM_PACKETS,
                socketA.getReceivedBytes().size(),
                "socketA should receive " + NUM_PACKETS + " packets from B in KCP mode, got: "
                        + socketA.getReceivedBytes().size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketA.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            String msg = "Packet " + i + " B→A in KCP mode: expected=kcp-B-" + i + " actual=" + received;
            assertEquals("kcp-B-" + i, received, msg);
        }
    }
}
