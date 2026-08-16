package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.AckField;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.PendingPacket;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.SendWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SendWindowTest {

    private SendWindow sendWindow;

    @BeforeEach
    void setUp() {
        sendWindow = new SendWindow(10, 10000, new RttEstimator());
    }

    @Test
    void testAddAndGetOldest() {
        byte[] payload = new byte[]{1, 2, 3};
        PendingPacket packet = new PendingPacket(10, payload, 0, Reliability.RELIABLE);

        assertTrue(sendWindow.add(packet));
        PendingPacket oldest = sendWindow.getOldest();

        assertNotNull(oldest);
        assertEquals(10, oldest.getSeq());
    }

    @Test
    void testAddWhenFull() {
        for (int i = 0; i < 10; i++) {
            byte[] payload = new byte[10];
            PendingPacket packet = new PendingPacket(i, payload, 0, Reliability.RELIABLE);
            assertTrue(sendWindow.add(packet));
        }

        // Window is full
        byte[] payload = new byte[10];
        PendingPacket packet = new PendingPacket(100, payload, 0, Reliability.RELIABLE);
        assertFalse(sendWindow.add(packet));
    }

    @Test
    void testAcknowledgeUpTo() {
        for (int i = 0; i < 5; i++) {
            byte[] payload = new byte[10];
            PendingPacket packet = new PendingPacket(i, payload, 0, Reliability.RELIABLE);
            sendWindow.add(packet);
        }

        assertEquals(5, sendWindow.size());

        // Acknowledge up to seq 2
        int lastAcked = sendWindow.acknowledgeUpTo(2);
        assertEquals(2, lastAcked);
        assertEquals(2, sendWindow.size()); // Only seq 3 and 4 remain

        // Acknowledge up to seq 4
        lastAcked = sendWindow.acknowledgeUpTo(4);
        assertEquals(0, sendWindow.size());
    }

    @Test
    void testRemove() {
        byte[] payload = new byte[10];
        PendingPacket packet = new PendingPacket(50, payload, 0, Reliability.RELIABLE);
        sendWindow.add(packet);

        assertEquals(1, sendWindow.size());
        sendWindow.remove(50);
        assertEquals(0, sendWindow.size());
    }

    @Test
    void testIsFull() {
        assertFalse(sendWindow.isFull());

        for (int i = 0; i < 10; i++) {
            byte[] payload = new byte[10];
            PendingPacket packet = new PendingPacket(i, payload, 0, Reliability.RELIABLE);
            sendWindow.add(packet);
        }

        assertTrue(sendWindow.isFull());
    }

    @Test
    void testClear() {
        for (int i = 0; i < 5; i++) {
            byte[] payload = new byte[10];
            PendingPacket packet = new PendingPacket(i, payload, 0, Reliability.RELIABLE);
            sendWindow.add(packet);
        }

        sendWindow.clear();
        assertEquals(0, sendWindow.size());
        assertEquals(0, sendWindow.getCurrentBytes());
    }

    @Test
    void testGetNextSeqIncrements() {
        // getNextSeq() should return and increment sequence number
        int seq1 = sendWindow.getNextSeq();
        int seq2 = sendWindow.getNextSeq();
        int seq3 = sendWindow.getNextSeq();

        assertEquals(1, seq1);
        assertEquals(2, seq2);
        assertEquals(3, seq3);

        // Verify nextSeq is now 4
        assertEquals(4, sendWindow.getNextSeq());
    }

    @Test
    void testAssignAndAdd() {
        assertTrue(sendWindow.assignAndAdd(0, Reliability.RELIABLE, new byte[]{1, 2, 3}));
        assertTrue(sendWindow.assignAndAdd(1, Reliability.UNRELIABLE, new byte[]{4, 5}));

        // Sequence numbers should auto-increment, starting from 1
        assertEquals(3, sendWindow.getNextSeq());
    }

    @Test
    void testByteLimit() {
        SendWindow smallWindow = new SendWindow(100, 100, new RttEstimator());

        // Add packets that fit
        assertTrue(smallWindow.assignAndAdd(0, Reliability.RELIABLE, new byte[50]));
        assertTrue(smallWindow.assignAndAdd(0, Reliability.RELIABLE, new byte[40]));

        // This should fail - would exceed byte limit
        assertFalse(smallWindow.assignAndAdd(0, Reliability.RELIABLE, new byte[20]));
    }

    @Test
    void testAcknowledgeWithBitfield() {
        // Add packets with sequence numbers 10-14
        for (int i = 10; i <= 14; i++) {
            byte[] payload = new byte[10];
            PendingPacket packet = new PendingPacket(i, payload, 0, Reliability.RELIABLE);
            sendWindow.add(packet);
        }

        assertEquals(5, sendWindow.size());

        // Acknowledge bitfield for base seq 12:
        // WINDOW_SIZE=16, valid bit positions [0, 15]
        // bit 0-15 map to offsets -15 to 0 (i.e., seq 12-15=base-15 to base)
        // So only packets <= base are covered: seqs 10, 11, 12
        // toAckBits filters out-of-range: seqs 13, 14 are filtered (positive offsets)

        long ackBits = AckField.toAckBits(12, new int[]{10, 11, 12});

        // All 3 bits should be set
        // 10 -> offset -2 -> bit 13
        // 11 -> offset -1 -> bit 14
        // 12 -> offset 0 -> bit 15
        assertTrue(AckField.isSet(ackBits, 13));
        assertTrue(AckField.isSet(ackBits, 14));
        assertTrue(AckField.isSet(ackBits, 15));

        // Acknowledge these 3 packets
        sendWindow.acknowledgeWithBitfield(12, ackBits);
        assertEquals(2, sendWindow.size()); // Only seqs 13 and 14 remain

        // Now acknowledge the remaining with cumulative ACK
        int lastAcked = sendWindow.acknowledgeUpTo(14);
        assertEquals(14, lastAcked);
        assertEquals(0, sendWindow.size()); // All acked
    }

    @Test
    void testRecordRttForAcked() throws InterruptedException {
        // Create a packet and wait a bit to simulate send delay
        byte[] payload = new byte[10];
        PendingPacket packet = new PendingPacket(100, payload, 0, Reliability.RELIABLE);
        assertTrue(sendWindow.add(packet));

        // Wait 50ms to ensure RTT > 0
        Thread.sleep(50);

        RttEstimator rttEstimator = new RttEstimator();

        // Record RTT for acked packet
        sendWindow.recordRttForAcked(100, rttEstimator);

        // Verify RTT was recorded (measured should be true)
        assertTrue(rttEstimator.isMeasured());
    }

    @Test
    void testRecordRttForAckedNonExistent() {
        RttEstimator rttEstimator = new RttEstimator();

        // Should not throw for non-existent seq
        assertDoesNotThrow(() -> sendWindow.recordRttForAcked(999, rttEstimator));

        // Should not have recorded anything
        assertFalse(rttEstimator.isMeasured());
    }

    @Test
    void testGetPendingPacket() {
        byte[] payload = new byte[]{1, 2, 3};
        PendingPacket packet = new PendingPacket(100, payload, 0, Reliability.RELIABLE);
        assertTrue(sendWindow.add(packet));

        PendingPacket found = sendWindow.getPendingPacket(100);
        assertNotNull(found);
        assertEquals(100, found.getSeq());

        // Non-existent seq
        PendingPacket notFound = sendWindow.getPendingPacket(999);
        assertNull(notFound);
    }

    @Test
    void testRecordSendUpdatesMetadata() {
        byte[] payload = new byte[10];
        PendingPacket packet = new PendingPacket(10, payload, 0, Reliability.RELIABLE);

        int initialAttempts = packet.getAttempts();
        long initialLastSend = packet.getLastSendTime();

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        packet.recordSend();

        assertEquals(initialAttempts + 1, packet.getAttempts());
        assertTrue(packet.getLastSendTime() > initialLastSend);
    }
}
