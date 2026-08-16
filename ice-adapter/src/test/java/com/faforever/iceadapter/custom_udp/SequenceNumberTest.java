package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.SequenceNumber;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SequenceNumberTest {

    @Test
    void testCompareEqual() {
        assertEquals(0, SequenceNumber.compare(100, 100));
        assertEquals(0, SequenceNumber.compare(0, 0));
        assertEquals(0, SequenceNumber.compare(32767, 32767));
    }

    @Test
    void testCompareNewer() {
        assertTrue(SequenceNumber.isNewer(101, 100));
        assertTrue(SequenceNumber.isNewer(200, 100));
        assertTrue(SequenceNumber.compare(200, 100) > 0);
    }

    @Test
    void testCompareOlder() {
        assertTrue(SequenceNumber.isOlder(100, 101));
        assertTrue(SequenceNumber.isOlder(100, 200));
        assertTrue(SequenceNumber.compare(100, 200) < 0);
    }

    @Test
    void testCompareWithOverflowForward() {
        // 32767 -> 0 -> 1
        assertTrue(SequenceNumber.isNewer(0, 32767));
        assertTrue(SequenceNumber.isNewer(1, 0));
        assertTrue(SequenceNumber.compare(1, 32767) > 0);
    }

    @Test
    void testCompareWithOverflowBackward() {
        // 0 -> 32767 -> 32766
        assertTrue(SequenceNumber.isOlder(0, 1));
        assertTrue(SequenceNumber.isOlder(32767, 0));
        assertTrue(SequenceNumber.compare(32766, 32767) < 0);
    }

    @Test
    void testCompareOverflowWrap() {
        // Test sequence number wrap-around behavior
        assertTrue(SequenceNumber.isNewer(0, 32767), "0 should be newer than 32767");
        assertTrue(SequenceNumber.isNewer(1, 0), "1 should be newer than 0");
        assertTrue(SequenceNumber.isOlder(32767, 0), "32767 should be older than 0");
    }

    @Test
    void testNext() {
        assertEquals(1, SequenceNumber.next(0));
        assertEquals(0, SequenceNumber.next(32767));
        assertEquals(5, SequenceNumber.next(4));
    }

    @Test
    void testInWindow() {
        assertTrue(SequenceNumber.inWindow(105, 100, 10));
        assertTrue(SequenceNumber.inWindow(95, 100, 10));
        assertTrue(SequenceNumber.inWindow(100, 100, 5));

        assertFalse(SequenceNumber.inWindow(111, 100, 10));
        assertFalse(SequenceNumber.inWindow(89, 100, 10));
    }

    @Test
    void testInWindowWithOverflow() {
        assertTrue(SequenceNumber.inWindow(0, 32767, 5));
        assertTrue(SequenceNumber.inWindow(2, 0, 5));
        assertTrue(SequenceNumber.inWindow(32766, 32767, 5));
    }
}
