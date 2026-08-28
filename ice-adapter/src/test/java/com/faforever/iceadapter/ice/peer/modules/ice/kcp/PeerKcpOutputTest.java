package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.ice.KcpPeerToPeerSenderModule;
import com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework.IceKcp;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Component;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PeerKcpOutput}.
 * <p>
 * Verifies that out(ByteBuf, IKcp) correctly prepends the KCP protocol marker
 * and forwards the combined payload to the Component.
 * </p>
 */
@Slf4j
@DisplayName("PeerKcpOutput")
class PeerKcpOutputTest {

    private Peer peerMock;
    private Component compMock;
    private List<byte[]> sentPackets;

    @BeforeEach
    void setUp() throws IOException {
        peerMock = mock(Peer.class);
        compMock = mock(Component.class);
        sentPackets = new ArrayList<>();

        when(peerMock.getComponent()).thenReturn(compMock);
        doAnswer(inv -> {
            byte[] data = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            byte[] copy = new byte[len];
            System.arraycopy(data, off, copy, 0, len);
            sentPackets.add(copy);
            return null;
        }).when(compMock).send(any(byte[].class), eq(0), anyInt());

        when(peerMock.getPeerIdentifier()).thenReturn("TestPeer");
    }

    private PeerKcpOutput createOutput(byte[] data, int off, int len) {
        PeerKcpOutput output = new PeerKcpOutput(peerMock);
        ByteBuf buf = Unpooled.wrappedBuffer(data, off, len);
        IceKcp kcpMock = mock(IceKcp.class);
        // PeerKcpOutput.out() already calls data.release(), so we must NOT release here
        output.out(buf, kcpMock);
        return output;
    }

    @Test
    @Timeout(value = 10)
    @DisplayName("out() should prepend KCP_PROTOCOL_MARKER to payload")
    void testMarkerPrepended() {
        byte[] payload = "hello".getBytes();
        PeerKcpOutput output = createOutput(payload, 0, payload.length);

        assertEquals(1, sentPackets.size(), "Should have sent exactly one packet");
        byte[] pkt = sentPackets.get(0);

        assertEquals(payload.length + 1, pkt.length,
                "Packet length should be payload + 1 marker byte");
        assertEquals(KcpPeerToPeerSenderModule.KCP_PROTOCOL_MARKER, (char) pkt[0],
                "First byte must be 'u' marker");
        assertArrayEquals(payload, java.util.Arrays.copyOfRange(pkt, 1, pkt.length),
                "Remaining bytes must match original payload");
    }

    @Test
    @Timeout(value = 10)
    @DisplayName("out() should handle empty payload")
    void testEmptyPayload() {
        byte[] payload = new byte[0];
        PeerKcpOutput output = createOutput(payload, 0, 0);

        assertEquals(1, sentPackets.size());
        byte[] pkt = sentPackets.get(0);
        assertEquals(1, pkt.length, "Only marker byte should be sent");
        assertEquals(KcpPeerToPeerSenderModule.KCP_PROTOCOL_MARKER, (char) pkt[0]);
    }

    @Test
    @Timeout(value = 10)
    @DisplayName("out() should handle large payload")
    void testLargePayload() {
        byte[] payload = new byte[1000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 256);
        }
        PeerKcpOutput output = createOutput(payload, 0, payload.length);

        assertEquals(1, sentPackets.size());
        byte[] pkt = sentPackets.get(0);
        assertEquals(payload.length + 1, pkt.length);
        assertArrayEquals(payload, java.util.Arrays.copyOfRange(pkt, 1, pkt.length));
    }

    @Test
    @Timeout(value = 10)
    @DisplayName("out() should handle null component gracefully")
    void testNullComponent() throws IOException {
        when(peerMock.getComponent()).thenReturn(null);
        byte[] payload = "test".getBytes();

        PeerKcpOutput output = new PeerKcpOutput(peerMock);
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        IceKcp kcpMock = mock(IceKcp.class);
        // PeerKcpOutput.out() calls data.release() internally even for null component
        output.out(buf, kcpMock);

        assertEquals(0, sentPackets.size(), "No packet should be sent when component is null");
    }

    @Test
    @Timeout(value = 10)
    @DisplayName("out() should preserve binary data with null bytes")
    void testBinaryDataWithNulls() {
        byte[] payload = new byte[]{0x00, 0x01, 0x00, (byte) 0xFF, 0x00};
        PeerKcpOutput output = createOutput(payload, 0, payload.length);

        assertEquals(1, sentPackets.size());
        byte[] pkt = sentPackets.get(0);
        assertArrayEquals(payload, Arrays.copyOfRange(pkt, 1, pkt.length));
    }

    @AfterEach
    void tearDown() {
        sentPackets.clear();
    }
}
