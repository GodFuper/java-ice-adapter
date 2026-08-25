package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.PeerSendMode;
import com.faforever.iceadapter.ice.peer.modules.ice.PeerToPeerListenerModule;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Integration tests for {@link com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule}.
 * <p>
 * All tests inherit from {@link KcpPeerToPeerIntegrationBase} where PEER_LISTENER_MODULE
 * is disabled by default — each test injects its own listener.
 * </p>
 */
@Slf4j
@DisplayName("KcpPeerToPeerSenderModule Integration")
class KcpPeerToPeerSenderModuleTest extends KcpPeerToPeerIntegrationBase {

    @Test
    @Timeout(value = 30)
    @DisplayName("Packets from A should be delivered to B through KcpPeerToPeerSenderModule")
    void testSendPacketsFromAtoB() throws Exception {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        sleep(KCP_INIT_DELAY_MS); // Wait for component to be ready before injecting listener

        PeerToPeerListenerModule listenerA = new PeerToPeerListenerModule(peerA);
        PeerToPeerListenerModule listenerB = new PeerToPeerListenerModule(peerB);
        injectListenerModule(peerA, listenerA);
        injectListenerModule(peerB, listenerB);

        socketA.clear();
        socketB.clear();

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("kcp-a-to-b-" + i);
        }

        waitForReceived(socketB, NUM_PACKETS, DATA_TIMEOUT_MS);

        assertEquals(NUM_PACKETS, socketB.getReceivedBytes().size(),
                "Expected " + NUM_PACKETS + " packets at B, got: " + socketB.getReceivedBytes().size());

        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("kcp-a-to-b-" + i, received, "Packet " + i + " payload mismatch");
        }
    }

    @Test
    @Timeout(value = 30)
    @DisplayName("Packets from B should be delivered to A through KcpPeerToPeerSenderModule")
    void testSendPacketsFromBtoA() throws Exception {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        sleep(KCP_INIT_DELAY_MS); // Wait for component to be ready before injecting listener

        injectListenerModule(peerA, new PeerToPeerListenerModule(peerA));
        injectListenerModule(peerB, new PeerToPeerListenerModule(peerB));

        socketA.clear();
        socketB.clear();

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketB.sendString("kcp-b-to-a-" + i);
        }

        waitForReceived(socketA, NUM_PACKETS, DATA_TIMEOUT_MS);

        assertEquals(NUM_PACKETS, socketA.getReceivedBytes().size(),
                "Expected " + NUM_PACKETS + " packets at A, got: " + socketA.getReceivedBytes().size());

        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketA.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("kcp-b-to-a-" + i, received, "Packet " + i + " payload mismatch");
        }
    }

    @Test
    @Timeout(value = 30)
    @DisplayName("Packets should be delivered in both directions simultaneously")
    void testBidirectionalPackets() throws Exception {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        sleep(KCP_INIT_DELAY_MS); // Wait for component to be ready before injecting listener

        injectListenerModule(peerA, new PeerToPeerListenerModule(peerA));
        injectListenerModule(peerB, new PeerToPeerListenerModule(peerB));

        socketA.clear();
        socketB.clear();

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("bidir-a-to-b-" + i);
            socketB.sendString("bidir-b-to-a-" + i);
        }

        waitForReceived(socketB, NUM_PACKETS, DATA_TIMEOUT_MS);
        waitForReceived(socketA, NUM_PACKETS, DATA_TIMEOUT_MS);

        assertEquals(NUM_PACKETS, socketB.getReceivedBytes().size(),
                "Expected " + NUM_PACKETS + " packets at B, got: " + socketB.getReceivedBytes().size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("bidir-a-to-b-" + i, received, "Packet " + i + " A→B mismatch");
        }

        assertEquals(NUM_PACKETS, socketA.getReceivedBytes().size(),
                "Expected " + NUM_PACKETS + " packets at A, got: " + socketA.getReceivedBytes().size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(socketA.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("bidir-b-to-a-" + i, received, "Packet " + i + " B→A mismatch");
        }
    }

    @Test
    @Timeout(value = 30)
    @DisplayName("Large payloads should be delivered correctly through KCP")
    void testLargePayloads() throws Exception {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        sleep(KCP_INIT_DELAY_MS); // Wait for component to be ready before injecting listener

        injectListenerModule(peerA, new PeerToPeerListenerModule(peerA));
        injectListenerModule(peerB, new PeerToPeerListenerModule(peerB));

        socketA.clear();
        socketB.clear();

        String largePayload = "X".repeat(1000);
        socketA.sendString(largePayload);

        waitForReceived(socketB, 1, DATA_TIMEOUT_MS);

        assertEquals(1, socketB.getReceivedBytes().size(), "Expected 1 large packet at B");
        String received = new String(socketB.getReceivedBytes().get(0), StandardCharsets.UTF_8);
        assertEquals(largePayload, received, "Large payload mismatch");
    }

    @Test
    @Timeout(value = 30)
    @DisplayName("Multiple batches of packets should maintain order")
    void testMultipleBatches() throws Exception {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        sleep(KCP_INIT_DELAY_MS); // Wait for component to be ready before injecting listener

        injectListenerModule(peerA, new PeerToPeerListenerModule(peerA));
        injectListenerModule(peerB, new PeerToPeerListenerModule(peerB));

        socketA.clear();
        socketB.clear();

        for (int i = 0; i < 5; i++) {
            socketA.sendString("batch1-" + i);
        }
        waitForReceived(socketB, 5, DATA_TIMEOUT_MS);

        assertEquals(5, socketB.getReceivedBytes().size(), "First batch: expected 5 packets at B");
        for (int i = 0; i < 5; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("batch1-" + i, received, "Batch 1 packet " + i + " mismatch");
        }

        for (int i = 0; i < 5; i++) {
            socketA.sendString("batch2-" + i);
        }
        waitForReceived(socketB, 10, DATA_TIMEOUT_MS);

        assertEquals(10, socketB.getReceivedBytes().size(), "Second batch: expected 10 packets at B");
        for (int i = 5; i < 10; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("batch2-" + (i - 5), received, "Batch 2 packet " + (i - 5) + " mismatch");
        }
    }

    @Test
    @Timeout(value = 30)
    @DisplayName("Different payload content should arrive intact")
    void testPayloadIntegrity() throws Exception {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        sleep(KCP_INIT_DELAY_MS); // Wait for component to be ready before injecting listener

        injectListenerModule(peerA, new PeerToPeerListenerModule(peerA));
        injectListenerModule(peerB, new PeerToPeerListenerModule(peerB));

        socketA.clear();
        socketB.clear();

        String[] payloads = {
                "simple",
                "with-utf8-ёж-数据",
                new String(new byte[]{0, 1, 2, 'a', 'b', 'c'}),
                "long-".concat("A".repeat(500)),
                "short-a",
                "special!@#$%^&*()"
        };

        for (String payload : payloads) {
            socketA.sendString(payload);
        }

        waitForReceived(socketB, payloads.length, DATA_TIMEOUT_MS);

        assertEquals(payloads.length, socketB.getReceivedBytes().size(),
                "Expected " + payloads.length + " packets, got: " + socketB.getReceivedBytes().size());

        for (int i = 0; i < payloads.length; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals(payloads[i], received, "Payload " + i + " integrity mismatch");
        }
    }

    @Test
    @Timeout(value = 30)
    @DisplayName("HandleData should correctly strip KCP marker and deliver data")
    void testHandleDataStripsKcpMarker() throws Exception {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        sleep(KCP_INIT_DELAY_MS); // Wait for component to be ready before injecting listener

        injectListenerModule(peerA, new PeerToPeerListenerModule(peerA));
        injectListenerModule(peerB, new PeerToPeerListenerModule(peerB));

        socketA.clear();
        socketB.clear();

        String[] messages = {"marker-test-1", "marker-test-2", "marker-test-3"};
        for (String msg : messages) {
            socketA.sendString(msg);
        }

        waitForReceived(socketB, messages.length, DATA_TIMEOUT_MS);

        assertEquals(messages.length, socketB.getReceivedBytes().size(),
                "Expected " + messages.length + " packets at B");

        for (int i = 0; i < messages.length; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals(messages[i], received, "Packet " + i + " should not contain KCP marker");
            assertNotEquals('u', received.charAt(0),
                    "Packet " + i + " still contains KCP protocol marker");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20, 30, 40, 50, 60, 70})
    @Timeout(value = 45)
    @DisplayName("KCP must deliver all packets under random packet loss (drop: {0}%)")
    void testRandomPacketLossDeliversAllPackets(int dropChance) throws Exception {
        peerA.setSendMode(PeerSendMode.KCP_ONLY);
        peerB.setSendMode(PeerSendMode.KCP_ONLY);

        sleep(KCP_INIT_DELAY_MS); // Wait for component to be ready before injecting listener

        injectListenerModule(peerA, new PeerToPeerListenerModule(peerA));
        injectListenerModule(peerB, new RandomDroppingPeerToPeerListenerModule(peerB, dropChance));

        socketA.clear();
        socketB.clear();

        int packetCount = 20;
        for (int i = 0; i < packetCount; i++) {
            socketA.sendString("lossy-" + i);
        }

        waitForReceived(socketB, packetCount, 30_000);

        log.info("[Test] Random loss {}%: received {} packets", dropChance, socketB.getReceivedBytes().size());

        assertEquals(packetCount, socketB.getReceivedBytes().size(),
                "Expected " + packetCount + " packets at B despite " + dropChance + "% random loss, got: "
                        + socketB.getReceivedBytes().size());

        for (int i = 0; i < packetCount; i++) {
            String received = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("lossy-" + i, received, "Packet " + i + " payload mismatch under " + dropChance + "% loss");
        }
    }
}
