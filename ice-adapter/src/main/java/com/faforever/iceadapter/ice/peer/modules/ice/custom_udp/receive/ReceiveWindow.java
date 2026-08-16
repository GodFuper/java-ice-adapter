package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.receive;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.SequenceNumber;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Handles duplicate detection and out-of-order buffering for ordered packets.
 * <p>
 * For UNRELIABLE/RELIABLE (unordered): only duplicate detection.
 * For UNRELIABLE_ORDERED/RELIABLE_ORDERED: also buffers out-of-order packets
 * and delivers them in sequence, with a timeout for stale packets.
 */
@Getter
@Slf4j
public class ReceiveWindow {

    private static final int DUPLICATE_CACHE_SIZE = 64;
    private static final int ORDERED_BUFFER_SIZE = 32;
    private static final long ORDERED_TIMEOUT_MS = 3000;

    private final TreeMap<Integer, byte[]> orderedBuffer = new TreeMap<>();
    // Trimmed list used for duplicate detection in isDuplicate()
    private final List<Integer> receivedSeqs = new ArrayList<>();
    // Untrimmed set used for gap detection, advance, and NACK generation
    private final Set<Integer> allReceivedSeqs = new HashSet<>();
    private volatile int nextExpectedSeq = 1;
    // Track highest received seq for gap detection in unordered (RELIABLE) packets
    private volatile int highestReceivedSeq = 0;
    // Track highest contiguous seq for reliable unordered delivery.
    // This is the highest seq such that ALL seqs from 0 to highestContiguousSeq have been received.
    // Used to ensure gap detection works correctly even when packets arrive out of order.
    private volatile int highestContiguousSeq = 0;
    // Gap threshold: send NACK if gap exceeds this to avoid false positives from out-of-order
    // Threshold=1 means: if a packet is more than 1 seq ahead of highestReceived, send NACK
    // This handles single-packet loss (the most common case with 5% loss rate)
    private static final int UNORDERED_GAP_THRESHOLD = 1;

    /**
     * Check if a packet is a duplicate.
     * Uses the trimmed list for duplicate detection, allowing old seqs to be
     * re-delivered once they are trimmed from the cache.
     */
    public synchronized boolean isDuplicate(int seq) {
        if (SequenceNumber.isOlderOrEqual(seq, nextExpectedSeq - DUPLICATE_CACHE_SIZE)) {
            // Packet is too old to be relevant
            return false;
        }
        return receivedSeqs.contains(seq);
    }

    /**
     * Record a received sequence number in both the trimmed list (for duplicate cache)
     * and the untrimmed set (for gap detection and advance).
     */
    public synchronized void recordReceived(int seq) {
        receivedSeqs.add(seq);
        allReceivedSeqs.add(seq);
        if (receivedSeqs.size() >= DUPLICATE_CACHE_SIZE) {
            // Trim oldest entries from the head (always safe regardless of circular seq space)
            int trimCount = DUPLICATE_CACHE_SIZE / 4;
            receivedSeqs.subList(0, trimCount).clear();
        }
    }

    /**
     * Process a received packet.
     *
     * @return the payload if the packet should be delivered, null if it should be buffered/dropped
     */
    public synchronized PacketResult process(int seq, byte[] payload, Reliability reliability) {
        // Duplicate check
        if (isDuplicate(seq)) {
            // For RELIABLE unordered packets, a duplicate seq might be a retransmission
            // that arrived after the initial (dropped) packet. If highestContiguousSeq
            // cannot advance past this seq, we still need to process it to allow
            // advanceHighestContiguousSeq() to progress.
            if (reliability == Reliability.RELIABLE && SequenceNumber.compare(seq, highestContiguousSeq) > 0) {
                // For RELIABLE unordered packets, a duplicate seq might be a retransmission
                // that arrived after the initial (dropped) packet was recorded but not delivered.
                // We need to: advance contiguous range AND deliver the payload
                log.warn("[RECEIVE-WINDOW] RETRANSMISSION seq={} (already in cache, highestContiguousSeq={}), delivering", seq, highestContiguousSeq);

                // Update highestReceivedSeq if this is newer
                if (SequenceNumber.isNewer(seq, highestReceivedSeq)) {
                    highestReceivedSeq = seq;
                }

                // Advance contiguous range
                advanceHighestContiguousSeq();

                // Check if this retransmission fills a gap and triggers new NACKs
                int[] newMissing = getMissingSeqsForUnordered(highestContiguousSeq + 1, seq - 1);

                return new PacketResult(payload, true, newMissing);
            }
            log.debug("[RECEIVE-WINDOW] DUPLICATE seq={} (receivedSeqs.size={}, highestContiguousSeq={})", seq, receivedSeqs.size(), highestContiguousSeq);
            // Mark as duplicate for tracking (caller should handle stats)
            return new PacketResult(null, false, new int[0]);
        }

        recordReceived(seq);
        log.debug("[RECEIVE-WINDOW] recordReceived seq={}, receivedSeqs.size={}", seq, receivedSeqs.size());

        if (reliability == Reliability.UNRELIABLE) {
            // Unordered: deliver immediately, no gap detection
            return new PacketResult(payload, true, new int[0]);
        }

        if (reliability == Reliability.RELIABLE) {
            // RELIABLE (unordered): deliver immediately but detect gaps
            // Advance highestContiguousSeq BEFORE gap detection so it's up to date
            advanceHighestContiguousSeq();

            log.debug("[RECEIVE-WINDOW] RELIABLE seq={}, highestReceivedSeq={}, highestContiguousSeq={}", seq, highestReceivedSeq, highestContiguousSeq);
            if (SequenceNumber.isNewer(seq, highestReceivedSeq)) {
                // New higher seq received - detect gaps using highestReceivedSeq for gap detection
                // (to avoid re-reporting the same gap on subsequent packets) and highestContiguousSeq
                // for identifying which specific seqs are missing.
                int gap = SequenceNumber.compare(seq, highestReceivedSeq + UNORDERED_GAP_THRESHOLD);
                if (gap > 0) {
                    // Gap detected between highestReceivedSeq and seq
                    // Missing seqs are those between highestReceivedSeq+1 and seq that aren't received
                    int[] missing = getMissingSeqsForUnordered(highestReceivedSeq + 1, seq - 1);
                    highestReceivedSeq = seq;
                    log.warn("[RECEIVE-WINDOW] UNORDERED GAP detected: seq={}, highestReceivedSeq={}, highestContiguousSeq={}, missingCount={}",
                            seq, highestReceivedSeq, highestContiguousSeq, missing.length);
                    return new PacketResult(payload, true, missing);
                }
                log.trace("[RECEIVE-WINDOW] UNORDERED seq={} highestReceivedSeq={} (no gap)", seq, highestReceivedSeq);
                highestReceivedSeq = seq;
            }

            // Try to advance highestContiguousSeq again (the highest seq where all below are received)
            // This is crucial for correct gap detection when packets arrive out of order
            advanceHighestContiguousSeq();

            // Deliver immediately
            return new PacketResult(payload, true, new int[0]);
        }

        // Ordered: check if we can deliver
        int cmp = SequenceNumber.compare(seq, nextExpectedSeq);
        if (cmp == 0) {
            // Exactly what we expected - deliver and check buffer
            deliverFromBuffer(seq);
            // Check for gaps and return missing seqs
            int[] missing = getMissingSeqs();
            return new PacketResult(payload, true, missing);
        } else if (cmp > 0) {
            // Future packet: buffer it
            orderedBuffer.put(seq, payload);
            // Check if we can now detect missing packets
            int[] missing = getMissingSeqs();
            if (missing.length > 0) {
                // Buffer a NACK payload with the missing seqs
                return new PacketResult(null, false, missing);
            }
            return new PacketResult(null, false, new int[0]);
        } else {
            // Old packet that wasn't a duplicate (e.g., late arrival within window)
            // For UNRELIABLE_ORDERED: deliver anyway (stale data might still be useful)
            // For RELIABLE_ORDERED: this shouldn't happen
            return new PacketResult(payload, true, new int[0]);
        }
    }

    /**
     * Advance highestContiguousSeq to the highest seq such that all seqs from
     * highestContiguousSeq to the current max received seq have been received contiguously.
     * This ensures gap detection works correctly even when packets arrive out of order.
     * <p>
     * If highestContiguousSeq has not been initialized (i.e., the first RELIABLE packet
     * just arrived), it is set to that packet's seq, establishing the baseline for
     * contiguous tracking.
     * <p>
     * Example: if seqs 18 is dropped, and 19, 20, 21 arrive, highestContiguousSeq stays
     * at 18. When 18 finally arrives (via retransmission), highestContiguousSeq advances
     * to 21.
     * <p>
     * Uses {@code allReceivedSeqs} (untrimmed) for accurate gap detection.
     */
    private synchronized void advanceHighestContiguousSeq() {
        // If highestContiguousSeq hasn't been initialized yet (no packets received or
        // first packet seq > 0), initialize it to the minimum received seq.
        // This handles the case where the first received seq is not 0.
        if (highestContiguousSeq == 0 && !allReceivedSeqs.isEmpty()) {
            // Find minimum seq in allReceivedSeqs to establish the base
            int minSeq = Integer.MAX_VALUE;
            for (int seq : allReceivedSeqs) {
                if (SequenceNumber.compare(seq, minSeq) < 0) {
                    minSeq = seq;
                }
            }
            // Initialize: from minSeq, advance as far as contiguous
            highestContiguousSeq = minSeq;
            // Now advance forward
            int checkSeq = minSeq;
            int maxIterations = 100;
            while (maxIterations-- > 0) {
                int nextSeq = SequenceNumber.next(checkSeq);
                if (allReceivedSeqs.contains(nextSeq)) {
                    highestContiguousSeq = nextSeq;
                    checkSeq = nextSeq;
                } else {
                    break;
                }
            }
            return;
        }

        // Normal advance from current highestContiguousSeq using untrimmed set
        int checkSeq = highestContiguousSeq;
        int maxIterations = 100; // Prevent infinite loop in case of bugs
        while (maxIterations-- > 0) {
            int nextSeq = SequenceNumber.next(checkSeq);
            if (allReceivedSeqs.contains(nextSeq)) {
                highestContiguousSeq = nextSeq;
                checkSeq = nextSeq;
            } else {
                break;
            }
        }
    }

    private List<Integer> deliverFromBuffer(int seq) {
        List<Integer> delivered = new ArrayList<>();
        delivered.add(seq);

        // Check if next expected is in buffer
        int checkSeq = SequenceNumber.next(seq);
        while (orderedBuffer.containsKey(checkSeq)) {
            orderedBuffer.remove(checkSeq);
            delivered.add(checkSeq);
            checkSeq = SequenceNumber.next(checkSeq);
        }

        if (!delivered.isEmpty()) {
            nextExpectedSeq = checkSeq;
        }

        return delivered;
    }

    /**
     * Clean up old buffered packets that have timed out.
     */
    public synchronized void cleanup(long currentTimeMs) {
        // Remove packets that have been buffered too long
        // (simplified: just keep buffer size bounded)
        if (orderedBuffer.size() > ORDERED_BUFFER_SIZE) {
            // Remove oldest entries
            int toRemove = orderedBuffer.size() - ORDERED_BUFFER_SIZE / 2;
            for (int i = 0; i < toRemove && !orderedBuffer.isEmpty(); i++) {
                orderedBuffer.pollFirstEntry();
            }
        }
    }

    /**
     * Generate missing sequence numbers for unordered (RELIABLE) packets.
     * Checks the range [start, end] and returns seqs that are NOT in allReceivedSeqs (untrimmed).
     * Used to generate NACK payloads when a gap is detected.
     */
    public synchronized int[] getMissingSeqsForUnordered(int start, int end) {
        List<Integer> missing = new ArrayList<>();
        int seq = start;
        while (SequenceNumber.compare(seq, end) <= 0 && missing.size() < 16) {
            if (!allReceivedSeqs.contains(seq)) {
                missing.add(seq);
            }
            seq = SequenceNumber.next(seq);
        }
        return missing.stream().mapToInt(i -> i).toArray();
    }

    /**
     * Detect missing sequence numbers between nextExpectedSeq and orderedBuffer.
     * Returns list of seqs that are missing (expected but not yet received).
     * Only considers packets that are within the gap window.
     */
    public synchronized int[] getMissingSeqs() {
        if (orderedBuffer.isEmpty()) {
            return new int[0];
        }

        // Gap is between nextExpectedSeq and first entry in orderedBuffer
        int firstInBuffer = orderedBuffer.firstKey();

        if (SequenceNumber.compare(firstInBuffer, nextExpectedSeq) <= 0) {
            // Buffer is stale (firstInBuffer <= nextExpectedSeq), clean it up
            orderedBuffer.clear();
            return new int[0];
        }

        // Count missing seqs: [nextExpectedSeq, firstInBuffer - 1]
        int gapSize = SequenceNumber.compare(firstInBuffer, nextExpectedSeq);
        if (gapSize <= 0) {
            return new int[0];
        }

        int limit = Math.min(gapSize, 16); // Max 16 missing per NACK
        int[] missing = new int[limit];
        int seq = nextExpectedSeq;
        for (int i = 0; i < limit; i++) {
            missing[i] = seq;
            seq = SequenceNumber.next(seq);
        }

        return missing;
    }

    /**
     * Get the latest delivered sequence number for piggyback ACK.
     * Returns the highest sequence number that has been received and processed.
     * Returns 0 if no packets received yet.
     */
    public synchronized int getLastDeliveredSeq() {
        if (receivedSeqs.isEmpty() && highestReceivedSeq == 0) {
            return 0;
        }
        // Find the highest received sequence
        int maxSeq = receivedSeqs.isEmpty() ? 0 : receivedSeqs.get(0);
        for (int seq : receivedSeqs) {
            if (SequenceNumber.isNewer(seq, maxSeq)) {
                maxSeq = seq;
            }
        }
        // Also consider highestReceivedSeq for RELIABLE unordered packets
        if (SequenceNumber.isNewer(highestReceivedSeq, maxSeq)) {
            maxSeq = highestReceivedSeq;
        }
        return maxSeq;
    }

    /**
     * Get recently received sequence numbers for selective ACK bitfield.
     * Returns up to the specified limit, sorted by sequence number.
     */
    public synchronized int[] getRecentReceivedSeqs(int limit) {
        int count = Math.min(receivedSeqs.size(), limit);
        if (count == 0) {
            return new int[0];
        }
        int[] seqs = new int[count];
        int startIndex = receivedSeqs.size() - count;
        for (int i = 0; i < count; i++) {
            seqs[i] = receivedSeqs.get(startIndex + i);
        }
        return seqs;
    }

    public record PacketResult(byte[] payload, boolean delivered, int[] missingSeqs) {
        public static PacketResult buffered() {
            return new PacketResult(null, false, new int[0]);
        }
    }
}
