package com.faforever.iceadapter.custom_udp;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.AckField;
import org.junit.jupiter.api.Test;

class AckFieldTest {

    @Test
    void testSetBit() {
        long ackBits = AckField.setBit(0, 0);
        assertEquals(1, ackBits);

        ackBits = AckField.setBit(ackBits, 1);
        assertEquals(3, ackBits); // binary: 11

        ackBits = AckField.setBit(0, 15);
        assertEquals(1L << 15, ackBits);
    }

    @Test
    void testSetBitOutOfBounds() {
        assertThrows(IllegalArgumentException.class, () -> AckField.setBit(0, -1));
        assertThrows(IllegalArgumentException.class, () -> AckField.setBit(0, 16));
    }

    @Test
    void testIsSet() {
        long ackBits = AckField.setBit(0, 5);
        assertTrue(AckField.isSet(ackBits, 5));
        assertFalse(AckField.isSet(ackBits, 4));
        assertFalse(AckField.isSet(ackBits, 6));
    }

    @Test
    void testMultipleBits() {
        long ackBits = 0;
        ackBits = AckField.setBit(ackBits, 0);
        ackBits = AckField.setBit(ackBits, 5);
        ackBits = AckField.setBit(ackBits, 15);

        assertTrue(AckField.isSet(ackBits, 0));
        assertTrue(AckField.isSet(ackBits, 5));
        assertTrue(AckField.isSet(ackBits, 15));
        assertFalse(AckField.isSet(ackBits, 1));
    }

    @Test
    void testClear() {
        long ackBits = AckField.setBit(0, 5);
        long cleared = AckField.clear();
        assertEquals(0, cleared);
        assertFalse(AckField.isSet(cleared, 5));
    }

    @Test
    void testPopulationCount() {
        assertEquals(0, AckField.populationCount(0));
        assertEquals(1, AckField.populationCount(1));
        assertEquals(2, AckField.populationCount(3)); // binary: 11
        assertEquals(16, AckField.populationCount((1L << 16) - 1));
    }

    @Test
    void testIsFull() {
        assertFalse(AckField.isFull(0));
        assertFalse(AckField.isFull(1));
        assertTrue(AckField.isFull((1L << 16) - 1));
    }

    @Test
    void testOffset() {
        assertEquals(0, AckField.offset(100, 100));
        assertEquals(5, AckField.offset(100, 105));
        assertEquals(-5, AckField.offset(100, 95));
    }

    @Test
    void testToAckBits() {
        // Use sequences within the 16-bit window
        // Base = 100, window is [85, 100] for offsets [-15, 0]
        int[] received = {100, 99, 98, 95};
        long ackBits = AckField.toAckBits(100, received);

        // Check that bits for received sequences are set
        // 100 -> offset 0 -> bit position 15
        assertTrue(AckField.isSet(ackBits, 15));
        // 99 -> offset -1 -> bit position 14
        assertTrue(AckField.isSet(ackBits, 14));
        // 98 -> offset -2 -> bit position 13
        assertTrue(AckField.isSet(ackBits, 13));
        // 95 -> offset -5 -> bit position 10
        assertTrue(AckField.isSet(ackBits, 10));
    }
}
