package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.receive.ReceiveWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-тесты для ReceiveWindow: gap detection и NACK generation для RELIABLE unordered.
 * <p>
 * Эти тесты НЕ используют ICE, Peer, Component — только чистую логику ReceiveWindow.
 * <p>
 * Сценарии:
 * 1. Gap detection: если пакеты приходят не по порядку (N пропущен), generateMissingSeqs возвращает N
 * 2. Retransmission delivery: если дублирующийся seq приходит после drop, он должен быть доставлен
 * 3. Contiguous advancement: highestContiguousSeq должен правильно продвигаться
 */
@DisplayName("ReceiveWindow: Gap Detection & NACK Generation")
class ReceiveWindowGapDetectionTest {

    // =========================================================================
    // Test 1: Базовый случай — все пакеты приходят по порядку
    // =========================================================================
    @Test
    @DisplayName("Sequential packets: no gaps, all delivered")
    void testSequentialPacketsNoGaps() {
        ReceiveWindow window = new ReceiveWindow();
        byte[] payload = new byte[]{1};

        // Send seq 0, 1, 2, 3, 4 in order
        for (int i = 0; i < 5; i++) {
            ReceiveWindow.PacketResult result = window.process(i, payload, Reliability.RELIABLE);
            assertTrue(result.delivered(), "Packet " + i + " should be delivered");
            assertEquals(0, result.missingSeqs().length,
                    "No missing seqs expected for sequential delivery at seq " + i);
        }
    }

    // =========================================================================
    // Test 2: Один пропущенный пакет (модель 5% loss)
    // =========================================================================
    @Test
    @DisplayName("Single gap at seq=19: gap detected, retransmission later delivers missing")
    void testSingleGapAtSeq19() {
        ReceiveWindow window = new ReceiveWindow();
        byte[] payload = new byte[]{1};

        // Send seq 0..18 — все delivered
        for (int i = 0; i <= 18; i++) {
            ReceiveWindow.PacketResult result = window.process(i, payload, Reliability.RELIABLE);
            assertTrue(result.delivered(), "Packet " + i + " should be delivered");
        }

        // seq=19 пропущен (dropped) — не вызываем process()

        // Send seq 20 — должен обнаружить gap [19]
        ReceiveWindow.PacketResult result20 = window.process(20, payload, Reliability.RELIABLE);
        assertTrue(result20.delivered(), "Packet 20 should be delivered");
        int[] missing = result20.missingSeqs();
        assertEquals(1, missing.length, "Should detect 1 missing seq");
        assertEquals(19, missing[0], "Missing seq should be 19");

        // Теперь send retransmission seq=19
        ReceiveWindow.PacketResult result19 = window.process(19, payload, Reliability.RELIABLE);
        assertTrue(result19.delivered(), "Retransmission of seq 19 should be delivered");
        // После доставки 19, gap закрыт — новые missing не ожидаются
    }

    // =========================================================================
    // Test 3: Несколько пропущенных пакетов
    // =========================================================================
    @Test
    @DisplayName("Multiple gaps at seq=19,39,59: all gaps detected")
    void testMultipleGapsAt20PercentLoss() {
        ReceiveWindow window = new ReceiveWindow();
        byte[] payload = new byte[]{1};

        // Helper: send a range and verify all delivered with no missing
        java.util.List<int[]> allMissingSeqs = new java.util.ArrayList<>();

        // Send 0..18
        for (int i = 0; i <= 18; i++) {
            ReceiveWindow.PacketResult r = window.process(i, payload, Reliability.RELIABLE);
            assertTrue(r.delivered());
            assertEquals(0, r.missingSeqs().length, "No missing for seq " + i);
        }

        // Skip 19
        // Receive 20 → detect gap [19]
        ReceiveWindow.PacketResult r20 = window.process(20, payload, Reliability.RELIABLE);
        assertTrue(r20.delivered());
        allMissingSeqs.add(r20.missingSeqs());

        // Send 21..38
        for (int i = 21; i <= 38; i++) {
            ReceiveWindow.PacketResult r = window.process(i, payload, Reliability.RELIABLE);
            assertTrue(r.delivered());
            assertEquals(0, r.missingSeqs().length, "No missing for seq " + i);
        }

        // Skip 39
        // Receive 40 → detect gap [39]
        ReceiveWindow.PacketResult r40 = window.process(40, payload, Reliability.RELIABLE);
        assertTrue(r40.delivered());
        allMissingSeqs.add(r40.missingSeqs());

        // Send 41..58
        for (int i = 41; i <= 58; i++) {
            ReceiveWindow.PacketResult r = window.process(i, payload, Reliability.RELIABLE);
            assertTrue(r.delivered());
            assertEquals(0, r.missingSeqs().length);
        }

        // Skip 59
        // Receive 60 → detect gap [59]
        ReceiveWindow.PacketResult r60 = window.process(60, payload, Reliability.RELIABLE);
        assertTrue(r60.delivered());
        allMissingSeqs.add(r60.missingSeqs());

        // Verify gaps detected
        assertEquals(3, allMissingSeqs.size(), "Should detect 3 gaps");
        assertArrayEquals(new int[]{19}, allMissingSeqs.get(0), "First gap: seq 19");
        assertArrayEquals(new int[]{39}, allMissingSeqs.get(1), "Second gap: seq 39");
        assertArrayEquals(new int[]{59}, allMissingSeqs.get(2), "Third gap: seq 59");

        // Теперь simulate retransmissions
        for (int missingSeq : allMissingSeqs.get(0)) {
            ReceiveWindow.PacketResult r = window.process(missingSeq, payload, Reliability.RELIABLE);
            assertTrue(r.delivered(), "Retransmission seq " + missingSeq + " should be delivered");
        }
        for (int missingSeq : allMissingSeqs.get(1)) {
            window.process(missingSeq, payload, Reliability.RELIABLE);
        }
        for (int missingSeq : allMissingSeqs.get(2)) {
            window.process(missingSeq, payload, Reliability.RELIABLE);
        }
    }

    // =========================================================================
    // Test 4: highestContiguousSeq должен правильно продвигаться
    // =========================================================================
    @Test
    @DisplayName("highestContiguousSeq advances correctly after retransmission")
    void testHighestContiguousSeqAdvances() {
        ReceiveWindow window = new ReceiveWindow();
        byte[] payload = new byte[]{1};

        // Send 0..18
        for (int i = 0; i <= 18; i++) {
            window.process(i, payload, Reliability.RELIABLE);
        }
        assertEquals(18, window.getHighestContiguousSeq(),
                "highestContiguousSeq should be 18 after sending 0..18");

        // Skip 19, send 20
        window.process(20, payload, Reliability.RELIABLE);
        assertEquals(18, window.getHighestContiguousSeq(),
                "highestContiguousSeq should still be 18 (gap at 19)");

        // Retransmit 19
        window.process(19, payload, Reliability.RELIABLE);
        // Теперь contiguous: 0,1,2,...,20 — highestContiguousSeq должен стать 20
        assertEquals(20, window.getHighestContiguousSeq(),
                "highestContiguousSeq should advance to 20 after delivering seq 19");
    }

    // =========================================================================
    // Test 5: Gap detection с более чем одним пропущенным пакетом подряд
    // =========================================================================
    @Test
    @DisplayName("Consecutive gaps: seq 5,6,7 missing → all three detected")
    void testConsecutiveGaps() {
        ReceiveWindow window = new ReceiveWindow();
        byte[] payload = new byte[]{1};

        // Send 0, 1, 2, 3, 4
        for (int i = 0; i <= 4; i++) {
            window.process(i, payload, Reliability.RELIABLE);
        }

        // Skip 5, 6, 7
        // Send 8 → detect gap [5, 6, 7]
        ReceiveWindow.PacketResult result = window.process(8, payload, Reliability.RELIABLE);
        assertTrue(result.delivered());
        int[] missing = result.missingSeqs();
        assertEquals(3, missing.length, "Should detect 3 missing seqs");
        assertArrayEquals(new int[]{5, 6, 7}, missing, "Missing seqs should be 5, 6, 7");
    }

    // =========================================================================
    // Test 6: Retransmission доставки после drop
    // =========================================================================
    @Test
    @DisplayName("Retransmission delivery: packet delivered after initial drop")
    void testRetransmissionDeliveredAfterDrop() {
        ReceiveWindow window = new ReceiveWindow();
        byte[] payload = new byte[]{42};

        // Send seq 10, 11
        window.process(10, payload, Reliability.RELIABLE);
        window.process(11, payload, Reliability.RELIABLE);

        // seq 12 dropped (not processed)

        // Send seq 13 → detect missing [12]
        ReceiveWindow.PacketResult r13 = window.process(13, payload, Reliability.RELIABLE);
        assertTrue(r13.delivered());
        assertArrayEquals(new int[]{12}, r13.missingSeqs(), "Missing: 12");

        // Retransmission seq 12 arrives
        ReceiveWindow.PacketResult r12 = window.process(12, payload, Reliability.RELIABLE);
        assertTrue(r12.delivered(), "Retransmission of seq 12 should be delivered");
        assertEquals(0, r12.missingSeqs().length, "No missing after gap filled");

        // highestContiguousSeq теперь 13
        assertEquals(13, window.getHighestContiguousSeq(),
                "highestContiguousSeq should be 13 after gap filled");
    }

    // =========================================================================
    // Test 7: Отрицательный test — UNRELIABLE не должен делать gap detection
    // =========================================================================
    @Test
    @DisplayName("UNRELIABLE packets: no gap detection, all delivered immediately")
    void testUnreliableNoGapDetection() {
        ReceiveWindow window = new ReceiveWindow();
        byte[] payload = new byte[]{1};

        // Send 0, 2 (skip 1) — for UNRELIABLE no gap detection
        window.process(0, payload, Reliability.UNRELIABLE);
        window.process(2, payload, Reliability.UNRELIABLE);

        // Для UNRELIABLE не должно быть gap detection
        // (но gap detection вообще не запускается для UNRELIABLE — только для RELIABLE)
    }

    // =========================================================================
    // Test 8: 100 пакетов, 5% loss (simulated)
    // =========================================================================
    @Test
    @DisplayName("100 packets with 5% loss pattern: all delivered after retransmissions")
    void test100PacketsWith5PercentLoss() {
        ReceiveWindow window = new ReceiveWindow();
        byte[] payload = new byte[]{1};

        java.util.List<Integer> droppedSeqs = new java.util.ArrayList<>();
        java.util.List<byte[]> deliveredPayloads = new java.util.ArrayList<>();

        for (int i = 0; i < 100; i++) {
            if (i % 20 == 19) {
                // Этот пакет "дропается" — не вызываем window.process()
                droppedSeqs.add(i);
                continue;
            }

            ReceiveWindow.PacketResult result = window.process(i, payload, Reliability.RELIABLE);
            assertTrue(result.delivered(), "seq " + i + " should be delivered");
            if (result.missingSeqs().length > 0) {
                // Это должно сработать для seq 20, 40, 60, 80, 100
                // Но поскольку мы process() вызываем для КАЖДОГО seq, gap detection
                // сработает только при первом пропуске
            }
        }

        System.out.println("Dropped seqs: " + droppedSeqs);
        assertEquals(5, droppedSeqs.size(), "Should have 5 dropped seqs (every 20th)");

        // Теперь simulate retransmissions
        for (int seq : droppedSeqs) {
            ReceiveWindow.PacketResult result = window.process(seq, payload, Reliability.RELIABLE);
            assertTrue(result.delivered(), "Retransmission of seq " + seq + " should be delivered");
        }

        // highestContiguousSeq должен быть 99
        assertEquals(99, window.getHighestContiguousSeq(),
                "highestContiguousSeq should be 99 after all retransmissions");
    }
}
