package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp;

import lombok.Getter;

/**
 * Statistics for the Custom Reliable UDP transport.
 */
@Getter
public class TransportConnectionStats {

    private long packetsSent;
    private long packetsReceived;
    private long bytesSent;
    private long bytesReceived;
    private long retransmissions;
    private long nackRetransmissions;
    private long duplicates;
    private long outOfOrder;
    private long timeouts;
    private double totalRttMs;
    private int rttSamples;
    private double p95RttMs;
    private double p99RttMs;

    public void recordSent(int packetSize) {
        packetsSent++;
        bytesSent += packetSize;
    }

    public void recordReceived(int packetSize) {
        packetsReceived++;
        bytesReceived += packetSize;
    }

    public void recordRetransmission() {
        retransmissions++;
    }

    public void recordNackRetransmission() {
        nackRetransmissions++;
    }

    public void recordDuplicate() {
        duplicates++;
    }

    public void recordOutOfOrder() {
        outOfOrder++;
    }

    public void recordTimeout() {
        timeouts++;
    }

    public void recordRtt(double rttMs) {
        totalRttMs += rttMs;
        rttSamples++;
        // Simple moving average approximation for p95/p99
        double alpha = 0.1;
        p95RttMs = (1 - alpha) * p95RttMs + alpha * rttMs * 1.3;
        p99RttMs = (1 - alpha) * p99RttMs + alpha * rttMs * 1.6;
    }

    public double getAverageRtt() {
        return rttSamples > 0 ? totalRttMs / rttSamples : 0;
    }

    public double getThroughputBps() {
        return bytesSent > 0 ? bytesSent * 8.0 : 0;
    }

    public double getPacketLossRate() {
        return packetsSent > 0 ? (double) retransmissions / packetsSent : 0;
    }

    @Override
    public String toString() {
        return "TransportConnectionStats{" + "sent="
                + packetsSent + ", received="
                + packetsReceived + ", retransmissions="
                + retransmissions + ", nackRetransmissions="
                + nackRetransmissions + ", duplicates="
                + duplicates + ", avgRtt="
                + String.format("%.2f", getAverageRtt()) + "ms" + ", p95Rtt="
                + String.format("%.2f", p95RttMs) + "ms" + ", p99Rtt="
                + String.format("%.2f", p99RttMs) + "ms" + '}';
    }
}
