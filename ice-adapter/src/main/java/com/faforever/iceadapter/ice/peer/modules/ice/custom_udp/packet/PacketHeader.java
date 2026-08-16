package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * Binary packet header for Custom Reliable UDP.
 * <p>
 * Format (15 bytes):
 * +------+-----+-----+------+----+-----+---------+--------+--------+
 * | Magic(1)| Ver(1) | Type(1) | ConnId(2) | Seq(2) | Ack(2) | AckBits(2) |
 * +-----+-----+-----+------+----+-----+---------+--------+--------+
 * | Channel(1) | Flags(1) | PayloadLen(2) |
 * +-----------------------------------------+
 */
@Getter
@Builder
@ToString
public final class PacketHeader {

    public static final int HEADER_SIZE = 15;
    public static final byte MAGIC = 'u';
    public static final byte VERSION = 1;

    private final PacketType type;
    private final int connId;
    private final int seq;
    private final int ack;
    private final long ackBits;
    private final int channel;
    private final int payloadLen;
    private final Reliability reliability;

    @Getter
    @RequiredArgsConstructor
    public enum PacketType {
        DATA(0),
        KEEP_ALIVE(1),
        DISCONNECT(2),
        NACK(3);
        private final int code;

        public static PacketType fromCode(int code) {
            for (PacketType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown PacketType code: " + code);
        }
    }
}
