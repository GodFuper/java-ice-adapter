package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import kcp.IKcp;
import kcp.Kcp;
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
     * Smoothed round-trip time (SRTT) — not exposed by kcp-base 1.6.2 API.
     */
    private int srttMs = 0;

    /**
     * RTT variation (RTTVAR) — not exposed by kcp-base 1.6.2 API.
     */
    private int rttvarMs = 0;

    /**
     * Retransmission timeout (RTO) — not exposed by kcp-base 1.6.2 API.
     */
    private int rtoMs = 0;

    /**
     * Congestion window size (cwnd) — not exposed by kcp-base 1.6.2 API.
     */
    private int cwnd = 0;

    /**
     * Send next sequence number — not exposed by kcp-base 1.6.2 API.
     */
    private long sndNxt = 0;

    /**
     * Send unacknowledged — not exposed by kcp-base 1.6.2 API.
     */
    private long sndUna = 0;

    /**
     * Receive next sequence number — not exposed by kcp-base 1.6.2 API.
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
     * KCP internal state — 0=normal, -1=dead.
     */
    private int state = 0;

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
     * Resets all statistics to their default (zero) values.
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
        this.state = 0;
        this.timestampMs = 0;
        this.nextUpdateMs = 0;
        this.timeToNextUpdateMs = 0;
        this.bytesSentBytes = 0;
        this.bytesReceivedBytes = 0;
    }

    /**
     * Updates all KCP statistics from the IKcp instance.
     * Note: Only exposes metrics available in kcp-base 1.6.2 public API.
     */
    public void update(IKcp ikcp) {
        // Only sndWnd and rcvWnd are available via IKcp interface
        this.sndWnd = ikcp.getSndWnd();
        this.waitSnd = ikcp.waitSnd();
        this.state = ikcp.getState();
        this.timestampMs = System.currentTimeMillis();
    }

    /**
     * Updates all KCP statistics from the Kcp instance and sets the conv.
     * @deprecated Use {@link #update(IKcp)} instead
     */
    @Deprecated
    public void update(Kcp kcp, int conv) {
        this.conv = conv;
        update((IKcp) kcp);
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
