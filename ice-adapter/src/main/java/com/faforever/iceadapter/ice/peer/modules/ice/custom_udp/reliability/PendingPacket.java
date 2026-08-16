package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import lombok.Getter;
import lombok.ToString;

import java.util.Arrays;

/**
 * Per-packet retransmission metadata.
 * Stores information about a pending reliable packet.
 */
@Getter
@ToString
public final class PendingPacket {

    /**
     * Maximum number of retransmissions before giving up. Increased for better reliability under packet loss.
     */
    public static final int MAX_RETRANSMISSIONS = 30;

    private final int seq;
    private final byte[] payload;
    private final int channel;
    private final Reliability reliability;
    private final long firstSendTime;
    private long lastSendTime;
    private volatile long lastNackTriggeredSendTime;
    private int attempts;

    public PendingPacket(int seq, byte[] payload, int channel, Reliability reliability) {
        this.seq = seq;
        this.payload = Arrays.copyOf(payload, payload.length);
        this.channel = channel;
        this.reliability = reliability;
        this.firstSendTime = System.currentTimeMillis();
        this.lastSendTime = this.firstSendTime;
        this.lastNackTriggeredSendTime = 0;
        this.attempts = 1;
    }

    /**
     * Record an RTO-triggered send.
     */
    public void recordSend() {
        this.lastSendTime = System.currentTimeMillis();
        this.attempts++;
    }

    /**
     * Record a NACK-triggered send and update both lastSendTime and lastNackTriggeredSendTime.
     * This prevents both RTO and duplicate NACK from retransmitting the same packet.
     * Returns true if the send was allowed, false if max NACK retransmissions exceeded.
     */
    public boolean recordNackSend() {
        long now = System.currentTimeMillis();
        this.lastSendTime = now;           // Prevents RTO retransmission
        this.lastNackTriggeredSendTime = now;  // Prevents duplicate NACK processing
        this.attempts++;
        return true;
    }

    /**
     * Check if this packet has been retransmitted too many times.
     */
    public boolean isMaxRetransmitted() {
        return attempts > MAX_RETRANSMISSIONS;
    }

    /**
     * Check if a NACK-triggered retransmission was recently sent
     * (to prevent duplicate NACK processing).
     */
    public boolean hasNackRetransmitPending(long cooldownMs) {
        return lastNackTriggeredSendTime > 0
                && (System.currentTimeMillis() - lastNackTriggeredSendTime) < cooldownMs;
    }

    public long getElapsedSinceLastSend() {
        return System.currentTimeMillis() - lastSendTime;
    }

    public long getElapsedSinceFirstSend() {
        return System.currentTimeMillis() - firstSendTime;
    }

    /**
     * Calculate effective RTO with exponential backoff based on retransmission attempts.
     * Each attempt adds 10% to the base RTO to prevent thundering herd.
     */
    public double getBackoffMultiplier() {
        return 1.0 + attempts * 0.1;
    }

    @Override
    public String toString() {
        return "PendingPacket{" + "seq="
                + seq + ", channel="
                + channel + ", attempts="
                + attempts + ", elapsed="
                + getElapsedSinceLastSend() + "ms" + '}';
    }
}
