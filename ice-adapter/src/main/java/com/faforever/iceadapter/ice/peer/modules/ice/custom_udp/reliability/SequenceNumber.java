package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability;

/**
 * Handles cyclical sequence number comparison with overflow.
 * <p>
 * Uses half-open comparison window to correctly handle wrap-around.
 * Sequence space is treated as circular with comparison logic:
 * - seqA > seqB: A is newer than B
 * - seqA == seqB: same sequence
 * - seqA < seqB: A is older than B
 * <p>
 * This correctly handles sequence number overflow when the space
 * is larger than the maximum gap between any two packets.
 */
public final class SequenceNumber {

    public static final int MAX_SEQUENCE = 0x7FFF; // 16-bit signed max

    private SequenceNumber() {
    }

    /**
     * Compare two sequence numbers in circular space.
     *
     * @return positive if a > b, negative if a < b, zero if equal
     */
    public static int compare(int a, int b) {
        int diff = a - b;
        int halfSpace = MAX_SEQUENCE / 2;
        // If diff > MAX_SEQUENCE/2, then a is actually "before" b in circular space
        if (diff > halfSpace) {
            return diff - (MAX_SEQUENCE + 1);
        }
        // If diff <= -MAX_SEQUENCE/2, then a is actually "after" b in circular space
        if (diff <= -halfSpace) {
            return diff + (MAX_SEQUENCE + 1);
        }
        return diff;
    }

    /**
     * Check if seq is within the window [expected - windowSize, expected + windowSize].
     */
    public static boolean inWindow(int seq, int expected, int windowSize) {
        int diff = compare(seq, expected);
        return diff >= -windowSize && diff <= windowSize;
    }

    /**
     * Increment sequence number with overflow wrapping.
     */
    public static int next(int current) {
        return (current + 1) & MAX_SEQUENCE;
    }

    /**
     * Check if seqA is strictly newer than seqB.
     */
    public static boolean isNewer(int a, int b) {
        return compare(a, b) > 0;
    }

    /**
     * Check if seqA is older than or equal to seqB.
     */
    public static boolean isOlderOrEqual(int a, int b) {
        return compare(a, b) <= 0;
    }

    /**
     * Check if seqA is strictly older than seqB.
     */
    public static boolean isOlder(int a, int b) {
        return compare(a, b) < 0;
    }

    /**
     * Check if seqA is new or equal to seqB.
     */
    public static boolean isNewOrEqual(int a, int b) {
        return compare(a, b) >= 0;
    }
}
