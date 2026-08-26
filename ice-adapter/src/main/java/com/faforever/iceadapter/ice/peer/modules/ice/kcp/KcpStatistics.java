package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcpMetric;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * KCP protocol statistics for a single peer connection.
 * <p>
 * Captures available metrics from the underlying KCP implementation for monitoring and debugging.
 * Note: kcp-base 1.6.2 does not expose all internal metrics via public API,
 * so some fields (xmit, maxSegXmit) are not available.
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class KcpStatistics {

    /**
     * KCP conversation ID — unique identifier for this KCP session.
     */
    private int conv = 0;

    /**
     * Smoothed round-trip time (SRTT) in milliseconds.
     */
    private int srttMs = 0;

    /**
     * RTT variation (RTTVAR) in milliseconds.
     */
    private int rttvarMs = 0;

    /**
     * Retransmission timeout (RTO) in milliseconds.
     */
    private int rtoMs = 0;

    /**
     * Congestion window size (cwnd).
     */
    private int cwnd = 0;

    /**
     * Send next sequence number.
     */
    private long sndNxt = 0;

    /**
     * Send unacknowledged.
     */
    private long sndUna = 0;

    /**
     * Receive next sequence number.
     */
    private long rcvNxt = 0;

    /**
     * Local send window size.
     */
    private int sndWnd = 0;

    /**
     * Local receive window size.
     */
    private int rcvWnd = 0;

    /**
     * Pending send queue size.
     */
    private int waitSnd = 0;

    /**
     * Maximum segment retransmissions (xmit threshold).
     */
    private int maxSegXmit = 0;

    /**
     * Current segment retransmission count.
     */
    private int xmit = 0;

    /**
     * Cumulative timeout-based retransmissions (RTO expiry).
     */
    private int resendCount = 0;

    /**
     * Cumulative fast retransmissions (triggered by fastack).
     */
    private int fastResendCount = 0;

    /**
     * Cumulative fastack events (segments with duplicate ACKs).
     */
    private int fastackCount = 0;

    /**
     * Slow-start threshold (congestion control).
     */
    private int ssthresh = 0;

    /**
     * Pending send queue size (sndQueue).
     */
    private int sndQueueSize = 0;

    /**
     * Received queue size (rcvQueue).
     */
    private int rcvQueueSize = 0;

    /**
     * Unacknowledged packets in sndBuf.
     */
    private int unackedPackets = 0;

    /**
     * Last update timestamp.
     */
    private long timestampMs = 0;

    /**
     * Next scheduled update timestamp.
     */
    private long nextUpdateMs = 0;

    /**
     * Time until next update in milliseconds.
     */
    private int timeToNextUpdateMs = 0;

    /**
     * Total bytes sent through KCP.
     */
    private long bytesSentBytes = 0;

    /**
     * Total bytes received through KCP.
     */
    private long bytesReceivedBytes = 0;

    /**
     * Indicates a near-dead KCP link (consecutive soft-resyncs or excessive retransmissions).
     */
    private boolean deadLinkDetected = false;

    /**
     * Count of consecutive soft-resync events (skipped missing ranges).
     */
    private int consecutiveSoftResync = 0;

    /**
     * Timestamp (ms) when the current receive gap was first detected. -1 if no gap.
     */
    private int receiveGapSince = -1;

    /**
     * Cumulative count of segments dropped from sndBuf due to TTL expiry.
     */
    private long softDroppedSegments = 0;

    /**
     * Cumulative count of soft-resync events (skipped missing ranges in rcvBuf).
     */
    private long softResyncCount = 0;


    /**
     * Updates all KCP statistics from the MyKcpMetric instance.
     * Provides full access to internal KCP metrics including SRTT, RTTVAR, RTO, CWND, etc.
     */
    public void update(MyKcpMetric metric) {
        if (metric == null) {
            return;
        }
        this.srttMs = metric.srtt();
        this.rttvarMs = metric.rttvar();
        this.rtoMs = metric.rto();
        this.cwnd = metric.cwnd();
        this.sndNxt = metric.sndNxt();
        this.sndUna = metric.sndUna();
        this.rcvNxt = metric.rcvNxt();
        this.maxSegXmit = metric.maxSegXmit();
        this.xmit = metric.xmit();
        this.resendCount = metric.resendCount();
        this.fastResendCount = metric.fastResendCount();
        this.fastackCount = metric.fastackCount();
        this.ssthresh = metric.ssthresh();
        this.sndQueueSize = metric.sndQueueSize();
        this.rcvQueueSize = metric.rcvQueueSize();
        this.unackedPackets = metric.unackedPackets();
        this.deadLinkDetected = metric.deadLinkDetected();
        this.consecutiveSoftResync = metric.consecutiveSoftResync();
        this.receiveGapSince = metric.getReceiveGapSince();
        this.softDroppedSegments = metric.getSoftDroppedSegments();
        this.softResyncCount = metric.getSoftResyncCount();
        this.timestampMs = System.currentTimeMillis();
    }

    /**
     * Updates the next update timestamp fields from the KcpAdapter.
     */
    public void updateNextUpdate(long nextUpdateTimestamp) {
        this.nextUpdateMs = nextUpdateTimestamp;
        this.timeToNextUpdateMs = Math.max(0, (int) (nextUpdateTimestamp - System.currentTimeMillis()));
    }

    /**
     * Sets the byte transfer statistics from the KcpAdapter.
     */
    public void updateBytes(long bytesSentBytes, long bytesReceivedBytes) {
        this.bytesSentBytes = bytesSentBytes;
        this.bytesReceivedBytes = bytesReceivedBytes;
    }
}
