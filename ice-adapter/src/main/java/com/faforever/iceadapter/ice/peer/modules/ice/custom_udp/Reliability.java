package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp;

/**
 * Defines the delivery guarantees and ordering semantics for UDP packets.
 *
 * <p>This enum is modeled after ENet's reliability channels and determines
 * how packets are handled by the Custom Reliable UDP transport. Each level
 * adds additional guarantees on top of the previous one:</p>
 *
 * <ul>
 *   <li><b>UNRELIABLE</b> — Packets are sent immediately with no guarantees.
 *       They may be lost, duplicated, or arrive out of order. Suitable for
 *       high-frequency data where freshness matters more than completeness,
 *       e.g. real-time player positions or telemetry.</li>
 *
 *   <li><b>RELIABLE</b> — Packets are guaranteed to arrive, but order is not
 *       preserved. The sender will retransmit lost packets until they are
 *       acknowledged by the receiver. Suitable for data that must arrive but
 *       where ordering is irrelevant, e.g. chat messages or state updates.</li>
 *
 *   <li><b>UNRELIABLE_ORDERED</b> — Packets may be lost, but those that arrive
 *       will be delivered in sequence order. The receiver deduplicates and
 *       reorders using sequence numbers. Suitable for streaming data where
 *       in-order delivery matters but loss is acceptable, e.g. voice chat.</li>
 *
 *   <li><b>RELIABLE_ORDERED</b> — Strongest guarantee: all packets are delivered
 *       without loss and in the exact order sent. Lost packets are retransmitted
 *       and the receiver uses sequence numbers to reconstruct the original order.
 *       Suitable for critical protocol messages, commands, or state synchronization.</li>
 * </ul>
 *
 * <p><b>Trade-offs:</b> Higher reliability levels increase latency and bandwidth
 * usage due to acknowledgements and retransmissions. Choose the weakest level
 * that satisfies the application's correctness requirements.</p>
 *
 * @see <a href="https://libenet.github.io/docs/structENetChannel.html">ENet reliability channels</a>
 */
public enum Reliability {
    UNRELIABLE,
    RELIABLE,
    UNRELIABLE_ORDERED,
    RELIABLE_ORDERED
}
