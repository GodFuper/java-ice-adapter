package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import io.jpower.kcp.netty.Kcp;
import io.jpower.kcp.netty.KcpMetric;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * KCP protocol statistics for a single peer connection.
 * <p>
 * Captures available metrics from the underlying KCP implementation for monitoring and debugging.
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class KcpStatistics {

    /**
     * Smoothed round-trip time (SRTT) — average latency estimate in milliseconds.
     * Low values indicate stable connection; sudden spikes suggest packet loss or congestion.
     */
    private int srttMs = 0;

    /**
     * RTT variation (RTTVAR) — standard deviation of round-trip times in milliseconds.
     * High values indicate unstable connection with variable latency.
     */
    private int rttvarMs = 0;

    /**
     * Retransmission timeout (RTO) — time before unacknowledged data is retransmitted in milliseconds.
     * Increases with RTT and RTTVAR; clamped between IKCP_RTO_MIN (100) and IKCP_RTO_MAX (60000).
     */
    private int rtoMs = 0;

    /**
     * Congestion window size (cwnd) — maximum number of segments that can be in flight.
     * Growths indicate healthy throughput; reductions signal detected loss/congestion.
     */
    private int cwnd = 0;

    /**
     * Send next sequence number — the SN of the next segment to be sent.
     * Monotonically increasing; gap indicates segments not yet transmitted.
     */
    private long sndNxt = 0;

    /**
     * Send unacknowledged — the earliest SN waiting for ACK from remote peer.
     * Distance from sndNxt indicates in-flight data volume.
     */
    private long sndUna = 0;

    /**
     * Receive next sequence number — the SN expected by local application.
     * Increments as out-of-order segments are reassembled and delivered.
     */
    private long rcvNxt = 0;

    /**
     * Local send window size — maximum segments the local stack permits (configurable, default 32).
     * Together with cwnd and rmtWnd, bounds actual throughput.
     */
    private int sndWnd = 0;

    /**
     * Local receive window size — maximum segments the local stack can buffer (default 128).
     * Flow control limit for incoming data.
     */
    private int rcvWnd = 0;

    /**
     * Pending send queue size — total segments buffered locally (sndBuf + sndQueue).
     * High values indicate application producing data faster than network can transmit.
     */
    private int waitSnd = 0;

    /**
     * Total retransmissions — cumulative count of all segment retransmits since connection start.
     * Useful for computing packet loss ratio (xmit / total_sent).
     */
    private int xmit = 0;

    /**
     * Maximum retransmissions for any single segment — indicates worst-case per-segment loss.
     * If this approaches IKCP_DEADLINK (20), the connection will be considered dead.
     */
    private int maxSegXmit = 0;

    /**
     * Configured dead link threshold — number of retransmits before connection is declared dead.
     * Default is 20 (IKCP_DEADLINK). Compare with maxSegXmit to assess connection health.
     */
    private int deadLink = 0;

    /**
     * KCP internal state — 0=normal, -1=dead (when maxSegXmit >= deadLink).
     * Use getState() on Kcp instance for current state.
     */
    private int state = 0;

    /**
     * Last update timestamp — epoch milliseconds when this snapshot was captured.
     * Useful for monitoring staleness of the statistics.
     */
    private long timestampMs = 0;

    /**
     * Resets all statistics to their default (zero) values.
     * Use this to clear accumulated counters when starting a new connection or session.
     */
    public void reset() {
        this.srttMs = 0;
        this.rttvarMs = 0;
        this.rtoMs = 0;
        this.cwnd = 0;
        this.sndNxt = 0;
        this.sndUna = 0;
        this.rcvNxt = 0;
        this.sndWnd = 0;
        this.rcvWnd = 0;
        this.waitSnd = 0;
        this.xmit = 0;
        this.maxSegXmit = 0;
        this.deadLink = 0;
        this.state = 0;
        this.timestampMs = 0;
    }

    public void update(Kcp kcp) {
        KcpMetric metric = kcp.getMetric();
        this.srttMs = metric.srtt();
        this.rttvarMs = metric.rttvar();
        this.rtoMs = metric.rto();
        this.cwnd = metric.cwnd();
        this.sndNxt = metric.sndNxt();
        this.sndUna = metric.sndUna();
        this.rcvNxt = metric.rcvNxt();
        this.xmit = metric.xmit();
        this.maxSegXmit = metric.maxSegXmit();
        this.sndWnd = kcp.getSndWnd();
        this.rcvWnd = kcp.getRcvWnd();
        this.waitSnd = kcp.waitSnd();
        this.deadLink = kcp.getDeadLink();
        this.state = kcp.getState();
        this.timestampMs = System.currentTimeMillis();
    }
}
