package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.TransportConnectionStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportConnectionStatsTest {

    private TransportConnectionStats stats;

    @BeforeEach
    void setUp() {
        stats = new TransportConnectionStats();
    }

    @Test
    void testRecordSentIncrementsPackets() {
        stats.recordSent(100);

        assertEquals(1, stats.getPacketsSent());
        assertEquals(100, stats.getBytesSent());
    }

    @Test
    void testRecordReceivedIncrementsPackets() {
        stats.recordReceived(50);

        assertEquals(1, stats.getPacketsReceived());
        assertEquals(50, stats.getBytesReceived());
    }

    @Test
    void testRecordMultipleSends() {
        stats.recordSent(100);
        stats.recordSent(200);

        assertEquals(2, stats.getPacketsSent());
        assertEquals(300, stats.getBytesSent());
    }

    @Test
    void testRecordRetransmission() {
        stats.recordRetransmission();
        assertEquals(1, stats.getRetransmissions());
    }

    @Test
    void testRecordNackRetransmission() {
        stats.recordNackRetransmission();
        assertEquals(1, stats.getNackRetransmissions());
    }

    @Test
    void testRecordDuplicate() {
        stats.recordDuplicate();
        assertEquals(1, stats.getDuplicates());
    }

    @Test
    void testRecordTimeout() {
        stats.recordTimeout();
        assertEquals(1, stats.getTimeouts());
    }

    @Test
    void testRecordRttUpdatesAverages() {
        stats.recordRtt(100);
        stats.recordRtt(200);

        assertEquals(150.0, stats.getAverageRtt(), 0.01);
        // p95/p99 should be non-zero after recording
        assertTrue(stats.getP95RttMs() > 0);
        assertTrue(stats.getP99RttMs() > 0);
    }

    @Test
    void testGetAverageRttEmpty() {
        assertEquals(0.0, stats.getAverageRtt());
    }

    @Test
    void testGetThroughputBps() {
        stats.recordSent(100);

        assertEquals(800.0, stats.getThroughputBps());
    }

    @Test
    void testGetThroughputBpsZeroBytes() {
        assertEquals(0.0, stats.getThroughputBps());
    }

    @Test
    void testGetPacketLossRate() {
        stats.recordSent(100);
        stats.recordSent(100);
        stats.recordSent(100);
        stats.recordSent(100);
        stats.recordSent(100);
        stats.recordRetransmission();

        assertEquals(0.2, stats.getPacketLossRate(), 0.01); // 1 retransmission / 5 packets sent
    }

    @Test
    void testGetPacketLossRateZeroPackets() {
        assertEquals(0.0, stats.getPacketLossRate());
    }

    @Test
    void testToString() {
        stats.recordSent(100);
        stats.recordReceived(50);
        stats.recordRetransmission();

        String str = stats.toString();
        assertTrue(str.contains("sent=1"));
        assertTrue(str.contains("received=1"));
        assertTrue(str.contains("retransmissions=1"));
        assertTrue(str.contains("nackRetransmissions=0"));
    }
}
