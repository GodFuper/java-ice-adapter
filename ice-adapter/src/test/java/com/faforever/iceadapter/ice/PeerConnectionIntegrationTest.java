package com.faforever.iceadapter.ice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for peer connection over InMemory sockets.
 */
@DisplayName("Peer Connection Integration")
class PeerConnectionIntegrationTest extends PeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 10;
    private static final long DATA_WAIT_MS = 3_000;

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("Two peers should connect and exchange data through real modules")
    void testTwoPeersConnectAndExchangeData() throws IOException {
        sleep(100); // Give modules time to settle

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("message-from-A-%d".formatted(i));
        }

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketB.sendString("message-from-B-%d".formatted(i));
        }

        // Wait for delivery
        sleep(DATA_WAIT_MS);
        // Extra wait for network propagation
        sleep(1000);

        List<byte[]> receivedAtSocketB = socketB.getReceivedBytes();
        assertEquals(
                NUM_PACKETS,
                receivedAtSocketB.size(),
                "socketB should receive " + NUM_PACKETS + " packets from A (sent via peerA), got: "
                        + receivedAtSocketB.size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(receivedAtSocketB.get(i), StandardCharsets.UTF_8);
            assertEquals("message-from-A-" + i, received, "Packet " + i + " content mismatch from A->B");
        }

        List<byte[]> receivedAtSocketA = socketA.getReceivedBytes();
        assertEquals(
                NUM_PACKETS,
                receivedAtSocketA.size(),
                "socketA should receive " + NUM_PACKETS + " packets from B (sent via peerB), got: "
                        + receivedAtSocketA.size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(receivedAtSocketA.get(i), StandardCharsets.UTF_8);
            assertEquals("message-from-B-" + i, received, "Packet " + i + " content mismatch from B->A");
        }
    }
}
