package com.faforever.iceadapter.custom_udp.reliability;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.PendingPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingPacketTest {

    @Test
    void testConstructorSetsFields() {
        byte[] payload = {1, 2, 3};
        PendingPacket packet = new PendingPacket(42, payload, 1, Reliability.RELIABLE);

        assertEquals(42, packet.getSeq());
        assertArrayEquals(payload, packet.getPayload());
        assertEquals(1, packet.getChannel());
        assertEquals(Reliability.RELIABLE, packet.getReliability());
    }

    @Test
    void testConstructorCopiesPayload() {
        byte[] payload = {1, 2, 3};
        PendingPacket packet = new PendingPacket(0, payload, 0, Reliability.RELIABLE);

        // Modify original array
        payload[0] = 99;

        // Internal copy should be unaffected
        assertEquals(1, packet.getPayload()[0]);
    }

    @Test
    void testConstructorSetsTimestamps() {
        long before = System.currentTimeMillis();
        PendingPacket packet = new PendingPacket(0, new byte[10], 0, Reliability.RELIABLE);
        long after = System.currentTimeMillis();

        assertTrue(packet.getFirstSendTime() >= before);
        assertTrue(packet.getFirstSendTime() <= after);
        assertEquals(packet.getFirstSendTime(), packet.getLastSendTime());
    }

    @Test
    void testConstructorSetsInitialAttempts() {
        PendingPacket packet = new PendingPacket(0, new byte[10], 0, Reliability.RELIABLE);
        assertEquals(1, packet.getAttempts());
    }

    @Test
    void testRecordSendIncrementsAttempts() {
        PendingPacket packet = new PendingPacket(0, new byte[10], 0, Reliability.RELIABLE);
        assertEquals(1, packet.getAttempts());

        packet.recordSend();
        assertEquals(2, packet.getAttempts());

        packet.recordSend();
        assertEquals(3, packet.getAttempts());
    }

    @Test
    void testRecordSendUpdatesLastSendTime() throws InterruptedException {
        PendingPacket packet = new PendingPacket(0, new byte[10], 0, Reliability.RELIABLE);
        long initialLastSend = packet.getLastSendTime();

        Thread.sleep(20);
        packet.recordSend();

        assertTrue(packet.getLastSendTime() > initialLastSend);
    }

    @Test
    void testGetElapsedSinceLastSend() throws InterruptedException {
        PendingPacket packet = new PendingPacket(0, new byte[10], 0, Reliability.RELIABLE);

        Thread.sleep(15);
        long elapsed = packet.getElapsedSinceLastSend();

        assertTrue(elapsed >= 10);
        assertTrue(elapsed < 2000);
    }

    @Test
    void testGetElapsedSinceFirstSend() throws InterruptedException {
        PendingPacket packet = new PendingPacket(0, new byte[10], 0, Reliability.RELIABLE);

        Thread.sleep(15);
        long elapsed = packet.getElapsedSinceFirstSend();

        assertTrue(elapsed >= 10);
        assertTrue(elapsed < 2000);
    }

    @Test
    void testToString() {
        PendingPacket packet = new PendingPacket(42, new byte[10], 1, Reliability.RELIABLE);
        String str = packet.toString();

        assertTrue(str.contains("seq=42"));
        assertTrue(str.contains("channel=1"));
        assertTrue(str.contains("attempts=1"));
        assertTrue(str.contains("elapsed="));
    }
}
