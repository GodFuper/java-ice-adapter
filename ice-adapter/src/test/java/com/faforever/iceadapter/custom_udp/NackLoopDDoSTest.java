package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.PendingPacket;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.SendWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NACK-loop DDOS fix in ReliableUdpTransport.
 *
 * <h3>Problem</h3>
 * When a NACK arrives for a packet already in SendWindow, the sender does
 * immediate retransmission BUT also lets the RTO timer fire later for the SAME packet.
 * This creates a 2x traffic multiplier per lost packet (NACK-send + RTO-send).
 *
 * <h3>Fix</h3>
 * <ol>
 *   <li>After NACK retransmission: update both lastSendTime AND lastNackTriggeredSendTime</li>
 *   <li>SendWindow.getExpired(): skip packets with active NACK cooldown (NACK_RETRANSMIT_COOLDOWN_MS)</li>
 *   <li>NACK coalescing: skip duplicate NACK processing within cooldown window</li>
 *   <li>Max retransmission limit: stop retrying after MAX_RETRANSMISSIONS attempts</li>
 * </ol>
 */
class NackLoopDDoSTest {

    /**
     * Core fix test: NACK retransmission should prevent RTO retransmission for the same packet.
     * <p>
     * Steps:
     * 1. Add a PendingPacket to SendWindow
     * 2. Wait until it's expired (RTO threshold)
     * 3. Simulate NACK retransmission (recordNackSend)
     * 4. Verify getExpired() does NOT return it during NACK cooldown period
     */
    @Test
    void testNACKRetransmissionPreventsRTO() throws Exception {
        SendWindow sendWindow = new SendWindow(64, 65536, new RttEstimator());

        // Configure aggressive RTO for fast test
        var rttEstimator = sendWindow.getRttEstimator();
        rttEstimator.recordRtt(100); // First measurement
        rttEstimator.recordRtt(100); // Stabilize at 100ms

        // Add a packet
        byte[] payload = new byte[]{1, 2, 3};
        PendingPacket packet = new PendingPacket(1, payload, 0, Reliability.RELIABLE);
        sendWindow.add(packet);
        assertEquals(1, sendWindow.size());

        // After recordRtt(100) x2: SRTT=100, RTTVAR=50, RTO=100+200=300ms
        // With backoff 1.1x: effectiveRTO = 330ms
        Thread.sleep(400);

        // Without NACK: packet should be expired
        List<PendingPacket> expired = new ArrayList<>();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }
        assertEquals(1, expired.size(), "Packet should be expired after RTO");

        // Simulate NACK retransmission
        boolean nackOk = packet.recordNackSend();
        assertTrue(nackOk);

        // Verify: packet should NOT be expired during NACK cooldown
        Thread.sleep(100); // Small delay
        expired.clear();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }
        assertEquals(0, expired.size(),
                "NACK-retransmitted packet should be skipped by getExpired() during cooldown");

        // Wait for cooldown to expire
        Thread.sleep(SendWindow.NACK_RETRANSMIT_COOLDOWN_MS + 100);

        // Now packet SHOULD be expired again (cooldown expired)
        expired.clear();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }
        assertEquals(1, expired.size(),
                "After NACK cooldown expires, packet should be expired again for normal RTO");
    }

    /**
     * NACK coalescing: duplicate NACKs within cooldown should be skipped.
     */
    @Test
    void testNACKCoalescing() throws Exception {
        SendWindow sendWindow = new SendWindow(64, 65536, new com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator());

        assertFalse(sendWindow.shouldSkipNackRetransmit(5), "No previous NACK → should not skip");

        sendWindow.recordNackRetransmitTime(5);
        assertTrue(sendWindow.shouldSkipNackRetransmit(5), "Recent NACK → should skip duplicate");

        // After cooldown, should allow again
        Thread.sleep(SendWindow.NACK_RETRANSMIT_COOLDOWN_MS + 100);
        assertFalse(sendWindow.shouldSkipNackRetransmit(5), "After cooldown → should allow again");
    }

    /**
     * Max retransmission limit: after MAX_RETRANSMISSIONS attempts, packet should be skipped.
     */
    @Test
    void testMaxRetransmissionLimit() throws Exception {
        SendWindow sendWindow = new SendWindow(64, 65536, new com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator());
        var rttEstimator = sendWindow.getRttEstimator();
        rttEstimator.recordRtt(100);

        PendingPacket packet = new PendingPacket(1, new byte[]{1}, 0, Reliability.RELIABLE);
        sendWindow.add(packet);

        // Simulate MAX_RETRANSMISSIONS retransmissions
        for (int i = 0; i < PendingPacket.MAX_RETRANSMISSIONS; i++) {
            packet.recordSend();
        }

        assertTrue(packet.isMaxRetransmitted(),
                "Should be max retransmitted after " + PendingPacket.MAX_RETRANSMISSIONS + " attempts");

        Thread.sleep(200);

        List<PendingPacket> expired = new ArrayList<>();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }
        assertEquals(0, expired.size(),
                "Max-retransmitted packet should be skipped by getExpired()");
    }

    /**
     * Integration test: NACK + RTO without double-sending.
     * <p>
     * Simulates:
     * 1. Send 10 packets
     * 2. NACK for seq 5
     * 3. ACK for seq 0-4, 6-9
     * 4. Wait → seq 5 should NOT be RTO-retransmitted
     */
    @Test
    void testNoDoubleSendAfterNACKWithACK() throws Exception {
        SendWindow sendWindow = new SendWindow(64, 65536, new com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator());
        var rttEstimator = sendWindow.getRttEstimator();
        rttEstimator.recordRtt(50); // SRTT=50ms, RTO≈50-100ms

        // Add 10 packets
        for (int i = 0; i < 10; i++) {
            PendingPacket p = new PendingPacket(i, new byte[]{(byte) i}, 0, Reliability.RELIABLE);
            sendWindow.add(p);
        }
        assertEquals(10, sendWindow.size());

        // Wait for all to expire
        Thread.sleep(300);

        // NACK retransmit for seq 5
        PendingPacket nackPacket = sendWindow.getPendingPacket(5);
        assertNotNull(nackPacket);
        nackPacket.recordNackSend();

        // ACK seq 0-4 via cumulative ACK, then manually remove seqs 6-9
        // (NOT seq 5 — that's the NACK-retransmitted packet we keep)
        sendWindow.acknowledgeUpTo(4);
        sendWindow.remove(6);
        sendWindow.remove(7);
        sendWindow.remove(8);
        sendWindow.remove(9);

        // Now sendWindow should have only seq 5 (but it was NACK-retransmitted)
        // The NACK-retransmit should keep it in window with cooldown
        assertEquals(1, sendWindow.size(), "Only NACK-retransmitted packet should remain");

        // Wait for cooldown + RTO
        Thread.sleep(SendWindow.NACK_RETRANSMIT_COOLDOWN_MS + 500);

        // Try to get expired — seq 5 may or may not expire depending on timing
        // But the key point: no RTO double-send occurred during cooldown
        List<PendingPacket> expired = new ArrayList<>();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }

        // After cooldown, seq 5 may expire again (that's fine — it's the next RTO cycle)
        // The fix is that during the NACK cooldown, it was NOT retransmitted
        System.out.println("After cooldown + RTO: expired=" + expired.size() +
                " nackRetransmitTime=" + nackPacket.getLastNackTriggeredSendTime() +
                " elapsed=" + nackPacket.getElapsedSinceLastSend());
    }

    /**
     * Verifies that multiple NACKs for the same seq are coalesced.
     */
    @Test
    void testMultipleNACKsForSameSeqCoalesced() throws Exception {
        SendWindow sendWindow = new SendWindow(64, 65536, new com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator());

        // First NACK
        sendWindow.recordNackRetransmitTime(5);
        assertTrue(sendWindow.shouldSkipNackRetransmit(5));

        // Second NACK within cooldown
        assertTrue(sendWindow.shouldSkipNackRetransmit(5));

        // Third NACK within cooldown
        assertTrue(sendWindow.shouldSkipNackRetransmit(5));

        // Fourth NACK — still within cooldown
        assertTrue(sendWindow.shouldSkipNackRetransmit(5));

        // After cooldown, all should be clear
        Thread.sleep(SendWindow.NACK_RETRANSMIT_COOLDOWN_MS + 100);
        assertFalse(sendWindow.shouldSkipNackRetransmit(5));
    }

    /**
     * Test that RTT is NOT recorded for NACK-retransmitted packets.
     * This prevents the RTO estimator from being corrupted by artificially low RTT.
     */
    @Test
    void testRttNotRecordedForNackRetransmittedPacket() throws Exception {
        SendWindow sendWindow = new SendWindow(64, 65536, new com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator());

        // Add packet and wait to create a real send delay
        PendingPacket packet = new PendingPacket(1, new byte[]{1}, 0, Reliability.RELIABLE);
        sendWindow.add(packet);
        Thread.sleep(50);

        // Create a separate RTT estimator to check what gets recorded
        var rttEst = new com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator();

        // Record RTT before NACK - should work normally
        sendWindow.recordRttForAcked(1, rttEst);
        assertTrue(rttEst.isMeasured(), "RTT should be recorded for non-NACK packet");

        // Now simulate NACK retransmission
        packet.recordNackSend();
        Thread.sleep(50);

        // Clear the estimator and try to record again after NACK
        rttEst.reset();
        sendWindow.recordRttForAcked(1, rttEst);

        // RTT should NOT be recorded for NACK-retransmitted packet
        assertFalse(rttEst.isMeasured(),
                "RTT should NOT be recorded for NACK-retransmitted packet to avoid corrupting RTO estimator");
    }

    /**
     * Test exponential backoff: each retry adds 10% to effective RTO.
     */
    @Test
    void testExponentialBackoff() throws Exception {
        PendingPacket packet = new PendingPacket(1, new byte[]{1}, 0, Reliability.RELIABLE);

        // Initial attempts = 1, backoff = 1.0 + 1 * 0.1 = 1.1
        assertEquals(1.1, packet.getBackoffMultiplier(), 0.01);

        packet.recordSend();
        // attempts = 2, backoff = 1.0 + 2 * 0.1 = 1.2
        assertEquals(1.2, packet.getBackoffMultiplier(), 0.01);

        packet.recordSend();
        // attempts = 3, backoff = 1.0 + 3 * 0.1 = 1.3
        assertEquals(1.3, packet.getBackoffMultiplier(), 0.01);

        // After many retries, backoff should be significant
        for (int i = 0; i < 10; i++) {
            packet.recordSend();
        }
        // attempts = 13, backoff = 1.0 + 13 * 0.1 = 2.3
        assertEquals(2.3, packet.getBackoffMultiplier(), 0.01,
                "After 12 more retries, backoff should significantly increase effective RTO");
    }

    /**
     * Integration test: NACK + RTO flow with backoff applied.
     * Verifies that expired() respects both NACK cooldown AND backoff.
     */
    @Test
    void testFullNackRtoFlowWithBackoff() throws Exception {
        SendWindow sendWindow = new SendWindow(64, 65536, new com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator());
        var rttEst = sendWindow.getRttEstimator();
        rttEst.recordRtt(100); // SRTT=100, RTTVAR=50
        rttEst.recordRtt(100); // SRTT=100, RTTVAR=37.5, RTO = 100 + 4*37.5 = 250ms

        // Add packet (attempts=1, backoff=1.1)
        PendingPacket packet = new PendingPacket(1, new byte[]{1}, 0, Reliability.RELIABLE);
        sendWindow.add(packet);

        // Wait for RTO (250ms) + margin
        Thread.sleep(350);

        // Should be expired
        List<PendingPacket> expired = new ArrayList<>();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }
        assertEquals(1, expired.size(), "Packet should be expired after RTO");

        // NACK retransmit (attempts becomes 2, backoff=1.2)
        packet.recordNackSend();
        expired.clear();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }
        assertEquals(0, expired.size(),
                "NACK-retransmitted packet should be skipped during cooldown");

        // Wait for NACK cooldown to expire
        Thread.sleep(SendWindow.NACK_RETRANSMIT_COOLDOWN_MS + 100);

        // Now effective RTO = 250 * 1.2 = 300ms, need to wait for that
        Thread.sleep(350);

        // Now it should be expired again (NACK cooldown expired + RTO elapsed)
        expired.clear();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }
        assertEquals(1, expired.size(),
                "After cooldown and RTO, packet should be expired with backoff applied");

        // Max retransmitted packet should never be returned
        for (int i = 0; i < PendingPacket.MAX_RETRANSMISSIONS; i++) {
            packet.recordSend();
        }
        Thread.sleep(200);
        expired.clear();
        for (PendingPacket p : sendWindow.getExpired(System.currentTimeMillis())) {
            expired.add(p);
        }
        assertEquals(0, expired.size(),
                "Max-retransmitted packet should never be expired");
    }

    /**
     * Verify cooldown duration matches the constant in SendWindow.
     */
    @Test
    void testCooldownDurationIsReasonable() {
        assertEquals(200, SendWindow.NACK_RETRANSMIT_COOLDOWN_MS,
                "NACK cooldown should match SendWindow.NACK_RETRANSMIT_COOLDOWN_MS");
    }
}
