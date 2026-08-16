package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.Reliability;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeader;
import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.packet.PacketHeaderCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketHeaderCodecTest {

    @Test
    void shouldEncodeAndDecodePacketHeader() {
        PacketHeader header = PacketHeader.builder()
                .type(PacketHeader.PacketType.DATA)
                .connId(123)
                .seq(456)
                .ack(789)
                .ackBits(0xFF)
                .channel(2)
                .payloadLen(100)
                .reliability(Reliability.RELIABLE)
                .build();

        byte[] payload = new byte[]{1, 2, 3, 4};
        byte[] encoded = PacketHeaderCodec.encode(header, payload);

        assertNotNull(encoded);
        assertEquals(PacketHeader.HEADER_SIZE + payload.length, encoded.length);

        PacketHeader decoded = PacketHeaderCodec.decode(java.nio.ByteBuffer.wrap(encoded));

        assertEquals(header.getType(), decoded.getType());
        assertEquals(header.getConnId(), decoded.getConnId());
        assertEquals(header.getSeq(), decoded.getSeq());
        assertEquals(header.getAck(), decoded.getAck());
        assertEquals(header.getAckBits(), decoded.getAckBits());
        assertEquals(header.getChannel(), decoded.getChannel());
        assertEquals(header.getPayloadLen(), decoded.getPayloadLen());
        assertEquals(header.getReliability(), decoded.getReliability());
    }

    @Test
    void shouldEncodeAndDecodeKeepAlive() {
        PacketHeader header = PacketHeader.builder()
                .type(PacketHeader.PacketType.KEEP_ALIVE)
                .connId(1)
                .seq(0)
                .channel(0)
                .payloadLen(0)
                .reliability(Reliability.UNRELIABLE)
                .build();

        byte[] encoded = PacketHeaderCodec.encode(header, new byte[0]);
        PacketHeader decoded = PacketHeaderCodec.decode(java.nio.ByteBuffer.wrap(encoded));

        assertEquals(PacketHeader.PacketType.KEEP_ALIVE, decoded.getType());
    }

    @Test
    void shouldRejectInvalidMagic() {
        byte[] data = new byte[PacketHeader.HEADER_SIZE];
        // Invalid magic
        data[0] = 0x00;

        assertThrows(IllegalArgumentException.class, () -> PacketHeaderCodec.decode(java.nio.ByteBuffer.wrap(data)));
    }

    @Test
    void shouldRejectUnsupportedVersion() {
        byte[] data = new byte[PacketHeader.HEADER_SIZE];
        // Valid magic
        data[0] = PacketHeader.MAGIC;
        // Wrong version
        data[1] = 99;

        assertThrows(IllegalArgumentException.class, () -> PacketHeaderCodec.decode(java.nio.ByteBuffer.wrap(data)));
    }

    @Test
    void shouldCalculateTotalSize() {
        assertEquals(15, PacketHeaderCodec.totalSize(0));
        assertEquals(115, PacketHeaderCodec.totalSize(100));
        assertEquals(1215, PacketHeaderCodec.totalSize(1200));
    }

    @Test
    void shouldEncodeAllPacketTypes() {
        for (PacketHeader.PacketType type : PacketHeader.PacketType.values()) {
            PacketHeader header = PacketHeader.builder()
                    .type(type)
                    .connId(0)
                    .seq(0)
                    .channel(0)
                    .payloadLen(0)
                    .reliability(Reliability.UNRELIABLE)
                    .build();

            byte[] encoded = PacketHeaderCodec.encode(header, new byte[0]);
            PacketHeader decoded = PacketHeaderCodec.decode(java.nio.ByteBuffer.wrap(encoded));
            assertEquals(type, decoded.getType(), "Failed for type: " + type);
        }
    }

    @Test
    void shouldEncodeAndDecodeNack() {
        PacketHeader header = PacketHeader.builder()
                .type(PacketHeader.PacketType.NACK)
                .connId(42)
                .seq(0)
                .ack(100)
                .ackBits(0xFF)
                .channel(0)
                .payloadLen(0)
                .reliability(Reliability.UNRELIABLE)
                .build();

        byte[] encoded = PacketHeaderCodec.encode(header, new byte[0]);
        PacketHeader decoded = PacketHeaderCodec.decode(java.nio.ByteBuffer.wrap(encoded));

        assertEquals(PacketHeader.PacketType.NACK, decoded.getType());
        assertEquals(42, decoded.getConnId());
        assertEquals(100, decoded.getAck());
    }
}
