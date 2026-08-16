package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeader.*;

/**
 * Serializes and deserializes PacketHeader to/from ByteBuffer.
 * Uses Little-Endian byte order.
 */
@UtilityClass
public final class PacketHeaderCodec {

    public byte[] encode(PacketHeader header, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Magic (1 byte)
        buffer.put(MAGIC);
        // Version (1 byte)
        buffer.put(VERSION);
        // PacketType (1 byte)
        buffer.put((byte) header.getType().getCode());
        // Connection ID (2 bytes)
        buffer.putShort((short) header.getConnId());
        // Sequence number (2 bytes)
        buffer.putShort((short) header.getSeq());
        // ACK number (2 bytes)
        buffer.putShort((short) header.getAck());
        // ACK bitfield (2 bytes)
        buffer.putShort((short) header.getAckBits());
        // Channel (1 byte)
        buffer.put((byte) header.getChannel());
        // Flags (1 byte) — reliability encoded in flags
        buffer.put((byte) header.getReliability().ordinal());
        // Payload length (2 bytes)
        buffer.putShort((short) header.getPayloadLen());
        // Payload
        buffer.put(payload);

        return buffer.array();
    }

    public PacketHeader decode(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Magic
        byte magic = buffer.get();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic: '" + (char) magic + "'");
        }

        // Version
        byte version = buffer.get();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }

        // Type
        PacketType type = PacketType.fromCode(buffer.get());
        // Connection ID
        int connId = buffer.getShort();
        // Sequence number
        int seq = buffer.getShort();
        // ACK
        int ack = buffer.getShort();
        // ACK bits
        long ackBits = buffer.getShort();
        // Channel
        int channel = buffer.get();
        // Flags (reliability)
        Reliability reliability = Reliability.values()[buffer.get()];
        // Payload length
        int payloadLen = buffer.getShort();

        return PacketHeader.builder()
                .type(type)
                .connId(connId)
                .seq(seq)
                .ack(ack)
                .ackBits(ackBits)
                .channel(channel)
                .payloadLen(payloadLen)
                .reliability(reliability)
                .build();
    }

    public int totalSize(int payloadLength) {
        return HEADER_SIZE + payloadLength;
    }
}
