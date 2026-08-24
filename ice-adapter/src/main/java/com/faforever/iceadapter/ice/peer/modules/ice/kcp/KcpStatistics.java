package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcp;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.MyKcpMetric;
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
     * KCP conversation ID — unique identifier for this KCP session.
     * Used to distinguish multiple KCP streams; matches the conv parameter passed to Ukcp constructor.
     */
    private int conv = 0;

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
     * Next scheduled update timestamp — epoch milliseconds when the next KCP update cycle is scheduled.
     * Lower time-to-update values indicate more responsive KCP processing.
     */
    private long nextUpdateMs = 0;

    /**
     * Time until next update in milliseconds — how long until the next KCP update cycle.
     * Useful for monitoring KCP responsiveness.
     */
    private int timeToNextUpdateMs = 0;

    /**
     * Total bytes sent through KCP — cumulative count of all application data bytes sent since connection start.
     * Increments each time send() is called on the KcpAdapter.
     */
    private long bytesSentBytes = 0;

    /**
     * Total bytes received through KCP — cumulative count of all application data bytes received since connection start.
     * Increments each time data is delivered to the application layer via receive().
     */
    private long bytesReceivedBytes = 0;

    /**
     * Resets all statistics to their default (zero) values.
     * Use this to clear accumulated counters when starting a new connection or session.
     */
    public void reset() {
        this.conv = 0;
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
        this.nextUpdateMs = 0;
        this.timeToNextUpdateMs = 0;
        this.bytesSentBytes = 0;
        this.bytesReceivedBytes = 0;
    }

    public void update(MyKcp kcp) {
        MyKcpMetric metric = kcp.getMetric();
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

    /**
     * Updates all KCP statistics from the MyKcp instance and sets the conv from the adapter.
     */
    public void update(MyKcp kcp, int conv) {
        this.conv = conv;
        update(kcp);
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
