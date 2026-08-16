package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.receive.ReceiveWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReceiveWindowTest {

    private ReceiveWindow receiveWindow;

    @BeforeEach
    void setUp() {
        receiveWindow = new ReceiveWindow();
    }

    @Test
    void testDeliverUnordered() {
        byte[] payload = new byte[]{1, 2, 3};
        ReceiveWindow.PacketResult result = receiveWindow.process(100, payload, Reliability.UNRELIABLE);

        assertTrue(result.delivered());
        assertArrayEquals(payload, result.payload());
        assertTrue(result.missingSeqs().length == 0);
    }

    @Test
    void testDeliverOrderedWithNack() {
        byte[] payload = new byte[]{1};

        // Fill the window up to seq=1
        receiveWindow.process(1, payload, Reliability.RELIABLE_ORDERED);

        // Now send 3 out-of-order first (2 is missing)
        ReceiveWindow.PacketResult result1 = receiveWindow.process(3, payload, Reliability.RELIABLE_ORDERED);
        assertFalse(result1.delivered());

        // Send 2 - should deliver and deliver 3 from buffer
        ReceiveWindow.PacketResult result2 = receiveWindow.process(2, payload, Reliability.RELIABLE_ORDERED);
        assertTrue(result2.delivered());

        // No more missing seqs
        assertEquals(0, result2.missingSeqs().length);
    }

    @Test
    void testNackDetectionWithGap() {
        byte[] payload = new byte[]{1};

        // Fill the window up to seq=1
        receiveWindow.process(1, payload, Reliability.RELIABLE_ORDERED);

        // Send 4 first (2 and 3 are missing)
        ReceiveWindow.PacketResult result1 = receiveWindow.process(4, payload, Reliability.RELIABLE_ORDERED);
        assertFalse(result1.delivered());
        // Missing seqs: 2, 3
        assertEquals(2, result1.missingSeqs().length);
        assertEquals(2, result1.missingSeqs()[0]);
        assertEquals(3, result1.missingSeqs()[1]);
    }

    @Test
    void testDuplicateDetection() {
        byte[] payload = new byte[]{1, 2, 3};

        // First packet
        ReceiveWindow.PacketResult result1 = receiveWindow.process(100, payload, Reliability.UNRELIABLE);
        assertTrue(result1.delivered());

        // Duplicate packet
        ReceiveWindow.PacketResult result2 = receiveWindow.process(100, payload, Reliability.UNRELIABLE);
        assertFalse(result2.delivered());
    }

    @Test
    void testDuplicateWithReliable() {
        byte[] payload = new byte[]{1, 2, 3};

        receiveWindow.process(100, payload, Reliability.RELIABLE);

        ReceiveWindow.PacketResult result = receiveWindow.process(100, payload, Reliability.RELIABLE);
        assertFalse(result.delivered());
    }

    @Test
    void testGetNextExpectedSeq() {
        assertEquals(1, receiveWindow.getNextExpectedSeq());

        // Send packet with seq 100 via reliable (unordered)
        byte[] payload = new byte[]{100};
        receiveWindow.process(100, payload, Reliability.RELIABLE);

        // Reliable does not update nextExpectedSeq (only ordered does)
        assertEquals(1, receiveWindow.getNextExpectedSeq());
    }

    @Test
    void testGetLastDeliveredSeqEmpty() {
        assertEquals(0, receiveWindow.getLastDeliveredSeq());
    }

    @Test
    void testGetLastDeliveredSeqWithUnreliable() {
        byte[] payload = new byte[]{1, 2, 3};
        receiveWindow.process(100, payload, Reliability.UNRELIABLE);
        receiveWindow.process(200, payload, Reliability.UNRELIABLE);

        // Should return the highest received sequence
        assertEquals(200, receiveWindow.getLastDeliveredSeq());
    }

    @Test
    void testGetLastDeliveredSeqWithReliable() {
        byte[] payload = new byte[]{1, 2, 3};
        receiveWindow.process(100, payload, Reliability.RELIABLE);
        receiveWindow.process(200, payload, Reliability.RELIABLE);

        assertEquals(200, receiveWindow.getLastDeliveredSeq());
    }

    @Test
    void testGetRecentReceivedSeqs() {
        int[] empty = receiveWindow.getRecentReceivedSeqs(10);
        assertEquals(0, empty.length);

        byte[] payload = new byte[]{1};
        receiveWindow.process(100, payload, Reliability.RELIABLE);
        receiveWindow.process(200, payload, Reliability.RELIABLE);
        receiveWindow.process(300, payload, Reliability.RELIABLE);

        // Get all 3
        int[] recent = receiveWindow.getRecentReceivedSeqs(10);
        assertEquals(3, recent.length);

        // Get only 2
        int[] limited = receiveWindow.getRecentReceivedSeqs(2);
        assertEquals(2, limited.length);
        // Should be the 2 most recent: 200, 300
        assertEquals(200, limited[0]);
        assertEquals(300, limited[1]);
    }

    @Test
    void testGetMissingSeqs_emptyBuffer() {
        int[] missing = receiveWindow.getMissingSeqs();
        assertEquals(0, missing.length);
    }

    @Test
    void testGetMissingSeqs_singleGap() {
        byte[] payload = new byte[]{1};

        // Fill the window up to seq=1
        receiveWindow.process(1, payload, Reliability.RELIABLE_ORDERED);

        // Send 3 first (2 is missing)
        receiveWindow.process(3, payload, Reliability.RELIABLE_ORDERED);

        // Should detect gap: 2 is missing
        int[] missing = receiveWindow.getMissingSeqs();
        assertEquals(1, missing.length);
        assertEquals(2, missing[0]);
    }

    @Test
    void testGetMissingSeqs_multipleGap() {
        byte[] payload = new byte[]{1};

        // Fill the window up to seq=1
        receiveWindow.process(1, payload, Reliability.RELIABLE_ORDERED);

        // Send 6 first (2, 3, 4, 5 are missing)
        receiveWindow.process(6, payload, Reliability.RELIABLE_ORDERED);

        // Should detect gap: 2, 3, 4, 5 are missing
        int[] missing = receiveWindow.getMissingSeqs();
        assertEquals(4, missing.length);
        assertEquals(2, missing[0]);
        assertEquals(3, missing[1]);
        assertEquals(4, missing[2]);
        assertEquals(5, missing[3]);
    }

    @Test
    void testGetMissingSeqs_noGap() {
        // No packets received
        int[] missing = receiveWindow.getMissingSeqs();
        assertEquals(0, missing.length);
    }

    @Test
    void testUnreliableDuplicateCacheDoesNotLeak() {
        // Sending 120 UNRELIABLE packets (DUPLICATE_CACHE_SIZE = 64)
        // should NOT grow receivedSeqs unboundedly
        byte[] payload = new byte[]{1};
        for (int i = 0; i < 120; i++) {
            receiveWindow.process(i, payload, Reliability.UNRELIABLE);
        }

        // Internal list must be bounded, check via getRecentReceivedSeqs
        int[] recent = receiveWindow.getRecentReceivedSeqs(100);
        assertTrue(recent.length <= 64,
                "receivedSeqs should be bounded to DUPLICATE_CACHE_SIZE, got " + recent.length);
    }

    @Test
    void testReliableDuplicateCacheDoesNotLeak() {
        // Same test for RELIABLE (unordered)
        byte[] payload = new byte[]{1};
        for (int i = 0; i < 120; i++) {
            receiveWindow.process(i, payload, Reliability.RELIABLE);
        }

        int[] recent = receiveWindow.getRecentReceivedSeqs(100);
        assertTrue(recent.length <= 64,
                "receivedSeqs should be bounded to DUPLICATE_CACHE_SIZE, got " + recent.length);
    }

    @Test
    void testDuplicateDetectionAfterCacheTrim() {
        // Fill the cache beyond capacity
        byte[] payload = new byte[]{1};
        for (int i = 0; i < 100; i++) {
            receiveWindow.process(i, payload, Reliability.UNRELIABLE);
        }

        // seq 10 was removed during trimming (trim removes 16 from head at cap 64)
        // so it should NOT be considered a duplicate anymore
        ReceiveWindow.PacketResult result = receiveWindow.process(10, payload, Reliability.UNRELIABLE);
        assertTrue(result.delivered(),
                "seq 10 was trimmed from cache, so it should be delivered as a new packet");
    }

    @Test
    void testDuplicateStillDetectedWithinWindow() {
        // Duplicate within the cache window should still be detected
        byte[] payload = new byte[]{1};
        receiveWindow.process(100, payload, Reliability.UNRELIABLE);

        // Before cache fills, duplicate is detected
        ReceiveWindow.PacketResult result = receiveWindow.process(100, payload, Reliability.UNRELIABLE);
        assertFalse(result.delivered(), "duplicate within window should be detected");
    }

    @Test
    void testOrderedChannelWithBufferedDelivery() {
        // Regression: ensure ORDERED channels still deliver correctly after the cache fix
        byte[] payload = new byte[]{1};

        // First deliver seq=1 to establish the window baseline
        receiveWindow.process(1, payload, Reliability.RELIABLE_ORDERED);

        // Send packets out of order: 2, 4 (3 is missing)
        receiveWindow.process(2, payload, Reliability.RELIABLE_ORDERED);
        receiveWindow.process(4, payload, Reliability.RELIABLE_ORDERED);

        // Deliver 3 - should trigger delivery of 3, 4 from buffer
        ReceiveWindow.PacketResult result = receiveWindow.process(3, payload, Reliability.RELIABLE_ORDERED);
        assertTrue(result.delivered());
        assertEquals(0, result.missingSeqs().length);
    }
}
