package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages pending reliable packets and triggers retransmission.
 * <p>
 * Uses a NavigableMap to efficiently find and remove acknowledged packets,
 * and to check which packets have exceeded their RTO.
 */
@Getter
public class SendWindow {

    private static final int DEFAULT_MAX_PENDING = 64;
    private static final int DEFAULT_MAX_BYTES = 65536;
    /**
     * Cooldown period after NACK-triggered retransmission.
     * During this period, RTO retransmission is suppressed to prevent duplicate sends.
     * Set to 200ms to quickly allow RTO recovery if NACK retransmission itself is lost.
     */
    public static final long NACK_RETRANSMIT_COOLDOWN_MS = 200;

    /**
     * Multiplier for RTO when checking NACK cooldown.
     * A packet NACK-retransmitted within NACK_COOLDOWN_RTO * RTO ms
     * will NOT be RTO-retransmitted.
     */
    private static final double NACK_COOLDOWN_RTO_MULTIPLIER = 5.0;

    private final NavigableMap<Integer, PendingPacket> pendingPackets = new TreeMap<>();
    private final RttEstimator rttEstimator;
    private final int maxPending;
    private final int maxBytes;
    private int currentBytes = 0;
    private final AtomicInteger nextSeq = new AtomicInteger(1);
    // Track NACK-triggered retransmissions to prevent duplicate sends
    private final ConcurrentHashMap<Integer, Long> nackRetransmitTimes = new ConcurrentHashMap<>();

    /**
     * Get the next sequence number and increment it.
     * This method should be called before creating a packet to reserve a sequence number.
     * Thread-safe via AtomicInteger. Starts from 1.
     *
     * @return the next sequence number
     */
    public int getNextSeq() {
        return nextSeq.getAndUpdate(seq -> seq >= SequenceNumber.MAX_SEQUENCE ? 1 : seq + 1);
    }

    public SendWindow() {
        this(DEFAULT_MAX_PENDING, DEFAULT_MAX_BYTES, new RttEstimator());
    }

    public SendWindow(int maxPending, int maxBytes, RttEstimator rttEstimator) {
        this.maxPending = maxPending;
        this.maxBytes = maxBytes;
        this.rttEstimator = rttEstimator;
    }

    /**
     * Add a packet to the send window.
     *
     * @return true if the packet was added, false if the window is full
     */
    public synchronized boolean add(PendingPacket packet) {
        if (pendingPackets.size() >= maxPending) {
            return false;
        }
        if (currentBytes + packet.getPayload().length > maxBytes) {
            return false;
        }
        pendingPackets.put(packet.getSeq(), packet);
        currentBytes += packet.getPayload().length;
        return true;
    }

    /**
     * Assign a new sequence number and create a PendingPacket.
     *
     * @return true if a sequence was assigned, false if window is full
     */
    public boolean assignAndAdd(int channel, Reliability reliability, byte[] payload) {
        if (pendingPackets.size() >= maxPending) {
            return false;
        }
        if (currentBytes + payload.length > maxBytes) {
            return false;
        }
        int seq = getNextSeq();
        PendingPacket packet = new PendingPacket(seq, payload, channel, reliability);
        pendingPackets.put(seq, packet);
        currentBytes += payload.length;
        return true;
    }

    /**
     * Acknowledge packets up to and including the given sequence number.
     * Returns the sequence number just before the acknowledged range.
     */
    public synchronized int acknowledgeUpTo(int ackSeq) {
        if (pendingPackets.isEmpty()) {
            return ackSeq;
        }
        int lastAcked = ackSeq;
        while (!pendingPackets.isEmpty()) {
            Integer lower = pendingPackets.firstKey();
            if (SequenceNumber.isOlderOrEqual(lower, ackSeq)) {
                PendingPacket removed = pendingPackets.remove(lower);
                if (removed != null) {
                    currentBytes -= removed.getPayload().length;
                    lastAcked = lower;
                }
            } else {
                break;
            }
        }
        return lastAcked;
    }

    /**
     * Process ACK bitfield to acknowledge packets in a range around ackSeq.
     */
    public synchronized void acknowledgeWithBitfield(int ackSeq, long ackBits) {
        for (int i = 0; i < AckField.WINDOW_SIZE; i++) {
            if (AckField.isSet(ackBits, i)) {
                int seq = ackSeq - (AckField.WINDOW_SIZE - 1) + i;
                PendingPacket removed = pendingPackets.remove(seq);
                if (removed != null) {
                    currentBytes -= removed.getPayload().length;
                }
            }
        }
    }

    /**
     * Record RTT for an acknowledged packet.
     * Looks up the pending packet by seq, calculates RTT from lastSendTime,
     * and records it in the RTT estimator.
     * Skips NACK-retransmitted packets to avoid artificially low RTT samples.
     */
    public synchronized void recordRttForAcked(int ackSeq, RttEstimator rttEstimator) {
        PendingPacket acked = pendingPackets.get(ackSeq);
        if (acked != null && !acked.hasNackRetransmitPending(Long.MAX_VALUE)) {
            // Only record RTT for packets that were NOT NACK-retransmitted.
            // NACK-retransmitted packets have lastSendTime = NACK-send time,
            // which gives artificially low RTT and corrupts RTO estimator.
            long rtt = System.currentTimeMillis() - acked.getLastSendTime();
            if (rtt > 0) {
                rttEstimator.recordRtt(rtt);
            }
        }
    }

    /**
     * Get packets that have exceeded their RTO and need retransmission.
     * Skips packets that were recently retransmitted via NACK (to prevent double-sends).
     * Skips packets that have exceeded the maximum retransmission count.
     * Applies exponential backoff based on retransmission attempts.
     */
    public synchronized Iterable<PendingPacket> getExpired(long now) {
        long rto = rttEstimator.getRtoMs();
        // First collect packets to retransmit
        List<PendingPacket> toRetransmit = new ArrayList<>();
        List<Integer> toRemove = new ArrayList<>();

        for (PendingPacket p : pendingPackets.values()) {
            if (p.getElapsedSinceLastSend() >= (rto * p.getBackoffMultiplier())) {
                if (p.isMaxRetransmitted()) {
                    // Mark packet for removal (RTO path cleanup)
                    toRemove.add(p.getSeq());
                } else if (!p.hasNackRetransmitPending(NACK_RETRANSMIT_COOLDOWN_MS)) {
                    toRetransmit.add(p);
                }
            }
        }

        // Remove max-retransmitted packets (after iteration)
        for (int seq : toRemove) {
            PendingPacket removed = pendingPackets.remove(seq);
            if (removed != null) {
                currentBytes -= removed.getPayload().length;
            }
        }

        return toRetransmit;
    }

    /**
     * Get the oldest pending packet.
     */
    public synchronized PendingPacket getOldest() {
        Integer first = pendingPackets.firstKey();
        return first != null ? pendingPackets.get(first) : null;
    }

    /**
     * Remove a specific packet by sequence number.
     */
    public synchronized void remove(int seq) {
        PendingPacket removed = pendingPackets.remove(seq);
        if (removed != null) {
            currentBytes -= removed.getPayload().length;
        }
    }

    /**
     * Get a pending packet by sequence number.
     * Returns null if not found.
     */
    public synchronized PendingPacket getPendingPacket(int seq) {
        return pendingPackets.get(seq);
    }

    /**
     * Get the number of pending packets.
     */
    public synchronized int size() {
        return pendingPackets.size();
    }

    /**
     * Check if the window is full.
     */
    public synchronized boolean isFull() {
        return pendingPackets.size() >= maxPending;
    }

    /**
     * Clear all pending packets.
     */
    public synchronized void clear() {
        pendingPackets.clear();
        currentBytes = 0;
        nackRetransmitTimes.clear();
    }

    /**
     * Check if a NACK-triggered retransmission should be skipped for this sequence.
     * Returns true if a recent NACK retransmission already exists (coalescing).
     */
    public boolean shouldSkipNackRetransmit(int seq) {
        Long lastTime = nackRetransmitTimes.get(seq);
        if (lastTime == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - lastTime;
        return elapsed < NACK_RETRANSMIT_COOLDOWN_MS;
    }

    /**
     * Record a NACK-triggered retransmission for a sequence number.
     */
    public void recordNackRetransmitTime(int seq) {
        nackRetransmitTimes.put(seq, System.currentTimeMillis());
        // Clean up old entries
        nackRetransmitTimes.entrySet().removeIf(e ->
                System.currentTimeMillis() - e.getValue() > NACK_RETRANSMIT_COOLDOWN_MS * 3
        );
    }

    /**
     * Get the number of tracked NACK retransmissions.
     */
    public int getNackRetransmitCount() {
        return nackRetransmitTimes.size();
    }
}
