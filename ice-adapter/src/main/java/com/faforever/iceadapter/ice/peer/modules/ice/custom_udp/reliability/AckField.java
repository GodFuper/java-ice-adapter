package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability;

import lombok.experimental.UtilityClass;

/**
 * ACK bitfield for cumulative/selective acknowledgment.
 * <p>
 * Uses a 16-bit bitmask where each bit represents whether a packet
 * at a specific sequence offset has been received.
 * <p>
 * Bit 0 = sequence - 16 (oldest in window)
 * Bit 1 = sequence - 15
 * ...
 * Bit 15 = sequence + 15 (newest in window)
 */
@UtilityClass
public final class AckField {

    public static final int WINDOW_SIZE = 16;
    private static final long ALL_BITS_SET = (1L << WINDOW_SIZE) - 1;

    /**
     * Set the bit for the given offset from base sequence.
     * Offset must be in range [0, WINDOW_SIZE - 1].
     */
    public long setBit(long ackBits, int offset) {
        if (offset < 0 || offset >= WINDOW_SIZE) {
            throw new IllegalArgumentException(
                    "Offset must be in range [0, " + (WINDOW_SIZE - 1) + "], got: " + offset);
        }
        return ackBits | (1L << offset);
    }

    /**
     * Check if the bit for the given offset is set.
     */
    public boolean isSet(long ackBits, int offset) {
        if (offset < 0 || offset >= WINDOW_SIZE) {
            throw new IllegalArgumentException(
                    "Offset must be in range [0, " + (WINDOW_SIZE - 1) + "], got: " + offset);
        }
        return (ackBits & (1L << offset)) != 0;
    }

    /**
     * Calculate offset from base sequence.
     * Offset = seq - base.
     * Negative offset means the packet is older than base.
     */
    public int offset(int base, int seq) {
        return SequenceNumber.compare(seq, base);
    }

    /**
     * Convert received sequence numbers into ACK bitfield relative to base.
     *
     * @param base         the base sequence number (ack field in packet)
     * @param receivedSeqs sorted list of received sequence numbers
     * @return ackBits bitfield
     */
    public long toAckBits(int base, int[] receivedSeqs) {
        long ackBits = 0;
        for (int seq : receivedSeqs) {
            int off = offset(base, seq);
            // Convert negative offset to positive bit position
            if (off >= -WINDOW_SIZE + 1 && off <= WINDOW_SIZE - 1) {
                int bitPos = off + WINDOW_SIZE - 1;
                ackBits = setBit(ackBits, bitPos);
            }
        }
        return ackBits;
    }

    /**
     * Clear all bits.
     */
    public long clear() {
        return 0;
    }

    /**
     * Count set bits.
     */
    public int populationCount(long ackBits) {
        return Long.bitCount(ackBits);
    }

    /**
     * Check if all bits are set (all packets in window acknowledged).
     */
    public boolean isFull(long ackBits) {
        return ackBits == ALL_BITS_SET;
    }
}
